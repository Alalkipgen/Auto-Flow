package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.LocalPreviewPlayer
import io.github.alalkipgen.autoflow.ui.components.PreviewPlayer
import io.github.alalkipgen.autoflow.ui.export.ExportHarness
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Dest
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Shell
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.delay
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.*

/**
 * Group 2 PR 2 (A §1 "Mini bar", "Sync with the lists", 0-preview-states 4): on a phone a block thumbnail plays its block in
 * the top card (Pause badge on the one playing; the card is not opened), the mini bar shows only while playing with the
 * card scrolled away, its caret brings the card back and its Pause stops it; a Source scene's badge is its own target that
 * seeks the card without toggling the pick. Desktop: no badges, no mini bar. No project file changes.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class RecapMiniBarTest(val width: Int, val font: Float, val language: UiLanguage) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}")
        fun cases() = listOf(arrayOf<Any>(360, 1f, UiLanguage.ENGLISH), arrayOf<Any>(360, 2f, UiLanguage.BURMESE),
            arrayOf<Any>(1440, 1f, UiLanguage.ENGLISH))
        private val player = PreviewPlayer(atOnce = true) { _, request, modifier, _, onSnapshot ->
            val snapshot by rememberUpdatedState(onSnapshot)
            LaunchedEffect(request.seekRevision, request.playing) {
                var at = request.sourceMs
                while (true) {
                    snapshot(MovieSnapshot(at, 10_000, if (request.playing) MoviePhase.PLAYING else MoviePhase.PAUSED))
                    if (!request.playing) break
                    delay(40); at += 10 // quarter speed: block 2 (1.5 s, the end) keeps playing through the waits
                }
            }
            Box(modifier)
        }
    }
    private val phone get() = width < 840
    private val play get() = UiText.PLAY.text(language)
    private val pause get() = UiText.PAUSE.text(language)

    @Composable private fun Screen(h: ExportHarness, step: RecapStep) =
        CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
            LocalUiFontFamily provides BurmeseTestFont.family, LocalPreviewPlayer provides player) {
            StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat(), editor = true) {
                StudioRecapScreen(h.recap, step, width.toFloat(), {}, RecapNavigation({}, {}, {}))
            }
        }

    private fun ComposeUiTest.scrollAway(tag: String) {
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 4_000f) }; waitForIdle()
    }

    private val miniBar = hasTestTag("recap-mini-bar")

    @Test fun blockThumbnailPlaysItsBlockAndTheMiniBarFollowsTheCard() = runDesktopComposeUiTest(width = width, height = if (phone) 760 else 900) {
        ExportHarness().use { h ->
            val before = h.bytes()
            setContent { Screen(h, RecapStep.STORY) }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("block-select-1").fetchSemanticsNodes().isNotEmpty() }
            if (!phone) {
                listOf("block-preview-0", "block-preview-1", "recap-mini-bar").forEach { onNodeWithTag(it).assertDoesNotExist() }
                return@use
            }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("block-preview-1").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("recap-mini-bar").assertDoesNotExist()
            // The thumbnail plays block 2 in the card; the card is not opened (the list stays).
            onNodeWithTag("block-preview-1").assert(hasContentDescription(play)).performScrollTo().performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("timeline-play") and hasContentDescription(pause)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("block-select-0").assertExists()
            onNodeWithTag("block-preview-1").assert(hasContentDescription(pause))
            onNodeWithTag("block-preview-0").assert(hasContentDescription(play))
            // Scrolled into view by the tap above? Then the card may be away already; bring it back first.
            onNodeWithTag("recap-top-preview").performScrollTo(); waitForIdle()
            onNodeWithTag("recap-mini-bar").assertDoesNotExist()
            // The card chip breaks Burmese syllables (ToneChip); the mini bar line is plain text.
            onNode(hasText(My.breakSyllables(UiText.V3_BLOCK.text(language, "n" to 2))) and hasAnyAncestor(hasTestTag("recap-top-preview"))).assertExists()
            // Scrolled away while playing → the mini bar (Block 2, time, Pause, caret-up).
            scrollAway("story-content")
            waitUntil(timeoutMillis = 5_000) { onAllNodes(miniBar).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("recap-mini-bar").assertIsDisplayed()
            onNode(hasText(UiText.V3_BLOCK.text(language, "n" to 2)) and hasAnyAncestor(miniBar)).assertIsDisplayed()
            assertEquals(emptyList(), textFitProblems(width, font, strict = true))
            // caret-up brings the card back; the bar goes (the card is in view), playback goes on.
            onNode(hasContentDescription(UiText.PROJECT_PAGE_PREVIEW.text(language)) and hasAnyAncestor(miniBar)).performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(miniBar).fetchSemanticsNodes().isEmpty() }
            onNodeWithTag("timeline-scrubber").assertIsDisplayed()
            onNodeWithTag("timeline-play").assert(hasContentDescription(pause))
            // Away again, then the bar's Pause: paused → no bar.
            scrollAway("story-content")
            waitUntil(timeoutMillis = 5_000) { onAllNodes(miniBar).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasContentDescription(pause) and hasAnyAncestor(miniBar)).performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(miniBar).fetchSemanticsNodes().isEmpty() }
            onNodeWithTag("timeline-play").assert(hasContentDescription(play))
            onNodeWithTag("block-preview-1").assert(hasContentDescription(play))
            // The playing block's thumbnail pauses it.
            onNodeWithTag("block-preview-0").performScrollTo().performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("block-preview-0") and hasContentDescription(pause)).fetchSemanticsNodes().isNotEmpty() }
            // Scrolled up to the top edge, the thumbnail now sits under the mini bar (an overlay under the top bar): bring it out first.
            if (onAllNodes(miniBar).fetchSemanticsNodes().isNotEmpty()) {
                val bar = onNode(miniBar).getUnclippedBoundsInRoot(); val thumb = onNodeWithTag("block-preview-0").getUnclippedBoundsInRoot()
                if (thumb.top < bar.bottom) onNodeWithTag("story-content")
                    .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, with(density) { (thumb.top - bar.bottom - 8.dp).toPx() }) }
            }
            onNodeWithTag("block-preview-0").performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("timeline-play") and hasContentDescription(play)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(before, h.bytes(), "previewing must not change any project file")
        }
    }

    @Test fun voiceListThumbnailsPlayTooAndPausedNeverShowsTheBar() = runDesktopComposeUiTest(width = width, height = if (phone) 760 else 900) {
        ExportHarness().use { h ->
            val before = h.bytes()
            setContent { Screen(h, RecapStep.VOICE) }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("block-select-1").fetchSemanticsNodes().isNotEmpty() }
            if (!phone) { onNodeWithTag("block-preview-1").assertDoesNotExist(); return@use }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("block-preview-1").fetchSemanticsNodes().isNotEmpty() }
            scrollAway("voice-content")
            onNodeWithTag("recap-mini-bar").assertDoesNotExist()
            onNodeWithTag("block-preview-1").performScrollTo().performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("block-preview-1") and hasContentDescription(pause)).fetchSemanticsNodes().isNotEmpty() }
            scrollAway("voice-content")
            waitUntil(timeoutMillis = 5_000) { onAllNodes(miniBar).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasContentDescription(pause) and hasAnyAncestor(miniBar)).performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(miniBar).fetchSemanticsNodes().isEmpty() }
            assertEquals(before, h.bytes())
        }
    }

    @Test fun sceneBadgeSeeksTheCardWithoutTogglingThePick() = runDesktopComposeUiTest(width = width, height = if (phone) 760 else 900) {
        ExportHarness().use { h ->
            // Three scenes, only 2–4 s picked: the Source card (PR 3) plays the picks, so only that row has a badge.
            val project = (h.store.open(h.fixture.dir.path) as CoreResult.Ok).value
            val saved = (h.store.save(h.fixture.dir.path, project.copy(recap = project.recap!!.copy(scenes = listOf(
                RecapScene(0, 1_500), RecapScene(2_000, 4_000, picked = true), RecapScene(8_500, 10_000))))) as CoreResult.Ok).value
            h.current.set(OpenProject(h.fixture.dir.path, saved)); h.recap.refresh()
            val before = h.bytes()
            setContent { Screen(h, RecapStep.SOURCE) }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("scene-pick-2").fetchSemanticsNodes().isNotEmpty() }
            if (!phone) { onNodeWithTag("scene-preview-1").assertDoesNotExist(); return@use }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("scene-preview-1").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("scene-preview-0").assertDoesNotExist(); onNodeWithTag("scene-preview-2").assertDoesNotExist()
            onNodeWithTag("scene-pick-1").assertIsOn()
            onNodeWithTag("scene-preview-1").assert(hasContentDescription(play)).performScrollTo().performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("timeline-play") and hasContentDescription(pause)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("scene-pick-1").assertIsOn()
            onNodeWithTag("scene-preview-1").assert(hasContentDescription(pause)).performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("timeline-play") and hasContentDescription(play)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("scene-pick-1").assertIsOn()
            assertEquals(emptyList(), textFitProblems(width, font, strict = true))
            assertEquals(before, h.bytes(), "the badge must not toggle the pick or change any file")
        }
    }
}
