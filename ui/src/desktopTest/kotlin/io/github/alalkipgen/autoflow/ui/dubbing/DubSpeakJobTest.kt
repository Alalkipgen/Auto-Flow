package io.github.alalkipgen.autoflow.ui.dubbing

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.TtsProvider
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.ai.fake.FakeTtsProvider
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.dubbing.DubRules
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.LexiconEntry
import io.github.alalkipgen.autoflow.core.recap.RpmPacer
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.ui.jobs.DesktopAiJobRunner
import io.github.alalkipgen.autoflow.ui.jobs.RecapJobProcessor
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitleVersions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** D-2 (specs/dubbing.md §4–§5): one take per line, only for an approved script; a redo of one line leaves the others. */
class DubSpeakJobTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val jobs = AiJobStore(fs, "/files/ai-jobs")
    private val dir = "/p/Dub.autoflow"
    private val config = ToolsConfig()
    private val lexicon = Lexicon(listOf(LexiconEntry("AF", "Auto Flow")))
    private val lock = VoiceLock("gemini", "tts-a", "Kore", "my")
    private val doc = SubtitleDoc(source = "srt", nextId = 4, cues = listOf(
        SubtitleCue("c1", 0, 1_500, "Hi.", textMy = "AF ပါ။"),
        SubtitleCue("c2", 2_000, 4_000, "How are you?", textMy = "နေကောင်းလား။"),
        SubtitleCue("c3", 4_500, 6_000, "Fine.", textMy = "ကောင်းပါတယ်။"),
    ))

    private val calls = mutableListOf<Pair<String, String?>>()
    private val busy = mutableSetOf<Int>()
    private val fake = FakeTtsProvider(msPerChar = 100)
    private val tts = object : TtsProvider by fake {
        override suspend fun speak(text: String, voice: VoiceRef, lang: String, style: String?, lockedModel: String?): AiResult<ByteArray> {
            calls += text to lockedModel
            if (calls.size in busy) return AiResult.Failed("http_503", "overloaded")
            return fake.speak(text, voice, lang, style, lockedModel)
        }
    }
    private val job = DubSpeakJob(session, fs, tts, config, { lexicon })
    private val versions = SubtitleVersions(session, fs, kotlin.time.Clock.System)

    private fun setUp(approved: Boolean = true) {
        val p = (store.create(dir, "Dub", Mode.DUBBING) as CoreResult.Ok).value
        val st = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(),
            ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
        val tool = DubbingTool(lock = lock, tone = "news")
        store.save(dir, p.copy(subtitles = SubtitlesTool(video = SourceVideo("source/movie.mp4", 9, 8_000, 1920, 1080, 25.0), current = st),
            dubbing = if (approved) DubRules.approve(tool, 8_000, config.dubbing.maxSourceMs, doc, 1)!! else tool))
    }

    private fun project() = (store.open(dir) as CoreResult.Ok).value
    private fun takes() = project().dubbing!!.takes
    private fun TestScope.runner(): Pair<CoroutineScope, DesktopAiJobRunner> {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        return scope to DesktopAiJobRunner(jobs, scope, RecapJobProcessor({ null }, { null }, { null }, { null }, { job }), pacer = { RpmPacer(1_000) })
    }

    @Test fun nothingIsPlannedOrSentBeforeApproval() {
        setUp(approved = false)
        assertNull(job.plan(dir))
        assertTrue(calls.isEmpty())
    }

    @Test fun speaksEveryLineMeasuresTheVoiceAndRedoOfOneLineLeavesTheOthers() = runTest {
        setUp()
        val request = job.plan(dir)!!
        assertEquals(listOf("c1", "c2", "c3"), request.lines.map { it.id })
        assertEquals("Auto Flow ပါ။", request.lines[0].spoken, "the Lexicon is applied before TTS")
        val (scope, r) = runner()
        val id = job.enqueue(r, dir, request)
        advanceUntilIdle()
        assertEquals(AiJobKind.DUB_TTS, jobs.load(id)!!.kind)
        assertEquals(AiJobState.DONE, jobs.load(id)!!.state, jobs.load(id)!!.error)
        assertEquals(listOf("tts-a", "tts-a", "tts-a"), calls.map { it.second }, "the locked model, never another one")
        val first = takes()
        assertEquals(setOf("c1", "c2", "c3"), first.keys)
        val c1 = first.getValue("c1")
        val chars = "Auto Flow ပါ။".length
        assertTrue(c1.ms in (chars * 100L - 25)..(chars * 100L), "voice length without the fades: ${c1.ms}")
        assertTrue(c1.leadMs in 0..10, "lead ${c1.leadMs}")
        assertEquals(ArtifactKind.AUDIO, (ArtifactStore(fs, dir).get(c1.au) as CoreResult.Ok).value.id.kind)
        assertTrue(job.plan(dir)!!.lines.isEmpty(), "every line has its take")

        val redo = job.plan(dir, only = setOf("c2"))!!
        assertEquals(listOf("c2"), redo.lines.map { it.id })
        job.enqueue(r, dir, redo)
        advanceUntilIdle()
        assertEquals(4, calls.size, "↻ sends exactly that line")
        val after = takes()
        assertEquals(first.getValue("c1"), after.getValue("c1"))
        assertEquals(first.getValue("c3"), after.getValue("c3"))
        assertEquals(first.getValue("c2").key, after.getValue("c2").key, "the same line, lock and tone (the fake voice is identical, so the content-addressed take is too)")
        scope.cancel()
    }

    @Test fun busyPausesResumeSendsOnlyTheRestAndAChangedLineIsNotSent() = runTest {
        setUp()
        busy += 2
        val (scope, r) = runner()
        val id = job.enqueue(r, dir, job.plan(dir)!!)
        advanceUntilIdle()
        assertEquals(AiJobState.PAUSED, jobs.load(id)!!.state)
        assertEquals(listOf("c2", "c3"), jobs.load(id)!!.items)
        assertEquals(setOf("c1"), takes().keys)
        r.enqueue(jobs.load(id)!!) // Resume (or the app restarted)
        advanceUntilIdle()
        assertEquals(AiJobState.DONE, jobs.load(id)!!.state)
        assertEquals(4, calls.size, "only the lines left, c2 once more")
        assertEquals(setOf("c1", "c2", "c3"), takes().keys)

        // c3 changes after Start: its old take stays but is no longer current, and nothing is sent or attached.
        val id2 = job.enqueue(r, dir, job.plan(dir, only = setOf("c3"))!!)
        current.set(OpenProject(dir, project())) // the owner edits the open project
        val edited = DubRules.setSpoken(versions.read(dir, project().subtitles!!.current!!)!!, "c3", "ကောင်းတယ်ဗျ။")
        assertTrue(versions.save(dir, edited) is CoreResult.Ok)
        val before = calls.size
        val old = takes().getValue("c3")
        advanceUntilIdle()
        assertEquals(AiJobState.DONE, jobs.load(id2)!!.state)
        assertEquals(before, calls.size, "the line changed after Start (and the approval was cleared), so nothing was sent")
        assertEquals(old, takes().getValue("c3"))
        assertNull(project().dubbing!!.scriptApproved, "the edit cleared the approval")
        assertNull(job.plan(dir), "no voice until the new script is approved")
        scope.cancel()
    }
}
