package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.SceneSpan
import io.github.alalkipgen.autoflow.ai.TranscribeProvider
import io.github.alalkipgen.autoflow.ai.Transcript as AiTranscript
import io.github.alalkipgen.autoflow.ai.TranscriptSegment
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.core.recap.RpmPacer
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.MediaChunks
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.jobs.DesktopAiJobRunner
import io.github.alalkipgen.autoflow.ui.jobs.RecapJobProcessor
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.recap.SceneAudioCutter
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.*

/** S-5b: Transcribe in the Subtitles tool — confirm card (nothing sent), the job with progress, Pause / Resume / Cancel, Done. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SubtitleTranscribeTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    private inner class Fixture(hasAudio: Boolean = true, withDoc: Boolean = false) {
        val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
        val index = ProjectIndex(fs, "/app/projects.json")
        val dir = "/p/Film.autoflow"
        val session = ProjectSession(fs, store, index, current)
        val settings = SettingsStore(fs, "/app/s.json")
        val usage = UsageCounter(fs, "/app/usage.json")
        val sent = mutableListOf<Pair<String, String?>>()
        var gate: Pair<Int, CompletableDeferred<Unit>>? = null
        val gemini = object : TranscribeProvider {
            override suspend fun transcribe(audio: ByteArray, lang: String, mime: String, lines: List<String>): AiResult<AiTranscript> = error("not used")
            override suspend fun transcribeScenes(audio: ByteArray, mime: String, scenes: List<SceneSpan>, lang: String?): AiResult<List<List<TranscriptSegment>>> {
                sent += audio.decodeToString() to lang
                val n = sent.size
                gate?.takeIf { it.first == n }?.second?.await()
                return AiResult.Ok(listOf(listOf(TranscriptSegment(1_000, 2_500, "line $n"))), Usage("gemini-test"))
            }
        }
        val cutter = SceneAudioCutter { _, chunk, out, _ -> fs.writeDurably(out, chunk.ranges.joinToString { "${it.inMs}-${it.outMs}" }.encodeToByteArray()); null }
        var old: ArtifactId? = null
        init {
            val created = (store.create(dir, "Film", Mode.SUBTITLES) as CoreResult.Ok).value
            if (withDoc) {
                val doc = SubtitleDoc(source = SubtitleDoc.SOURCE_SRT, nextId = 2, cues = listOf(SubtitleCue("c1", 1_000, 2_000, "Old line")))
                old = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(), ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
            }
            val project = created.copy(subtitles = SubtitlesTool(video = SourceVideo("source/movie.mp4", 9, 1_500_000, 1920, 800, 25.0, hasAudio), current = old))
            current.set(OpenProject(dir, (store.save(dir, project) as CoreResult.Ok).value))
        }
        val runScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transcriber = VideoTranscriber(session, fs, null, gemini, cutter = cutter, pauses = { _, _ -> listOf(MediaChunks.Pause(590_000, 591_000)) })
        val jobs = AiJobStore(fs, "/app/ai-jobs")
        val runner = DesktopAiJobRunner(jobs, runScope, RecapJobProcessor({ null }, { transcriber }), pacer = { RpmPacer(6_000) })
        val model = SubtitlesModel(session, fs, DashboardModel(fs, store, index, settings, FakePicker(), current), null, MainScope(), probe = { null })
        val editor = SubtitleEditorModel(session, fs, MainScope())
        val transcribe = SubtitleTranscribeModel(session, transcriber, runner, usage, ToolsConfig(), MainScope())
        fun tool() = (store.open(dir) as CoreResult.Ok).value.subtitles!!
    }

    private val noDecoder: SubtitlePlayer = { _, _, m, report ->
        LaunchedEffect(Unit) { report(MovieSnapshot(phase = MoviePhase.FAILED, problem = "no decoder")) }
        Box(m)
    }

    private fun ComposeUiTest.show(f: Fixture, language: UiLanguage = UiLanguage.ENGLISH, font: Float = 1f) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
            SubtitlesScreen(f.model, f.session, f.editor, player = noDecoder, transcribe = f.transcribe)
        }
    }

    private fun ComposeUiTest.tap(tag: String) = onNodeWithTag(tag).performScrollTo().performMouseInput { click() }
    private fun ComposeUiTest.waitTag(tag: String, ms: Long = 10_000) = waitUntil(timeoutMillis = ms) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private val selected = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "selected")

    @Test fun confirmFirstThenTheJobMakesTheVersionTheEditorOpens() = runDesktopComposeUiTest(width = 412, height = 900) {
        val f = Fixture()
        runBlocking { repeat(3) { f.usage.record("gemini-test") } }
        show(f)
        tap("subtitles-transcribe")
        waitTag("subtitles-transcribe-confirm")
        onNodeWithTag("subtitles-transcribe-facts").assertTextEquals("25 min of sound · 3 Gemini requests · only the sound is sent")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Today: 3 requests.").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("subtitles-transcribe-lang-auto").assert(selected)
        onNodeWithTag("subtitles-transcribe-again").assertDoesNotExist()
        onNodeWithTag("subtitles-transcribe").assertIsNotEnabled()
        tap("subtitles-transcribe-lang-my")
        onNodeWithTag("subtitles-transcribe-lang-my").assert(selected)
        assertTrue(f.sent.isEmpty() && f.jobs.loadAll().isEmpty(), "nothing is sent or queued before Start")
        tap("subtitles-transcribe-back")
        onNodeWithTag("subtitles-transcribe-confirm").assertDoesNotExist()
        assertTrue(f.sent.isEmpty())

        tap("subtitles-transcribe")
        waitTag("subtitles-transcribe-start")
        tap("subtitles-transcribe-start")
        waitTag("subtitles-doc-facts")
        onNodeWithTag("subtitles-doc-facts").assertTextContains("3 lines", substring = true)
        waitTag("subtitles-transcribe-notice")
        onNodeWithTag("subtitles-transcribe-notice").assertTextContains("Done: 3 lines", substring = true)
        assertEquals(3, f.sent.size)
        assertEquals(AiJobState.DONE, f.jobs.loadAll().single().state)
        f.runScope.cancel()
    }

    @Test fun pauseKeepsTheSentChunkAndResumeSendsOnlyTheRest() = runDesktopComposeUiTest(width = 412, height = 900) {
        val f = Fixture()
        f.gate = 1 to CompletableDeferred()
        show(f)
        tap("subtitles-transcribe")
        waitTag("subtitles-transcribe-start")
        tap("subtitles-transcribe-start")
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Transcribing: chunk 1 of 3").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("subtitles-transcribe").assertIsNotEnabled()
        tap("subtitles-transcribe-pause")
        onNodeWithTag("subtitles-transcribe-pause").assertIsNotEnabled()
        f.gate!!.second.complete(Unit)
        waitTag("subtitles-transcribe-resume")
        onNodeWithTag("subtitles-transcribe-progress").assertTextEquals("Paused: 1 of 3 chunks saved.")
        onNodeWithTag("subtitles-transcribe-resume").assertTextEquals("Resume 2 remaining")
        assertEquals(1, f.sent.size)
        assertNull(f.tool().current, "nothing changes before every chunk is in")
        tap("subtitles-transcribe-resume")
        waitTag("subtitles-doc-facts")
        assertEquals(3, f.sent.size, "the saved chunk was not sent again")
        f.runScope.cancel()
    }

    @Test fun cancelKeepsTheSubtitlesAndAgainSaysItMakesANewVersion() = runDesktopComposeUiTest(width = 412, height = 900) {
        val f = Fixture(withDoc = true)
        f.gate = 1 to CompletableDeferred()
        show(f)
        waitTag("subtitles-change-source")
        tap("subtitles-change-source")
        tap("subtitles-transcribe")
        waitTag("subtitles-transcribe-again")
        tap("subtitles-transcribe-start")
        waitTag("subtitles-transcribe-cancel")
        tap("subtitles-transcribe-cancel")
        waitTag("subtitles-transcribe-notice")
        onNodeWithTag("subtitles-transcribe-notice").assertTextEquals("Transcribe cancelled. Your subtitles did not change.")
        f.gate!!.second.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { f.jobs.loadAll().single().state == AiJobState.CANCELLED }
        assertEquals(f.old, f.tool().current)
        f.runScope.cancel()
    }

    @Test fun noSoundSaysWhy() = runDesktopComposeUiTest(width = 360, height = 900) {
        val f = Fixture(hasAudio = false)
        show(f, UiLanguage.BURMESE)
        waitTag("subtitles-transcribe-no-sound")
        onNodeWithTag("subtitles-transcribe").assertIsNotEnabled()
        val g = Fixture()
        runBlocking { g.transcribe.ask()?.join() }
        assertEquals(SubtitleTranscribeConfirm(1_502_000, 3, 0, "auto", again = false), g.transcribe.state.value.confirm)
        assertTrue(g.sent.isEmpty())
        f.runScope.cancel(); g.runScope.cancel()
    }

    @Test fun confirmAndJobCardsFit() {
        for (width in listOf(360, 1440)) for (font in listOf(1f, 2f)) for (language in UiLanguage.entries) {
            runDesktopComposeUiTest(width = width, height = 900) {
                val f = Fixture(withDoc = true)
                f.gate = 1 to CompletableDeferred()
                show(f, language, font)
                waitTag("subtitles-change-source")
                tap("subtitles-change-source")
                // P9-12d: the source screen's cards and buttons carry icons, not glyphs.
                assertCardIcon("subtitles-video", UiText.SUBTITLES_VIDEO.text(language))
                assertCardIcon("subtitles-source", UiText.SUB_START_HOW.text(language))
                assertIconButton("subtitles-import-srt", UiText.IMPORT_SUBTITLES, language)
                assertIconButton("subtitles-transcribe", UiText.SUB_TRANSCRIBE, language)
                assertEquals(emptyList(), textFitProblems(width, font, strict = true), "$width × $font × $language source")
                tap("subtitles-transcribe")
                waitTag("subtitles-transcribe-again")
                assertCardIcon("subtitles-transcribe-confirm", My.breakSyllables(UiText.SUB_TRANSCRIBE.text(language)))
                assertIconButton("subtitles-transcribe-start", UiText.START, language)
                assertEquals(emptyList(), textFitProblems(width, font, strict = true), "$width × $font × $language confirm")
                tap("subtitles-transcribe-start")
                waitTag("subtitles-transcribe-pause")
                assertEquals(emptyList(), textFitProblems(width, font, strict = true), "$width × $font × $language running")
                f.gate!!.second.complete(Unit)
                f.runScope.cancel()
            }
        }
    }
}
