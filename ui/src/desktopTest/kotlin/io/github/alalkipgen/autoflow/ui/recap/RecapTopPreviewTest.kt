package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.LocalPreviewPlayer
import io.github.alalkipgen.autoflow.ui.components.PreviewPlayer
import io.github.alalkipgen.autoflow.ui.export.ExportHarness
import io.github.alalkipgen.autoflow.ui.export.StudioExportScreen
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Dest
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Shell
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.delay
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.*

/**
 * Group 2 PR 1 (A §1, §5 · D 25a–d, §2.2–2.3): on a phone every Recap step starts with the one top preview card and has no
 * ▶ preview page; ⋮ Diagnostics (steps 2–5) opens the readouts in a glass dialog and closes it; Fullscreen opens and exits;
 * collapsing keeps playing. Desktop (≥ 840 dp) keeps its panes, the Diagnostics card and no top card. No project file changes.
 * The decoder is a stand-in with the MoviePlayback contract (the desktop decoder needs VLC and a display).
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class RecapTopPreviewTest(val width: Int, val font: Float, val language: UiLanguage) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}")
        fun cases() = listOf(arrayOf<Any>(360, 1f, UiLanguage.ENGLISH), arrayOf<Any>(360, 2f, UiLanguage.BURMESE),
            arrayOf<Any>(1440, 1f, UiLanguage.ENGLISH))
        private val player = PreviewPlayer(atOnce = true) { _, request, modifier, _, onSnapshot ->
            val snapshot by rememberUpdatedState(onSnapshot)
            LaunchedEffect(request.seekRevision, request.playing) {
                var at = request.sourceMs
                while (true) {
                    snapshot(MovieSnapshot(at, 20_000, if (request.playing) MoviePhase.PLAYING else MoviePhase.PAUSED))
                    if (!request.playing) break
                    delay(40); at += 40
                }
            }
            Box(modifier)
        }
    }
    private val phone get() = width < 840

    @Composable private fun Screen(h: ExportHarness, step: RecapStep) =
        CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
            LocalUiFontFamily provides BurmeseTestFont.family, LocalPreviewPlayer provides player) {
            StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat(), editor = true) {
                val navigation = RecapNavigation({}, {}, {})
                if (step == RecapStep.EXPORT) StudioExportScreen(h.model, h.recap, width.toFloat(), navigation)
                else StudioRecapScreen(h.recap, step, width.toFloat(), {}, navigation)
            }
        }

    /** The card is the first child of the step's innermost scrolling column. */
    private fun ComposeUiTest.cardIsFirst(step: RecapStep) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("recap-top-preview").fetchSemanticsNodes().isNotEmpty() }
        val column = onAllNodes(hasScrollAction() and hasAnyDescendant(hasTestTag("recap-top-preview"))).fetchSemanticsNodes()
            .minBy { it.boundsInRoot.height }
        assertEquals("recap-top-preview", column.children.first().config.getOrNull(SemanticsProperties.TestTag), "$step: card first")
        onAllNodesWithTag("recap-top-preview").assertCountEquals(1)
    }

    @Test fun everyPhoneStepStartsWithTheTopPreviewAndHasNoPreviewPage() = runDesktopComposeUiTest(width = width, height = if (phone) 760 else 900) {
        ExportHarness().use { h ->
            val before = h.bytes()
            var step by mutableStateOf(RecapStep.SOURCE)
            setContent { Screen(h, step) }
            RecapStep.entries.forEach { s ->
                step = s; waitForIdle()
                if (phone) {
                    cardIsFirst(s)
                    onNodeWithTag("timeline-play").assertExists(); onNodeWithTag("timeline-scrubber").assertExists()
                    // Source (PR 3) plays the picked scenes from the movie: no Exact frame of an output there.
                    if (s == RecapStep.SOURCE) onNodeWithTag("timeline-exact").assertDoesNotExist()
                    else onNodeWithTag("timeline-exact").assertExists()
                    onNodeWithTag("timeline-fullscreen").assertExists()
                    // Off the phone card (A §1): Close player, the long sound hint and the Diagnostics card.
                    listOf("timeline-close", "timeline-sound-hint").forEach { onNodeWithTag(it).assertDoesNotExist() }
                    onNode(hasTestTag("timeline-diagnostics")).assertDoesNotExist()
                    assertEquals(emptyList(), textFitProblems(width, font, strict = true), "$s")
                } else onNodeWithTag("recap-top-preview").assertDoesNotExist()
                // No ▶ / ✎ preview page toggle on any Recap step (phone: the card is on the page; desktop: the preview pane).
                onNodeWithTag("project-page-preview").assertDoesNotExist(); onNodeWithTag("project-page-edit").assertDoesNotExist()
                onNodeWithTag("project-overflow").performMouseInput { click() }; waitForIdle()
                onNodeWithTag("classic-recap").assertIsDisplayed()
                // Diagnostics in ⋮ on phone steps 2–5 only (Source has no timeline before a story).
                if (phone && s != RecapStep.SOURCE) onNodeWithTag("recap-diagnostics").assertIsDisplayed().assertIsEnabled()
                else onNodeWithTag("recap-diagnostics").assertDoesNotExist()
                onNodeWithTag("project-overflow").performMouseInput { click() }; waitForIdle()
            }
            assertEquals(before, h.bytes(), "looking must not change any project file")
        }
    }

    @Test fun diagnosticsFullscreenAndCollapse() = runDesktopComposeUiTest(width = width, height = if (phone) 760 else 900) {
        ExportHarness().use { h ->
            val before = h.bytes()
            setContent { Screen(h, RecapStep.STORY) }
            if (!phone) {
                // Desktop keeps its Diagnostics card in the preview pane.
                onNodeWithTag("timeline-diagnostics").assertExists(); onNodeWithTag("recap-top-preview").assertDoesNotExist()
                return@use
            }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("timeline-play").fetchSemanticsNodes().isNotEmpty() }
            // ⋮ → Diagnostics: the readouts in a glass dialog, Close closes it.
            onNodeWithTag("project-overflow").performMouseInput { click() }
            onNodeWithTag("recap-diagnostics").performMouseInput { click() }; waitForIdle()
            onNodeWithTag("timeline-diagnostics").assertIsDisplayed()
            listOf("timeline-position", "timeline-seek-input", "timeline-seek", "timeline-caption").forEach { onNodeWithTag(it).assertExists() }
            onNodeWithTag("recap-diagnostics-close").performScrollTo().performMouseInput { click() }; waitForIdle()
            onNodeWithTag("timeline-diagnostics").assertDoesNotExist(); onNodeWithTag("timeline-position").assertDoesNotExist()
            // Fullscreen opens over the step and exits back to the card.
            onNodeWithTag("timeline-fullscreen").performScrollTo().performMouseInput { click() }; waitForIdle()
            onNodeWithTag("timeline-fullscreen-window").assertExists()
            onNodeWithTag("timeline-fullscreen-exit").assertIsDisplayed()
                .assert(hasContentDescription(UiText.PREVIEW_EXIT_FULLSCREEN.text(language))).performMouseInput { click() }
            waitForIdle(); onNodeWithTag("timeline-fullscreen-window").assertDoesNotExist()
            onNodeWithTag("recap-top-preview").assertExists()
            // Play, then collapse: one row, still playing (the clock keeps moving), then open again.
            onNodeWithTag("timeline-play").performScrollTo().performMouseInput { click() }; waitForIdle()
            onNodeWithTag("timeline-play").assert(hasContentDescription(UiText.PAUSE.text(language)))
            onNodeWithTag("recap-top-preview-collapse").performScrollTo().performMouseInput { click() }; waitForIdle()
            onNodeWithTag("timeline-scrubber").assertDoesNotExist(); onNodeWithTag("timeline-video").assertExists()
            onNodeWithTag("timeline-play").assert(hasContentDescription(UiText.PAUSE.text(language)))
            val at = h.recap.previewSource.currentPosition()!!
            waitUntil(timeoutMillis = 5_000) { h.recap.previewSource.currentPosition()!! > at }
            assertEquals(emptyList(), textFitProblems(width, font, strict = true))
            onNodeWithTag("recap-top-preview-collapse").performScrollTo().performMouseInput { click() }; waitForIdle()
            onNodeWithTag("timeline-scrubber").assertExists()
            assertEquals(before, h.bytes(), "previewing must not change any project file")
        }
    }
}
