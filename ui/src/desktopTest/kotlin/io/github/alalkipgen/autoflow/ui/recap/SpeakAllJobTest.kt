package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.TtsCapabilities
import io.github.alalkipgen.autoflow.ai.TtsProvider
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.core.recap.RecapAudio
import io.github.alalkipgen.autoflow.core.recap.RecapBlock
import io.github.alalkipgen.autoflow.core.recap.RecapClip
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.RecapStory
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.RpmPacer
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.jobs.DesktopAiJobRunner
import io.github.alalkipgen.autoflow.ui.jobs.RecapJobProcessor
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Audit F11 end to end: the Voice card's Speak All (RecapModel) → a TTS_ALL job in the app's runner → the
 * project-bound processor ([RecapJobProcessor]) → takes in the job's own project. The job is persisted with the
 * project folder and block ids, goes on in its project when another one is opened, and after an app restart resumes
 * without voicing a block twice — also when its checkpoint was lost and blocks were inserted meanwhile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeakAllJobTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val jobs = AiJobStore(fs, "/files/ai-jobs")
    private val sent = mutableListOf<String>()
    /** Holds the TTS answer for this text until completed. */
    private var gate: Pair<String, CompletableDeferred<Unit>>? = null
    private var n = 0

    private fun wav(): ByteArray {
        val samples = 8 * (1000 + 10 * ++n) // a different take each time, like real TTS
        return java.io.ByteArrayOutputStream().apply {
            fun i(v: Int) { write(v and 255); write(v shr 8 and 255); write(v shr 16 and 255); write(v shr 24 and 255) }
            fun s(v: Int) { write(v and 255); write(v shr 8 and 255) }
            write("RIFF".toByteArray()); i(36 + samples * 2); write("WAVE".toByteArray())
            write("fmt ".toByteArray()); i(16); s(1); s(1); i(8000); i(16000); s(2); s(16)
            write("data".toByteArray()); i(samples * 2); write(ByteArray(samples * 2))
        }.toByteArray()
    }

    private val tts = object : TtsProvider {
        override val capabilities = TtsCapabilities(burmese = true)
        override suspend fun speak(text: String, voice: VoiceRef, lang: String, style: String?, lockedModel: String?): AiResult<ByteArray> {
            sent += text
            gate?.takeIf { it.first == text }?.second?.await()
            return AiResult.Ok(wav(), Usage("gemini-3.8-flash-tts"))
        }
    }

    /** One run of the app: its scope, runner (project-bound processor) and Recap model. */
    private class App(val scope: CoroutineScope, val runner: DesktopAiJobRunner, val model: RecapModel)

    private fun TestScope.app(): App {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        var model: RecapModel? = null
        val runner = DesktopAiJobRunner(jobs, scope, RecapJobProcessor { model }, pacer = { RpmPacer(1_000) })
        model = RecapModel(session, fs, null, null, scope, tts = tts, jobs = runner)
        return App(scope, runner, model)
    }

    private fun block(id: String) = RecapBlock(id, listOf(RecapClip(0, 4_000)), "$id text")

    private fun putStory(dir: String, blocks: List<RecapBlock>): ArtifactId =
        (ArtifactStore(fs, dir).put(CoreJson.encodeToString(RecapStory.serializer(), RecapStory(source = "manual", blocks = blocks)).encodeToByteArray(), ArtifactKind.RECAP_SCRIPT, "json") as CoreResult.Ok).value.id

    /** Project [dir] with an approved story of [ids], saved (as the Dashboard creates it). */
    private fun create(dir: String, title: String, vararg ids: String) {
        val p = (store.create(dir, title, Mode.MOVIE_RECAP) as CoreResult.Ok).value
        val recap = RecapV2(
            rightsConfirmed = true, movie = RecapMovie("source/movie.mp4", 1, 20_000, 320, 180),
            scenes = listOf(RecapScene(0, 4_000, true)), story = putStory(dir, ids.map(::block)), storyApproved = true,
            audio = RecapAudio(polish = false, loudness = false),
        )
        store.save(dir, p.copy(recap = recap))
    }

    private fun open(dir: String, m: RecapModel) {
        current.set(OpenProject(dir, (store.open(dir) as CoreResult.Ok).value))
        m.refresh()
    }

    private fun disk(dir: String): RecapV2 = (store.open(dir) as CoreResult.Ok).value.recap!!

    @Test fun speakAllRunsAsAPersistentJobOfThisProjectsBlocks() = runTest {
        create("/a", "A", "A1", "A2", "A3")
        val app = app()
        open("/a", app.model)
        gate = "A2 text" to CompletableDeferred()
        app.model.speakAll()
        val follow = assertNotNull(app.model.speakAll(confirmed = true))
        runCurrent()

        val job = jobs.loadAll().single()
        assertEquals(AiJobKind.TTS_ALL, job.kind)
        assertEquals("/a", job.project, "the job knows its project folder")
        assertEquals(AiJobState.RUNNING, job.state)
        assertEquals(listOf("A2", "A3"), job.items, "block ids, checkpointed after A1")
        assertTrue(app.model.state.value.busy)

        gate!!.second.complete(Unit)
        follow.join()
        assertEquals(AiJobState.DONE, jobs.load(job.id)!!.state)
        assertEquals(listOf("A1 text", "A2 text", "A3 text"), sent)
        assertEquals(listOf("A1", "A2", "A3"), disk("/a").takes.map { it.block })
        assertEquals("🔊 Spoken 3 scenes.", app.model.state.value.message)
        app.scope.cancel()
    }

    @Test fun speakAllGoesOnInItsOwnProjectWhenAnotherIsOpened() = runTest {
        create("/a", "A", "A1", "A2")
        create("/b", "B", "B1")
        val app = app()
        open("/a", app.model)
        gate = "A1 text" to CompletableDeferred()
        app.model.speakAll()
        val follow = assertNotNull(app.model.speakAll(confirmed = true))
        runCurrent()

        open("/b", app.model) // while A1's answer is on its way
        gate!!.second.complete(Unit)
        follow.join()
        advanceUntilIdle()
        assertEquals("Speak All goes on for A: see Jobs.", app.model.state.value.message)
        assertEquals(listOf("A1 text", "A2 text"), sent, "only project A's blocks")
        assertEquals(listOf("A1", "A2"), disk("/a").takes.map { it.block })
        assertEquals(emptyList(), disk("/b").takes, "nothing lands in the open project")
        assertEquals(AiJobState.DONE, jobs.loadAll().single().state)
        app.scope.cancel()
    }

    @Test fun aRestartResumesSpeakAllWithoutVoicingABlockTwice() = runTest {
        create("/a", "A", "A1", "A2", "A3")
        val first = app()
        open("/a", first.model)
        gate = "A2 text" to CompletableDeferred()
        first.model.speakAll()
        first.model.speakAll(confirmed = true)
        runCurrent()
        first.scope.cancel() // the app is closed while A2 is being spoken
        runCurrent()
        val id = jobs.loadAll().single().id
        assertEquals(AiJobState.RUNNING, jobs.load(id)!!.state)
        // The worst case: A1's take is saved but its checkpoint is lost; and the user inserts a block A0 in front.
        jobs.update(id) { it.copy(items = listOf("A1", "A2", "A3")) }
        val r = disk("/a")
        store.save("/a", (store.open("/a") as CoreResult.Ok).value.copy(recap = r.copy(story = putStory("/a", listOf(block("A0"), block("A1"), block("A2"), block("A3"))))))
        assertEquals(listOf("A1"), disk("/a").takes.map { it.block })

        gate = null
        sent.clear()
        val second = app() // the app starts again: the runner resumes the job by itself
        advanceUntilIdle()
        assertEquals(listOf("A2 text", "A3 text"), sent, "A1 is found in the project; A0 is not part of the job")
        assertEquals(AiJobState.DONE, jobs.load(id)!!.state)
        assertEquals(listOf("A1", "A2", "A3"), disk("/a").takes.map { it.block }, "one take per block, no duplicates")
        second.scope.cancel()
    }
}
