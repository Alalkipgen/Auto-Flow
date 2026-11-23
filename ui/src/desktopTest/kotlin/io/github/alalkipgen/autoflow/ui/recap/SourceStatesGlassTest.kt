package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.LocalPreviewPlayer
import io.github.alalkipgen.autoflow.ui.components.PreviewPlayer
import io.github.alalkipgen.autoflow.ui.components.SectionCard
import io.github.alalkipgen.autoflow.ui.export.ExportHarness
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlin.test.*

/**
 * The Source step's states sheet (Group 2 D 19 · 1–12), each under its [label]: the cards drawn from [base] with the state of
 * that moment (busy strip, message, empty Movie card, Split / Scan result, Subtitles, Transcribe confirm / running / paused /
 * failed, a movie without sound). Shared by [SourceStatesGlassTest] and the screenshots.
 */
internal object SourceStates {
    val TRACKS = listOf(SubtitleTrack(2, "subrip", "eng", "English"), SubtitleTrack(3, "subrip", "mya", "Burmese"),
        SubtitleTrack(4, "hdmv_pgs_subtitle", "eng", "SDH"))
    fun job(base: RecapUiState, state: AiJobState, left: Int, error: String? = null) = AiJob("transcribe-1", AiJobKind.TRANSCRIBE,
        base.projectDir.orEmpty(), items = (0 until left).map { "${3 - left + it}" }, state = state, error = error, initialItemCount = 3)

    @Composable
    fun Sheet(model: RecapModel, base: RecapUiState, label: @Composable (String) -> Unit) {
        // A movie with sound for 1–11 (the QA fixture's movie has none); 12 = the same movie without sound.
        val recap = base.recap!!.let { it.copy(movie = it.movie!!.copy(hasAudio = true)) }
        val movie = recap.movie!!
        val transcribe = TranscribeUi(available = true, todoScenes = 3, requests = 3)
        val done = transcribe.copy(todoScenes = 0, lines = 61, transcribedScenes = 6)
        val noSubtitles = recap.copy(sources = recap.sources.copy(subtitles = null))
        label("1 · Import · copying the movie into the project")
        SourceBusy(base.copy(job = UiText.MOVIE_COPYING.en, jobNotice = UiNotice(UiText.MOVIE_COPYING), progress = null, message = null))
        SourceMovieCard(base, recap.copy(movie = null, scenes = emptyList()), idle = false) {}
        label("2 · Movie ready (message from the import)")
        val ready = UiNotice(UiText.MOVIE_READY, "duration" to RecapModel.clock(movie.durationMs), "tracks" to UiNotice(UiText.MOVIE_TRACK_HINT, "n" to 3))
        SourceBusy(base.copy(job = null, message = ready.text(UiLanguage.ENGLISH), notice = ready, messageOk = true))
        SourceMovieCard(base, recap, idle = true) {}
        label("3 · Finding scenes · 42 % (Find scenes disabled while busy)")
        SourceBusy(base.copy(job = UiText.SCENES_FINDING.en, jobNotice = UiNotice(UiText.SCENES_FINDING), progress = .42, message = null))
        SourceSplitCards(model, recap.copy(scenes = emptyList()), idle = false, threshold = recap.sceneThreshold) {}
        label("4 · No scenes yet (first time: Find scenes)")
        SourceSplitCards(model, recap.copy(scenes = emptyList()), idle = true, threshold = recap.sceneThreshold) {}
        SourcePicks(model, base, recap.copy(scenes = emptyList()))
        label("5 · Subtitles · none · tracks not read yet")
        SectionCard(tr(UiText.V3_SUBTITLES), tile = RecapTiles.Subtitles) {
            SourceSubtitles(model, base.copy(movieSubtitleTracksRead = false, movieSubtitleTracks = emptyList()), noSubtitles, glass = true)
        }
        label("6 · Subtitles · tracks in the movie (text + image)")
        SectionCard(tr(UiText.V3_SUBTITLES), tile = RecapTiles.Subtitles) {
            SourceSubtitles(model, base.copy(movieSubtitleTracksRead = true, movieSubtitleTracks = TRACKS), noSubtitles, glass = true)
        }
        label("7 · Subtitles · none in the movie")
        SectionCard(tr(UiText.V3_SUBTITLES), tile = RecapTiles.Subtitles) {
            SourceSubtitles(model, base.copy(movieSubtitleTracksRead = true, movieSubtitleTracks = emptyList()), noSubtitles, glass = true)
        }
        label("8 · Transcribe · confirm (amber) · Start / Back")
        SourceTranscribeCard(model, base.copy(transcribe = transcribe.copy(confirm = TranscribeConfirm(3, 3, 67_000, 14))), recap)
        label("9 · Transcribe · running → card pinned under the top bar")
        val running = job(base, AiJobState.RUNNING, 2)
        RecapTranscribeRun(model, running, glass = true)
        SourceTranscribeCard(model, base.copy(transcribe = done.copy(job = running)), recap)
        label("10 · Transcribe · paused")
        SourceTranscribeCard(model, base.copy(transcribe = done.copy(job = job(base, AiJobState.PAUSED, 2))), recap)
        label("11 · Transcribe · failed")
        SourceTranscribeCard(model, base.copy(transcribe = transcribe.copy(job = job(base, AiJobState.FAILED, 3,
            "Gemini: 503 The model is overloaded. Please try again later."))), recap)
        label("12 · Movie without sound")
        val silent = recap.copy(movie = movie.copy(hasAudio = false))
        SourceMovieCard(base, silent, idle = true) {}
        SourceTranscribeCard(model, base.copy(transcribe = transcribe), silent)
    }
}

/**
 * Group 2 D 18–20 (PR 10): the Source step's states in the glass look — the busy strip without "⏳ " and with the job's bar,
 * the message callout, the Movie card's empty box, the Subtitles track chips and info rows, the Transcribe confirm / paused /
 * failed callouts and buttons, the Transcribe job as the glass job card (tile, small Cancel), "Previous · Transcript …", the
 * no-sound rows, and Replace movie as a glass dialog; every tag kept, every text fits a 360 dp phone, looking writes nothing.
 * D1: the Source card's scene chip shows the scene's start time.
 */
@OptIn(ExperimentalTestApi::class)
class SourceStatesGlassTest {
    /** Decoder stand-in: reports the requested time, paused. */
    private val paused = PreviewPlayer(atOnce = true) { _, request, modifier, _, onSnapshot ->
        val snapshot by rememberUpdatedState(onSnapshot)
        LaunchedEffect(request.seekRevision, request.playing) { snapshot(MovieSnapshot(request.sourceMs, 60_000, MoviePhase.PAUSED)) }
        Box(modifier)
    }

    @Test fun transcriptPreviousLostItsGlyphs() {
        assertEquals("Previous", UiText.TRANSCRIPT_PREVIOUS.text(UiLanguage.ENGLISH))
        assertEquals("ယခင်", UiText.TRANSCRIPT_PREVIOUS.text(UiLanguage.BURMESE))
        for (language in UiLanguage.entries) assertFalse("✓" in UiText.TRANSCRIPT_PREVIOUS.text(language) || "(" in UiText.TRANSCRIPT_PREVIOUS.text(language))
    }

    @Test fun sourceStatesShowTheGlassLookKeepTheirTagsAndWriteNothing() {
        for ((language, font) in listOf(UiLanguage.ENGLISH to 1f, UiLanguage.BURMESE to 2f)) runDesktopComposeUiTest(width = 360, height = 760) {
            ExportHarness().use { h ->
                val before = h.bytes()
                h.recap.refresh()
                val base = h.recap.state.value
                setContent { CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp).testTag("states"),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SourceStates.Sheet(h.recap, base) { AfText(it, color = AutoFlowColors.PrimaryText, type = AfType.Caption) }
                    }
                } }
                waitForIdle()
                fun text(key: UiText, vararg args: Pair<String, Any?>) = key.text(language, *args)
                // 1–3: one strip per busy job — no "⏳ ", the chip says Running; the bar only with a reported progress (42 %).
                assertTrue(onAllNodes(hasText("⏳", substring = true)).fetchSemanticsNodes().isEmpty())
                onAllNodesWithText(text(UiText.MOVIE_COPYING)).assertCountEquals(1)
                onAllNodesWithText(text(UiText.SCENES_FINDING) + " 42 %").assertCountEquals(1)
                assertEquals(1, onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(.42f, 0f..1f)))
                    .fetchSemanticsNodes().size)
                onAllNodesWithTag("status-RUNNING", useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue(it.size >= 3, "${it.size}") }
                // 18: the empty Movie card = the Duotone box with the hint; 2: the message.
                onNode(hasText(My.breakSyllables(text(UiText.RECAP_MOVIE_IMPORT_HINT)))).assertExists()
                onNode(hasText(UiNotice(UiText.MOVIE_READY, "duration" to RecapModel.clock(base.recap!!.movie!!.durationMs),
                    "tracks" to UiNotice(UiText.MOVIE_TRACK_HINT, "n" to 3)).text(language))).assertExists()
                // 3–4: no scenes = Muted info rows (the Split card of 3 and 4, the Scenes card of 4).
                onAllNodesWithText(My.breakSyllables(text(UiText.V3_NO_SCENES))).assertCountEquals(3)
                // 5–7: Read tracks; the tracks as glass filter chips with their state; the hints as rows.
                onNodeWithTag("source-read-tracks").assertExists()
                for (index in listOf(2, 3, 4)) onNodeWithTag("source-track-$index").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "not selected"))
                onNode(hasText(My.breakSyllables(text(UiText.IMAGE_TRACK_HINT)))).assertExists()
                onNode(hasText(My.breakSyllables(text(UiText.NO_EMBEDDED_SUBS)))).assertExists()
                // 8: the confirm callout, Start / Back with their icons.
                onNodeWithTag("transcribe-confirm").assertExists()
                for (tag in listOf("transcribe-confirm-start", "transcribe-confirm-back"))
                    onAllNodes(hasTestTag("button-icon") and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertCountEquals(1)
                // 9: the job as the glass job card (Transcribe tile, small Cancel with its x) and "Previous · Transcript …".
                onAllNodes(hasTestTag("card-tile") and hasAnyAncestor(hasTestTag("transcribe-progress")), useUnmergedTree = true).assertCountEquals(1)
                onAllNodes(hasTestTag("button-icon") and hasAnyAncestor(hasTestTag("transcribe-cancel") and hasAnyAncestor(hasTestTag("transcribe-progress"))),
                    useUnmergedTree = true).assertCountEquals(1)
                onAllNodesWithTag("transcribe-summary").fetchSemanticsNodes().let { assertEquals(2, it.size) } // 9 and 10
                onNode(hasTestTag("transcribe-summary") and hasText(text(UiText.TRANSCRIPT_PREVIOUS) + " · " + transcriptSummary(61, 6, language))).assertExists()
                // 10–11: paused / failed callouts, Resume / Retry and Cancel job.
                onAllNodesWithTag("transcribe-failed").assertCountEquals(2)
                onNode(hasTestTag("transcribe-retry") and hasText(text(UiText.V3_RESUME_REMAINING, "n" to 2))).assertExists()
                onNode(hasTestTag("transcribe-retry") and hasText(text(UiText.RETRY))).assertExists()
                onAllNodesWithText(text(UiText.CANCEL_JOB)).assertCountEquals(2)
                // 12: no sound — the movie's amber row and the Transcribe card's Muted row.
                onNode(hasText(text(UiText.NO_SOUND_SUFFIX).trim(' ', '·'))).assertExists()
                onNode(hasText(text(UiText.MOVIE_NO_SOUND_TO_TRANSCRIBE))).assertExists()
                assertEquals(emptyList(), textFitProblems(360, font), "${language.code} $font")
                assertEquals(before, h.bytes(), "looking must not write the project")
            }
        }
    }

    @Test fun replaceMovieIsAGlassDialogWithItsTags() {
        for ((language, font) in listOf(UiLanguage.ENGLISH to 1f, UiLanguage.BURMESE to 2f)) runDesktopComposeUiTest(width = 360, height = 760) {
            ExportHarness().use { h ->
                val before = h.bytes()
                setContent { CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 360f) {
                        StudioRecapScreen(h.recap, RecapStep.SOURCE, 360f, {}, RecapNavigation({}, {}, {}))
                    }
                } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("source-replace").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("source-replace").performScrollTo().performMouseInput { click() }; waitForIdle()
                onNodeWithTag("glass-dialog-tile", useUnmergedTree = true).assertExists()
                onNodeWithTag("source-replace-warning").assertIsDisplayed()
                // The Group 1 dialog buttons: one row (Cancel first) at font 1.0, stacked (Replace movie on top) at font 2.0.
                val confirm = onNodeWithTag("source-replace-confirm").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val cancel = onNodeWithTag("source-replace-cancel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                if (font < 2f) {
                    onNodeWithTag("glass-dialog-buttons-row").assertExists()
                    assertTrue(cancel.right <= confirm.left, "$confirm / $cancel")
                } else {
                    onNodeWithTag("glass-dialog-buttons-stacked").assertExists()
                    assertTrue(confirm.bottom <= cancel.top, "$confirm / $cancel")
                }
                assertEquals(emptyList(), textFitProblems(360, font), "${language.code} $font")
                onNodeWithTag("source-replace-cancel").performMouseInput { click() }; waitForIdle()
                onNodeWithTag("source-replace-confirm").assertDoesNotExist()
                assertEquals(before, h.bytes())
            }
        }
    }

    /** D1: paused 8.5 s into pick #2 (movie 0:20), the chip names the scene and its start (0:12), as its row does. */
    @Test fun sourceChipShowsTheSceneStart() = runDesktopComposeUiTest(width = 360, height = 760) {
        ExportHarness().use { h ->
            val project = (h.store.open(h.fixture.dir.path) as CoreResult.Ok).value
            val saved = (h.store.save(h.fixture.dir.path, project.copy(recap = project.recap!!.copy(scenes = listOf(
                RecapScene(0, 1_500, picked = true), RecapScene(12_000, 30_000, picked = true))))) as CoreResult.Ok).value
            h.current.set(OpenProject(h.fixture.dir.path, saved)); h.recap.refresh()
            val before = h.bytes()
            setContent { CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(1f, 1f),
                LocalUiFontFamily provides BurmeseTestFont.family, LocalPreviewPlayer provides paused) {
                StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 360f, editor = true) {
                    StudioRecapScreen(h.recap, RecapStep.SOURCE, 360f, {}, RecapNavigation({}, {}, {}))
                }
            } }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("timeline-scrubber").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("timeline-scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(10_000f / 19_499f) }
            waitForIdle()
            fun chip(text: String) = onAllNodes(hasText(text) and (hasTestTag("timeline-chip") or hasAnyAncestor(hasTestTag("timeline-chip"))))
                .fetchSemanticsNodes().isNotEmpty()
            waitUntil(timeoutMillis = 5_000) { chip("#2 · 0:12") }
            assertFalse(chip("#2 · 0:20"), "the chip shows the scene start, not the playing time")
            assertEquals(before, h.bytes())
        }
    }
}
