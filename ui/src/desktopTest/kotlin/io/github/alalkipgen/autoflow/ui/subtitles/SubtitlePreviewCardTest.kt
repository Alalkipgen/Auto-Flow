package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlesFixture.Line
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.AfSize
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlin.math.abs
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Group 5 PR 2 (G, 🆕-1, 🆕-2; samples 01 / 11 / 11b / 11c): the editor's preview card — the time chip on the video, the
 * cue strip as the seek bar (tap / drag / SetProgress seek, never retime; the knob over the track), −5 s / +5 s, the "#n"
 * chip, Fullscreen and the video that cannot play.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SubtitlePreviewCardTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    /** Three lines on a 12-second video: #1 1.0–3.0 s, #2 4.0–6.0 s, #3 8.0–9.5 s. */
    private fun fixture() = SubtitlesFixture(listOf(Line(1_000, 3_000, "First line", "ပထမ"), Line(4_000, 6_000, "Second line", "ဒုတိယ"),
        Line(8_000, 9_500, "Third line", "တတိယ")), 12_000)

    private val failing: SubtitlePlayer = { _, _, m, report ->
        LaunchedEffect(Unit) { report(MovieSnapshot(phase = MoviePhase.FAILED, problem = "no decoder")) }
        Box(m)
    }

    private fun ComposeUiTest.show(f: SubtitlesFixture, font: Float = 1f, language: UiLanguage = UiLanguage.ENGLISH, player: SubtitlePlayer = f.paused) =
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
                LocalUiFontFamily provides BurmeseTestFont.family) { SubtitlesScreen(f.model, f.session, f.editor, player = player, onBack = {}) }
        }

    private fun ComposeUiTest.ready() {
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-cues").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("subtitles-cues").performScrollToIndex(0)
    }

    private fun ComposeUiTest.now() = onNodeWithTag("subtitles-playhead")

    @Test fun theStripIsTheSeekBarAndNeverRetimesALine() = runDesktopComposeUiTest(width = 360, height = 900) {
        val f = fixture()
        show(f)
        ready()
        val before = f.editor.state.value.draft!!.doc.cues
        val strip = onNodeWithTag("subtitles-strip").assertHeightIsEqualTo(AfSize.Touch)
        onNodeWithTag("subtitles-strip-track").assertHeightIsEqualTo(32.dp)
        val seek = onNodeWithTag("subtitles-scrubber").assertContentDescriptionEquals(UiText.SUB_PLAYHEAD.en)
        seek.performMouseInput { click(center) }
        now().assertTextEquals("0:06.0")
        seek.performMouseInput { click(Offset(width * .75f, height / 2f)) }
        now().assertTextEquals("0:09.0")
        seek.performTouchInput { swipe(Offset(width * .75f, height / 2f), Offset(width / 12f, height / 2f), 300) }
        now().assertTextEquals("0:01.0")
        seek.performSemanticsAction(SemanticsActions.SetProgress) { it(.5f) }
        now().assertTextEquals("0:06.0")
        assertEquals(before, f.editor.state.value.draft!!.doc.cues, "seeking never changes a line's times")
        assertFalse(f.editor.state.value.draft!!.dirty)
        // The knob is centred on the playhead and sits on the track's top edge, above the blocks.
        val track = onNodeWithTag("subtitles-strip-track").fetchSemanticsNode().boundsInRoot
        val knob = onNodeWithTag("subtitles-strip-knob").assertExists().fetchSemanticsNode().boundsInRoot
        assertTrue(abs(knob.center.x - (track.left + track.width / 2)) <= 1.5f, "knob at 0:06.0 of 0:12.0: $knob in $track")
        assertTrue(knob.center.y < track.top + 2f && knob.bottom < track.center.y, "knob above the blocks: $knob, $track")
        assertTrue(strip.fetchSemanticsNode().boundsInRoot.width >= track.width)
        onNodeWithTag("subtitles-strip-now").assertDoesNotExist() // retired: the same value as subtitles-playhead
    }

    @Test fun theTimeChipAndControlsFollowThePlayhead() = runDesktopComposeUiTest(width = 360, height = 900) {
        val f = fixture()
        show(f)
        ready()
        // The time chip "now / length" is on the video; the length is subtitles-strip-length.
        onNode(hasTestTag("subtitles-playhead") and hasAnyAncestor(hasTestTag("subtitles-preview"))).assertTextEquals("0:00.0")
        onNode(hasTestTag("subtitles-strip-length") and hasAnyAncestor(hasTestTag("subtitles-preview"))).assertTextEquals("0:12.0")
        onNodeWithTag("subtitles-preview-big-play").assertExists() // paused
        onNodeWithTag("subtitles-preview-line-chip").assertDoesNotExist() // no line at 0:00
        onNodeWithTag("subtitles-preview-forward").assertContentDescriptionEquals(UiText.FORWARD_FIVE_SECONDS.en).performMouseInput { click() }
        now().assertTextEquals("0:05.0")
        onNodeWithTag("subtitles-preview-line-chip").assertTextEquals("#2")
        onNodeWithTag("subtitles-preview-forward").performMouseInput { click() }
        onNodeWithTag("subtitles-preview-forward").performMouseInput { click() }
        now().assertTextEquals("0:12.0") // never past the end
        onNodeWithTag("subtitles-preview-line-chip").assertDoesNotExist()
        onNodeWithTag("subtitles-preview-back").assertContentDescriptionEquals(UiText.BACK_FIVE_SECONDS.en).performMouseInput { click() }
        now().assertTextEquals("0:07.0")
        onNodeWithTag("subtitles-play").assertContentDescriptionEquals(UiText.SUB_PLAY.en).performMouseInput { click() }
        onNodeWithTag("subtitles-play").assertContentDescriptionEquals(UiText.SUB_PAUSE.en)
        onNodeWithTag("subtitles-preview-big-play").assertDoesNotExist() // playing
        onNodeWithTag("subtitles-play").performMouseInput { click() }
        // Fullscreen: a dark window on the same playhead, its own strip; exit (or Back) comes back to the card.
        onNodeWithTag("subtitles-preview-fullscreen").assertContentDescriptionEquals(UiText.PREVIEW_FULLSCREEN.en).performMouseInput { click() }
        onNodeWithTag("subtitles-preview-fullscreen-window").assertExists()
        onNodeWithTag("subtitles-fullscreen-scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(1f / 12) }
        onNodeWithTag("subtitles-preview-fullscreen-exit").assertContentDescriptionEquals(UiText.PREVIEW_EXIT_FULLSCREEN.en).performMouseInput { click() }
        onNodeWithTag("subtitles-preview-fullscreen-window").assertDoesNotExist()
        now().assertTextEquals("0:01.0")
        onNodeWithTag("subtitles-preview-line-chip").assertTextEquals("#1")
    }

    @Test fun aVideoThatCannotPlayStillLetsTheStripSetTheTime() {
        for (language in UiLanguage.entries) for (font in listOf(1f, 2f)) runDesktopComposeUiTest(width = 360, height = 900) {
            val f = fixture()
            show(f, font, language, failing)
            ready()
            onNodeWithTag("subtitles-preview-unavailable").assertIsDisplayed()
            onNodeWithTag("subtitles-preview").assertDoesNotExist()
            for (tag in listOf("subtitles-play", "subtitles-preview-back", "subtitles-preview-forward")) onNodeWithTag(tag).assertIsNotEnabled()
            onNodeWithTag("subtitles-preview-fullscreen").assertDoesNotExist()
            onNodeWithTag("subtitles-scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(.45f) }
            now().assertTextEquals("0:05.4") // in the controls row now
            onNodeWithTag("subtitles-preview-line-chip").assertTextEquals("#2")
            assertEquals(emptyList(), textFitProblems(360, font, strict = true), "unavailable · $font × $language")
        }
    }

    @Test fun onTheDesktopTheTimeSitsInTheControlsRow() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val f = fixture()
        show(f)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-cues").fetchSemanticsNodes().isNotEmpty() }
        onNode(hasTestTag("subtitles-playhead") and hasAnyAncestor(hasTestTag("subtitles-preview"))).assertDoesNotExist()
        now().assertTextEquals("0:00.0")
        val play = onNodeWithTag("subtitles-preview-forward").fetchSemanticsNode().boundsInRoot
        val time = now().fetchSemanticsNode().boundsInRoot
        assertTrue(time.left > play.right && abs(time.center.y - play.center.y) < 12f, "the time after +5 s: $time, $play")
    }
}
