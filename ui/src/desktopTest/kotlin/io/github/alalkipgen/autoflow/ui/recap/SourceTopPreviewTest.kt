package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieRequest
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

/** Group 2 PR 3 (A §1 "Source"): the Source card's scene clock — the picked scenes in order, end to end, from the movie. */
class PickedScenesTest {
    private val movie = RecapMovie("source/movie.mp4", 1, 10_000, 320, 180, hasAudio = true)
    private val scenes = listOf(RecapScene(0, 1_500, picked = true), RecapScene(2_000, 4_000), RecapScene(5_000, 6_000, picked = true))

    @Test fun picksInOrderEndToEndAtTheirOwnSpeed() {
        val timeline = PickedScenes.timeline(RecapV2(movie = movie, scenes = scenes))
        assertEquals(listOf(Triple(0L, 1_500L, 0L), Triple(1_500L, 2_500L, 5_000L)),
            timeline.clips.map { Triple(it.outputFromMs, it.outputToMs, it.sourceFromMs) })
        assertEquals(listOf(1f, 1f), timeline.clips.map { it.sourceRate })
        assertEquals(listOf(0, 2), timeline.clips.map { PickedScenes.index(it) })
        assertEquals(2_500, timeline.durationMs)
        assertEquals(emptyList(), timeline.captions)
        // Movie time ↔ card time: only inside a pick.
        assertEquals(2_000, PickedScenes.outputAt(timeline, 5_500))
        assertNull(PickedScenes.outputAt(timeline, 3_000))
        assertEquals(5_500, timeline.clipAt(2_000)!!.sourceAt(2_000))
    }

    @Test fun nothingPickedIsTheWholeMovieAndNoMovieIsNothing() {
        val whole = PickedScenes.timeline(RecapV2(movie = movie, scenes = scenes.map { it.copy(picked = false) }))
        assertEquals(1, whole.clips.size); assertEquals(10_000, whole.durationMs); assertNull(PickedScenes.index(whole.clips.single()))
        assertEquals(10_000, PickedScenes.timeline(RecapV2(movie = movie)).durationMs)
        assertTrue(PickedScenes.timeline(RecapV2(scenes = scenes)).clips.isEmpty())
    }
}

/**
 * Group 2 PR 3 (A §1 "Source", 1-source): on a phone the Source step's top card plays the picked scenes in order from the
 * movie with its own sound — scope "Picked scenes · movie sound", "m:ss / m:ss", chip "#n · movie time", no Exact frame; the
 * scene clock goes on from one pick to the next and never plays the unpicked part; the mini bar names the scene; no movie →
 * no card. Desktop keeps its output timeline pane. Watching never changes a project file.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class SourceTopPreviewTest(val width: Int, val font: Float, val language: UiLanguage) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}")
        fun cases() = listOf(arrayOf<Any>(360, 1f, UiLanguage.ENGLISH), arrayOf<Any>(360, 2f, UiLanguage.BURMESE),
            arrayOf<Any>(1440, 1f, UiLanguage.ENGLISH))
        /** Every request the stand-in decoder was given (the Source card's: unmuted). */
        private val requests = java.util.concurrent.CopyOnWriteArrayList<MovieRequest>()
        private val player = PreviewPlayer(atOnce = true) { _, request, modifier, _, onSnapshot ->
            val snapshot by rememberUpdatedState(onSnapshot)
            SideEffect { requests += request }
            LaunchedEffect(request.seekRevision, request.playing) {
                var at = request.sourceMs
                while (true) {
                    snapshot(MovieSnapshot(at, 10_000, if (request.playing) MoviePhase.PLAYING else MoviePhase.PAUSED))
                    if (!request.playing) break
                    delay(40); at += 20 // half speed: each pick plays long enough to be seen
                }
            }
            Box(modifier)
        }
    }
    private val phone get() = width < 840
    private val scope get() = UiText.V3_SOURCE_PREVIEW_SCOPE.text(language)
    private val card = hasTestTag("recap-top-preview")

    @Composable private fun Screen(h: ExportHarness) =
        CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
            LocalUiFontFamily provides BurmeseTestFont.family, LocalPreviewPlayer provides player) {
            StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat(), editor = true) {
                StudioRecapScreen(h.recap, RecapStep.SOURCE, width.toFloat(), {}, RecapNavigation({}, {}, {}))
            }
        }

    private fun ExportHarness.recap(change: (RecapV2) -> RecapV2) {
        val project = (store.open(fixture.dir.path) as CoreResult.Ok).value
        val saved = (store.save(fixture.dir.path, project.copy(recap = change(project.recap!!))) as CoreResult.Ok).value
        current.set(OpenProject(fixture.dir.path, saved)); recap.refresh()
    }

    private fun ComposeUiTest.chip(text: String) =
        onAllNodes(hasText(text) and (hasTestTag("timeline-chip") or hasAnyAncestor(hasTestTag("timeline-chip")))).fetchSemanticsNodes().isNotEmpty()

    @Test fun phoneCardPlaysThePickedScenesWithTheMovieSound() = runDesktopComposeUiTest(width = width, height = if (phone) 760 else 900) {
        ExportHarness().use { h ->
            h.recap { it.copy(scenes = listOf(RecapScene(0, 1_500, picked = true), RecapScene(2_000, 4_000), RecapScene(5_000, 6_000, picked = true))) }
            val before = h.bytes()
            requests.clear()
            setContent { Screen(h) }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("scene-pick-2").fetchSemanticsNodes().isNotEmpty() }
            if (!phone) {
                // Desktop: no top card; the preview pane keeps the output timeline (Diagnostics card) as before.
                onNodeWithTag("recap-top-preview").assertDoesNotExist(); onNode(hasText(scope)).assertDoesNotExist()
                onNodeWithTag("timeline-diagnostics").assertExists()
                assertEquals(before, h.bytes()); return@use
            }
            waitUntil(timeoutMillis = 10_000) { onAllNodes(card).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasText(My.breakSyllables(scope)) and hasAnyAncestor(card)).assertExists()
            onNode(hasText(My.breakSyllables(UiText.V3_PREVIEW_SCOPE.text(language)))).assertDoesNotExist()
            // m:ss / m:ss of the picks (1.5 s + 1 s), the first pick's chip, no Exact frame; opened at the pick's start, with sound.
            onNodeWithTag("timeline-time").assertTextEquals("0:00 / 0:02")
            assertTrue(chip("#1 · 0:00"))
            onNodeWithTag("timeline-exact").assertDoesNotExist()
            onNodeWithTag("timeline-fullscreen").assertExists(); onNodeWithTag("timeline-back").assertExists()
            assertTrue(requests.isNotEmpty() && requests.none { it.muted }, "the Source card plays the movie's own sound")
            assertEquals(0L, requests.last().sourceMs)
            assertEquals(emptyList(), textFitProblems(width, font, strict = true))
            // Play: pick #1, then straight to pick #3 at 0:05 of the movie (the unpicked 1.5–5 s is never asked for), then the end.
            onNodeWithTag("timeline-play").performScrollTo().performMouseInput { click() }
            waitUntil(timeoutMillis = 10_000) { requests.any { it.sourceMs == 5_000L } }
            waitUntil(timeoutMillis = 5_000) { chip("#3 · 0:05") }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodes(hasTestTag("timeline-play") and hasContentDescription(UiText.PLAY.text(language))).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(requests.none { it.sourceMs in 1_500 until 5_000 }, "${requests.map { it.sourceMs }.distinct()}")
            assertTrue(requests.none { it.muted })
            // The scrubber is on the picks' clock: 1.8 s = 0.3 s into pick #3 (movie 5.3 s).
            onNodeWithTag("timeline-scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(1_800f / 2_499f) }
            waitForIdle()
            waitUntil(timeoutMillis = 5_000) { chip("#3 · 0:05") }
            assertEquals(5_300L, requests.last().sourceMs)
            assertEquals(before, h.bytes(), "watching must not change any project file")
        }
    }

    @Test fun theMiniBarNamesThePlayingScene() = runDesktopComposeUiTest(width = width, height = if (phone) 760 else 900) {
        if (!phone) return@runDesktopComposeUiTest
        ExportHarness().use { h ->
            h.recap { it.copy(scenes = listOf(RecapScene(0, 1_500), RecapScene(2_000, 9_000, picked = true))) }
            val before = h.bytes()
            setContent { Screen(h) }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("scene-preview-1").fetchSemanticsNodes().isNotEmpty() }
            // Only the picked scene has a badge; it plays that scene in the card.
            onNodeWithTag("scene-preview-0").assertDoesNotExist()
            onNodeWithTag("scene-preview-1").performScrollTo().performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("scene-preview-1") and hasContentDescription(UiText.PAUSE.text(language))).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("source-content").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 4_000f) }; waitForIdle()
            val bar = hasTestTag("recap-mini-bar")
            waitUntil(timeoutMillis = 5_000) { onAllNodes(bar).fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("#2 · 0:0", substring = true) and hasAnyAncestor(bar)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(emptyList(), textFitProblems(width, font, strict = true))
            onNode(hasContentDescription(UiText.PAUSE.text(language)) and hasAnyAncestor(bar)).performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(bar).fetchSemanticsNodes().isEmpty() }
            assertEquals(before, h.bytes())
        }
    }

    @Test fun noMovieNoCard() = runDesktopComposeUiTest(width = width, height = if (phone) 760 else 900) {
        ExportHarness().use { h ->
            h.recap { it.copy(movie = null, scenes = emptyList()) }
            setContent { Screen(h) }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("source-import").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            onNodeWithTag("recap-top-preview").assertDoesNotExist(); onNodeWithTag("timeline-play").assertDoesNotExist()
        }
    }

    @Test fun nothingPickedPlaysTheWholeMovie() = runDesktopComposeUiTest(width = width, height = if (phone) 760 else 900) {
        if (!phone) return@runDesktopComposeUiTest
        ExportHarness().use { h ->
            h.recap { it.copy(scenes = listOf(RecapScene(0, 1_500), RecapScene(2_000, 4_000))) }
            setContent { Screen(h) }
            waitUntil(timeoutMillis = 10_000) { onAllNodes(card).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("timeline-time").assertTextEquals("0:00 / 0:10")
            onNodeWithTag("timeline-chip").assertDoesNotExist()
            // Every scene is in the whole movie, so each has its badge.
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("scene-preview-1").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("scene-preview-0").assertExists()
        }
    }
}
