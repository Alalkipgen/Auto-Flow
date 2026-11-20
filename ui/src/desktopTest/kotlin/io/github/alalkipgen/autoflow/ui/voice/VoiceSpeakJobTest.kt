package io.github.alalkipgen.autoflow.ui.voice

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.TtsProvider
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.ai.fake.FakeTtsProvider
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.LexiconEntry
import io.github.alalkipgen.autoflow.core.recap.RpmPacer
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.voice.VoiceTakes
import io.github.alalkipgen.autoflow.ui.jobs.DesktopAiJobRunner
import io.github.alalkipgen.autoflow.ui.jobs.RecapJobProcessor
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** V-1e with the offline fake TTS: only parts without a take are sent, with the exact lock and tone; busy pauses, Resume goes on. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSpeakJobTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), CurrentProject())
    private val jobs = AiJobStore(fs, "/files/ai-jobs")
    private val dir = "/p/Voice.autoflow"
    private val config = ToolsConfig(tts = ToolsConfig.Tts(maxCharsPerPart = 10))
    private val lexicon = Lexicon(listOf(LexiconEntry("AF", "Auto Flow")))
    private val lock = VoiceLock("gemini", "tts-a", "Kore", "my")

    private data class Call(val text: String, val voice: String, val style: String?, val model: String?)
    private val calls = mutableListOf<Call>()
    private val busy = mutableSetOf<Int>()
    private val fake = FakeTtsProvider()
    private val tts = object : TtsProvider by fake {
        override suspend fun speak(text: String, voice: VoiceRef, lang: String, style: String?, lockedModel: String?): AiResult<ByteArray> {
            calls += Call(text, voice.id, style, lockedModel)
            if (calls.size in busy) return AiResult.Failed("http_503", "overloaded")
            return fake.speak(text, voice, lang, style, lockedModel)
        }
    }
    private val job = VoiceSpeakJob(session, fs, tts, config, { lexicon })
    private val kept = ArtifactId("au_000000000001")

    init {
        val p = (store.create(dir, "Voice", Mode.VOICE_AUDIO) as CoreResult.Ok).value
        val v = VoiceTakes.plan(VoiceTool(task = VoiceTool.TASK_TEXT, text = "AF one.\nTwo here.\nThree.", lock = lock, tone = "news"), 10, lexicon)
        store.save(dir, p.copy(voice = v.copy(parts = v.parts.map { if (it.id == "p2") it.copy(take = kept) else it })))
    }

    private fun voice() = (store.open(dir) as CoreResult.Ok).value.voice!!
    private fun TestScope.runner(): Pair<CoroutineScope, DesktopAiJobRunner> {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        return scope to DesktopAiJobRunner(jobs, scope, RecapJobProcessor({ null }, { null }, { null }, { job }), pacer = { RpmPacer(1_000) })
    }

    @Test fun speaksOnlyPartsWithoutATakeWithTheLockAndToneAndKeepsTheirTakes() = runTest {
        val request = job.plan(dir)!!
        assertEquals(listOf("p1", "p3"), request.parts.map { it.id })
        assertEquals("Auto Flow one.", request.parts.first().spoken, "the Lexicon is applied before TTS")
        assertTrue(calls.isEmpty(), "planning sends nothing")
        val (scope, r) = runner()
        val id = job.enqueue(r, dir, request)
        advanceUntilIdle()
        val saved = jobs.load(id)!!
        assertEquals(AiJobKind.TEXT_TTS, saved.kind)
        assertEquals(AiJobState.DONE, saved.state, saved.error)
        assertEquals(listOf(Call("Auto Flow one.", "Kore", config.tts.style("news"), "tts-a"), Call("Three.", "Kore", config.tts.style("news"), "tts-a")), calls)
        val parts = voice().parts
        assertEquals(kept, parts[1].take, "a part with a take is never sent again")
        for (p in listOf(parts[0], parts[2])) {
            val a = (ArtifactStore(fs, dir).get(p.take!!) as CoreResult.Ok).value
            assertEquals(ArtifactKind.AUDIO, a.id.kind)
        }
        assertTrue(job.plan(dir)!!.parts.isEmpty(), "every part has its take")
        assertEquals(listOf("p1"), job.plan(dir, only = setOf("p1"))!!.parts.map { it.id }, "↻ sends exactly that part")
        scope.cancel()
    }

    @Test fun busyPausesResumeSendsOnlyTheRestAndAChangedPartIsNotSent() = runTest {
        busy += 2
        val (scope, r) = runner()
        val id = job.enqueue(r, dir, job.plan(dir)!!)
        advanceUntilIdle()
        assertEquals(AiJobState.PAUSED, jobs.load(id)!!.state)
        assertEquals(listOf("p3"), jobs.load(id)!!.items)
        assertNotNull(voice().parts[0].take)
        val first = calls.size
        r.enqueue(jobs.load(id)!!)
        advanceUntilIdle()
        assertEquals(AiJobState.DONE, jobs.load(id)!!.state)
        assertEquals(first + 1, calls.size, "only the part left")

        val p = (store.open(dir) as CoreResult.Ok).value
        val id2 = job.enqueue(r, dir, job.plan(dir, only = setOf("p3"))!!)
        store.save(dir, p.copy(voice = VoiceTakes.plan(p.voice!!.copy(text = "AF one.\nTwo here.\nChanged."), 10, lexicon)))
        val before = calls.size
        advanceUntilIdle()
        assertEquals(AiJobState.DONE, jobs.load(id2)!!.state)
        assertEquals(before, calls.size, "the part changed after Start, so nothing was sent")
        assertNull(voice().parts[2].take)
        scope.cancel()
    }
}
