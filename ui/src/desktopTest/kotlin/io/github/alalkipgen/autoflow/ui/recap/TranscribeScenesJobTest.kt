package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.SceneSpan
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.TranscribeProvider
import io.github.alalkipgen.autoflow.ai.Transcript as AiTranscript
import io.github.alalkipgen.autoflow.ai.TranscriptSegment
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.recap.RecapScriptGenerator
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.RecapSources
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.RpmPacer
import io.github.alalkipgen.autoflow.core.recap.Transcript
import io.github.alalkipgen.autoflow.core.recap.TranscriptRange
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
import kotlin.test.assertNull
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
import kotlinx.serialization.json.JsonElement

/**
 * 5B-1 end to end with a fake Gemini and a fake sound cutter: "Transcribe picked scenes" (RecapModel) → confirm card
 * (scenes, requests, today's usage; nothing sent) → a TRANSCRIBE job of this project in the app's runner → the
 * project-bound processor → lines in movie time in that project's `tx`. A failed request keeps the rest, Retry sends
 * only the scenes left; Cancel keeps what is saved; another open project gets nothing; a new movie is refused; a
 * restart never sends a transcribed scene again; the script writer uses the lines like subtitles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscribeScenesJobTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val jobs = AiJobStore(fs, "/files/ai-jobs")
    private val usage = UsageCounter(fs, "/files/usage.json")

    /** Every request: the scenes (audio time) and the cut sound it carried. */
    private val sent = mutableListOf<Pair<List<SceneSpan>, String>>()
    /** The answer of request n (1-based); default = one line per scene in its middle. */
    private val answers = mutableMapOf<Int, AiResult<List<List<TranscriptSegment>>>>()
    /** Holds request n until completed. */
    private var gate: Pair<Int, CompletableDeferred<Unit>>? = null

    private val provider = object : TranscribeProvider {
        override suspend fun transcribe(audio: ByteArray, lang: String, mime: String, lines: List<String>): AiResult<AiTranscript> =
            error("not used")

        override suspend fun transcribeScenes(audio: ByteArray, mime: String, scenes: List<SceneSpan>, lang: String?): AiResult<List<List<TranscriptSegment>>> {
            sent += scenes to audio.decodeToString()
            val n = sent.size
            gate?.takeIf { it.first == n }?.second?.await()
            return answers[n] ?: AiResult.Ok(
                scenes.mapIndexed { i, s -> listOf(TranscriptSegment(s.startMs + 1_000, s.startMs + 2_500, "line ${i + 1} of request $n")) },
                Usage("gemini-test"),
            )
        }
    }

    /** "Cuts" the sound: the ranges as text, so a test sees exactly what would be sent. */
    private val cutter = SceneAudioCutter { movie, chunk, out, _ ->
        fs.writeDurably(out, "$movie|${chunk.ranges.joinToString(",") { "${it.inMs}-${it.outMs}" }}".encodeToByteArray())
        null
    }

    private val prompts = mutableListOf<String>()
    private val text = object : TextProvider {
        override suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> {
            prompts += prompt
            return AiResult.Failed("test", "not answered in this test")
        }
    }

    private class App(val scope: CoroutineScope, val runner: DesktopAiJobRunner, val model: RecapModel)

    private fun TestScope.app(): App {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        var model: RecapModel? = null
        val runner = DesktopAiJobRunner(jobs, scope, RecapJobProcessor { model }, pacer = { RpmPacer(1_000) })
        model = RecapModel(
            session, fs, null, null, scope, generator = RecapScriptGenerator(text), jobs = runner,
            transcriber = provider, usage = usage, sceneAudio = cutter,
        )
        return App(scope, runner, model)
    }

    /** Project [dir]: a 30-minute movie, [scenes] picked; [srt] = `source/subtitles.srt`. */
    private fun create(dir: String, title: String, scenes: List<RecapScene>, srt: String? = null, size: Long = 1) {
        val p = (store.create(dir, title, Mode.MOVIE_RECAP) as CoreResult.Ok).value
        if (srt != null) { fs.createDirectories("$dir/source"); fs.writeDurably("$dir/source/subtitles.srt", srt.encodeToByteArray()) }
        val recap = RecapV2(
            rightsConfirmed = true, movie = RecapMovie("source/movie.mp4", size, 1_800_000, 320, 180),
            scenes = scenes, sources = RecapSources(subtitles = srt?.let { "source/subtitles.srt" }),
        )
        store.save(dir, p.copy(recap = recap))
    }

    private fun open(dir: String, m: RecapModel) {
        current.set(OpenProject(dir, (store.open(dir) as CoreResult.Ok).value))
        m.refresh()
    }

    private fun disk(dir: String): RecapV2 = (store.open(dir) as CoreResult.Ok).value.recap!!

    private fun transcript(dir: String): Transcript? = disk(dir).sources.transcript?.let { id ->
        CoreJson.decodeFromString(Transcript.serializer(), (ArtifactStore(fs, dir).readBytes(id) as CoreResult.Ok).value.decodeToString())
    }

    private fun picked(vararg ranges: Pair<Long, Long>) = ranges.map { (a, b) -> RecapScene(a, b, picked = true) }

    @Test fun theConfirmCardComesFirstAndNothingIsSentBeforeStart() = runTest {
        create("/a", "A", picked(0L to 10_000L, 20_000L to 30_000L, 60_000L to 75_000L) + RecapScene(40_000, 50_000), srt = "1\n00:00:02,000 --> 00:00:04,000\nHello\n")
        repeat(3) { usage.record("gemini-test") }
        val app = app()
        open("/a", app.model)
        assertEquals(2, app.model.state.value.transcribe.todoScenes, "the scene with an .srt line and the unpicked one are not sent")
        app.model.transcribeScenes()
        advanceUntilIdle()
        val confirm = assertNotNull(app.model.state.value.transcribe.confirm)
        assertEquals(TranscribeConfirm(scenes = 2, requests = 1, audioMs = 25_000, usedToday = 3), confirm)
        assertTrue(sent.isEmpty() && jobs.loadAll().isEmpty(), "nothing is sent or queued before Start")
        app.model.dismissTranscribe()
        assertNull(app.model.state.value.transcribe.confirm)
        assertTrue(sent.isEmpty())
        app.scope.cancel()
    }

    @Test fun startIsAJobOfThisProjectAndSavesMovieTimeLinesTheScriptWriterUses() = runTest {
        create("/a", "A", picked(10_000L to 20_000L, 40_000L to 46_000L))
        val app = app()
        open("/a", app.model)
        app.model.transcribeScenes()
        gate = 1 to CompletableDeferred()
        val follow = assertNotNull(app.model.transcribeScenes(confirmed = true))
        runCurrent()
        val job = jobs.loadAll().single()
        assertEquals(AiJobKind.TRANSCRIBE, job.kind)
        assertEquals("/a", job.project)
        assertEquals(listOf("m1-1800000:10000-20000,40000-46000"), job.items, "one request: the movie and its ranges")
        assertTrue(app.model.state.value.busy)
        assertEquals(listOf(SceneSpan(0, 10_000), SceneSpan(10_000, 16_000)), sent.single().first, "scenes in the joined sound's time")
        assertEquals("/a/source/movie.mp4|10000-20000,40000-46000", sent.single().second, "only the picked ranges' sound")

        gate!!.second.complete(Unit)
        follow.join()
        assertEquals(AiJobState.DONE, jobs.load(job.id)!!.state)
        val tx = assertNotNull(transcript("/a"))
        assertEquals(Transcript.SOURCE_GEMINI_SCENES, tx.source)
        assertEquals(listOf(11_000L to 12_500L, 41_000L to 42_500L), tx.lines.map { it.startMs to it.endMs }, "mapped back to movie time")
        assertEquals(listOf(TranscriptRange(10_000, 20_000), TranscriptRange(40_000, 46_000)), tx.scenes)
        val t = app.model.state.value.transcribe
        assertEquals(2, t.lines)
        assertEquals(0, t.todoScenes)
        assertNull(t.job)
        assertTrue(app.model.state.value.message!!.startsWith("Transcribed: 2 lines"), app.model.state.value.message)

        // Again: every picked scene has a transcript now — nothing to send.
        app.model.transcribeScenes()
        assertEquals("Every picked scene already has subtitles or a transcript.", app.model.state.value.message)
        // The script writer gets the lines like subtitle lines (no summary needed).
        app.model.writeScript()!!.join()
        assertTrue(prompts.single().contains("line 1 of request 1") && prompts.single().contains("line 2 of request 1"), prompts.single())
        app.scope.cancel()
    }

    @Test fun aFailedRequestKeepsTheRestAndRetrySendsOnlyTheScenesLeft() = runTest {
        // Two 6-minute scenes: more than 10 minutes together, so two requests.
        create("/a", "A", picked(0L to 360_000L, 400_000L to 760_000L))
        val app = app()
        open("/a", app.model)
        app.model.transcribeScenes()
        assertEquals(2, app.model.state.value.transcribe.confirm!!.requests)
        answers[2] = AiResult.Failed("http_400", "bad request")
        app.model.transcribeScenes(confirmed = true)!!.join()
        val job = jobs.loadAll().single()
        assertEquals(AiJobState.FAILED, jobs.load(job.id)!!.state)
        assertEquals(listOf("m1-1800000:400000-760000"), jobs.load(job.id)!!.items, "the failed request stays")
        assertEquals(listOf(TranscriptRange(0, 360_000)), transcript("/a")!!.scenes, "the first request's lines are saved")
        val failed = assertNotNull(app.model.state.value.transcribe.job)
        assertEquals(AiJobState.FAILED, failed.state)
        assertTrue(app.model.state.value.message!!.contains("Retry sends only the scenes left"), app.model.state.value.message)

        app.model.retryTranscribe()!!.join()
        assertEquals(3, sent.size, "Retry sent only the second scene again")
        assertEquals("/a/source/movie.mp4|400000-760000", sent.last().second)
        assertEquals(AiJobState.DONE, jobs.load(job.id)!!.state)
        assertEquals(listOf(TranscriptRange(0, 360_000), TranscriptRange(400_000, 760_000)), transcript("/a")!!.scenes)
        assertNull(app.model.state.value.transcribe.job)
        app.scope.cancel()
    }

    @Test fun aBusyModelPausesTheJob() = runTest {
        create("/a", "A", picked(0L to 10_000L))
        val app = app()
        open("/a", app.model)
        answers[1] = AiResult.Failed("http_503", "overloaded")
        app.model.transcribeScenes()
        app.model.transcribeScenes(confirmed = true)!!.join()
        assertEquals(AiJobState.PAUSED, jobs.loadAll().single().state)
        assertEquals(AiJobState.PAUSED, app.model.state.value.transcribe.job!!.state)
        assertNull(transcript("/a"))
        app.model.retryTranscribe()!!.join()
        assertEquals(AiJobState.DONE, jobs.loadAll().single().state)
        assertEquals(1, transcript("/a")!!.lines.size)
        app.scope.cancel()
    }

    @Test fun cancelStopsTheJobAndKeepsWhatIsSaved() = runTest {
        create("/a", "A", picked(0L to 360_000L, 400_000L to 760_000L))
        val app = app()
        open("/a", app.model)
        app.model.transcribeScenes()
        gate = 2 to CompletableDeferred()
        val follow = assertNotNull(app.model.transcribeScenes(confirmed = true))
        runCurrent()
        assertEquals(2, sent.size)
        app.model.cancelTranscribe()
        runCurrent()
        follow.join()
        assertEquals(AiJobState.CANCELLED, jobs.loadAll().single().state)
        assertEquals(listOf(TranscriptRange(0, 360_000)), transcript("/a")!!.scenes, "the first scene stays, the cancelled one is not saved")
        assertEquals("Transcribe cancelled. Scenes already transcribed stay.", app.model.state.value.message)
        assertEquals(1, app.model.state.value.transcribe.todoScenes, "the cancelled scene can be transcribed later")
        app.scope.cancel()
    }

    @Test fun theLinesLandInTheJobsProjectWhenAnotherIsOpened() = runTest {
        create("/a", "A", picked(0L to 10_000L))
        create("/b", "B", picked(0L to 10_000L))
        val app = app()
        open("/a", app.model)
        app.model.transcribeScenes()
        gate = 1 to CompletableDeferred()
        val follow = assertNotNull(app.model.transcribeScenes(confirmed = true))
        runCurrent()
        open("/b", app.model) // while A's answer is on its way
        gate!!.second.complete(Unit)
        follow.join()
        advanceUntilIdle()
        assertEquals("Transcribe goes on for A: see Jobs.", app.model.state.value.message)
        assertEquals(1, transcript("/a")!!.lines.size)
        assertNull(disk("/b").sources.transcript, "nothing lands in the open project")
        assertEquals(1, app.model.state.value.transcribe.todoScenes, "B still has its own scene to transcribe")
        app.scope.cancel()
    }

    @Test fun aMovieImportedMeanwhileIsRefusedNotMixed() = runTest {
        create("/a", "A", picked(0L to 10_000L))
        val app = app()
        open("/a", app.model)
        app.model.transcribeScenes()
        gate = 1 to CompletableDeferred()
        val follow = assertNotNull(app.model.transcribeScenes(confirmed = true))
        runCurrent()
        // Another movie is imported while the request is on its way.
        val p = (store.open("/a") as CoreResult.Ok).value
        store.save("/a", p.copy(recap = p.recap!!.copy(movie = RecapMovie("source/movie.mkv", 99, 1_800_000, 320, 180))))
        gate!!.second.complete(Unit)
        follow.join()
        val job = jobs.loadAll().single()
        assertEquals(AiJobState.FAILED, job.state)
        assertEquals(SceneTranscriber.MOVIE_CHANGED, job.error)
        assertNull(disk("/a").sources.transcript, "the old movie's lines are not saved for the new one")
        app.scope.cancel()
    }

    @Test fun aRestartNeverSendsATranscribedSceneAgain() = runTest {
        create("/a", "A", picked(0L to 360_000L, 400_000L to 760_000L))
        val first = app()
        open("/a", first.model)
        first.model.transcribeScenes()
        gate = 2 to CompletableDeferred()
        first.model.transcribeScenes(confirmed = true)
        runCurrent()
        first.scope.cancel() // the app is closed while the second request is on its way
        runCurrent()
        val id = jobs.loadAll().single().id
        // Worst case: the first request's lines are saved but its checkpoint is lost.
        jobs.update(id) { it.copy(items = listOf("m1-1800000:0-360000", "m1-1800000:400000-760000")) }
        assertEquals(listOf(TranscriptRange(0, 360_000)), transcript("/a")!!.scenes)

        gate = null
        sent.clear()
        val second = app() // the runner resumes the job by itself
        advanceUntilIdle()
        assertEquals(listOf("/a/source/movie.mp4|400000-760000"), sent.map { it.second }, "the first scene is found in the project")
        assertEquals(AiJobState.DONE, jobs.load(id)!!.state)
        assertEquals(listOf(TranscriptRange(0, 360_000), TranscriptRange(400_000, 760_000)), transcript("/a")!!.scenes)
        second.scope.cancel()
    }
}

/** The Script card's Transcribe section (5B-1): Start → confirm card → Start → the transcript summary. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class TranscribeScenesCardTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/index.json"), current)
    private val dir = "/p/R.autoflow"
    private var requests = 0

    private val provider = object : TranscribeProvider {
        override suspend fun transcribe(audio: ByteArray, lang: String, mime: String, lines: List<String>): AiResult<AiTranscript> = error("not used")

        override suspend fun transcribeScenes(audio: ByteArray, mime: String, scenes: List<SceneSpan>, lang: String?): AiResult<List<List<TranscriptSegment>>> {
            requests++
            return AiResult.Ok(scenes.map { listOf(TranscriptSegment(it.startMs, it.startMs + 1_000, "မင်္ဂလာပါ")) }, Usage("gemini-test"))
        }
    }

    private fun open(): RecapModel {
        val p = (store.create(dir, "R", Mode.MOVIE_RECAP) as CoreResult.Ok).value
        val recap = RecapV2(rightsConfirmed = true, movie = RecapMovie("source/movie.mp4", 10, 60_000), scenes = listOf(RecapScene(0, 8_000, picked = true), RecapScene(8_000, 20_000, picked = true)))
        current.set(OpenProject(dir, (store.save(dir, p.copy(recap = recap)) as CoreResult.Ok).value))
        val cutter = SceneAudioCutter { _, chunk, out, _ -> fs.writeDurably(out, chunk.ranges.toString().encodeToByteArray()); null }
        return RecapModel(session, fs, null, null, CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), transcriber = provider, sceneAudio = cutter)
    }

    @Test fun startConfirmThenTranscribe() = androidx.compose.ui.test.v2.runComposeUiTest {
        val m = open()
        setContent {
            val s by m.state.collectAsState()
            Column(Modifier.verticalScroll(rememberScrollState())) { ScriptCard(m, s, s.recap!!) }
        }
        onNodeWithTag("transcribe-start").performScrollTo().performClick()
        onNodeWithTag("transcribe-confirm").assertExists()
        onNodeWithText("Transcribe 2 picked scene(s) without subtitles: 1 Gemini request(s)", substring = true).assertExists()
        assertEquals(0, requests, "nothing is sent from the confirm card")
        onNodeWithTag("transcribe-confirm-start").performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("transcribe-summary").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("🎙 Transcript ✓ 2 lines from 2 scene(s)").assertExists()
        onNodeWithTag("transcribe-start").assertDoesNotExist()
        assertEquals(1, requests)
    }
}
