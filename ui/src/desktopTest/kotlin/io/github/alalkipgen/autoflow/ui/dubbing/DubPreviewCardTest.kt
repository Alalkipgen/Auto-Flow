package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.dubbing.DubRules
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.DubTake
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.media.MediaTasks
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlePlayer
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlesFixture
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlesFixture.Line
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Group 4 D + 🆕-1: the Dubbing preview card on Dub script — scope caption, hint behind ⓘ, one tick per line (too long
 * amber, no voice faint) that seeks to its line, the "#n" chip of the line under the playhead (none between lines),
 * −5 s / +5 s on the one playhead, Fullscreen on the same clock with its exit, and the dark box when the video cannot play.
 */
@OptIn(ExperimentalTestApi::class)
class DubPreviewCardTest {
    private val lines = listOf(
        Line(1_000, 3_000, "We have to leave tonight.", "ဒီည ထွက်သွားရမယ်။"),
        Line(10_000, 12_000, "Before the rain starts.", "မိုးမရွာခင် သွားရမယ်။"),
        Line(20_000, 22_000, "Trust me.", "ငါ့ကို ယုံပါ"))

    private class Harness(lines: List<Line>, scope: CoroutineScope) {
        val f = SubtitlesFixture(lines, 60_000, "Dub", scope, dub = true)
        val script = DubScriptModel(f.session, f.fs)
        val export = DubExportModel(f.session, f.fs, MediaTasks(DubExportModelTest.Queue(), scope), scope)
        var failed = false
        val player: SubtitlePlayer = { _, req, m, report ->
            LaunchedEffect(req) { report(MovieSnapshot(positionMs = req.sourceMs, durationMs = 60_000, phase = if (failed) MoviePhase.FAILED else MoviePhase.PAUSED)) }
            Box(m.background(AutoFlowColors.Card))
        }
        /** Line 1 fits, line 2 is too long, line 3 has no voice. */
        fun voices() {
            script.open()
            val doc = script.state.value.doc!!
            val open = f.session.open!!
            val art = ArtifactStore(f.fs, open.dir)
            val lock = VoiceLock("gemini", "tts-a", "Kore", "my")
            val takes = doc.cues.take(2).withIndex().associate { (i, c) ->
                c.id to DubTake((art.put("RIFF-${c.id}".encodeToByteArray(), ArtifactKind.AUDIO, "wav") as CoreResult.Ok).value.id,
                    DubRules.takeKey(c, Lexicon.EMPTY, lock, null), if (i == 1) 30_000 else 1_500)
            }
            check(f.session.update(open.dir, open.project.id) { it.copy(dubbing = (it.dubbing ?: DubbingTool()).copy(lock = lock, takes = takes)) } is CoreResult.Ok)
        }
    }

    private fun ComposeUiTest.time() = onNodeWithTag("dub-playhead").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    @Test fun ticksChipSkipsAndFullscreenShareOnePlayhead() {
        for (language in UiLanguage.entries) for (font in listOf(1f, 2f)) runDesktopComposeUiTest(width = 360, height = 1400) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val h = Harness(lines, scope); h.voices()
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
                        LocalUiFontFamily provides BurmeseTestFont.family) {
                        DubbingScreen(h.script, h.f.model, h.f.session, h.f.editor, onBack = {}, player = h.player, export = h.export)
                    }
                }
                waitForIdle()
                onNodeWithTag("dub-step-SCRIPT").performSemanticsAction(SemanticsActions.OnClick); waitForIdle()
                onNodeWithTag("dub-preview-scope").assertTextEquals(My.breakSyllables(UiText.V3_DUB_PREVIEW_SCOPE.text(language, "n" to 2)))
                onNodeWithTag("dub-preview-hint").assertDoesNotExist()
                onNodeWithTag("dub-preview-info").performSemanticsAction(SemanticsActions.OnClick); waitForIdle()
                onNodeWithTag("dub-preview-hint").assertExists()
                // One tick per line: a ≥ 48 dp high button named "#n · start" that seeks to the line (does not play).
                (1..3).forEach { onNodeWithTag("dub-preview-tick-$it").assertExists() }
                onNodeWithTag("dub-preview-tick-2").assert(hasContentDescription("#2 · 0:10.0")).performSemanticsAction(SemanticsActions.OnClick); waitForIdle()
                assertEquals("0:10.0 / 1:00.0", time())
                onNodeWithTag("dub-preview-line-chip").assertTextEquals("#2")
                onNodeWithTag("dub-play").assert(hasContentDescription(UiText.SUB_PLAY.text(language)))
                // ±5 s on the same playhead; between lines (no voice speaking) there is no chip.
                onNodeWithTag("dub-preview-forward").performSemanticsAction(SemanticsActions.OnClick); waitForIdle()
                assertEquals("0:15.0 / 1:00.0", time())
                onNodeWithTag("dub-preview-line-chip").assertTextEquals("#2") // the too-long voice still speaks
                onNodeWithTag("dub-preview-back").performSemanticsAction(SemanticsActions.OnClick); waitForIdle()
                onNodeWithTag("dub-preview-back").performSemanticsAction(SemanticsActions.OnClick); waitForIdle()
                assertEquals("0:05.0 / 1:00.0", time())
                onNodeWithTag("dub-preview-line-chip").assertDoesNotExist()
                onNodeWithTag("dub-preview-tick-3").performSemanticsAction(SemanticsActions.OnClick); waitForIdle()
                onNodeWithTag("dub-preview-line-chip").assertTextEquals("#3") // no voice: the subtitle time names it
                assertEquals(listOf(DubTickKind.VOICED, DubTickKind.TOO_LONG, DubTickKind.NO_VOICE),
                    h.export.previewTicks(h.f.session.open, h.script.state.value.doc!!).map { it.kind })
                assertEquals(emptyList(), textFitProblems(360, font))
                // Fullscreen: the window opens on the same clock; exit returns to the card.
                onNodeWithTag("dub-preview-fullscreen").performSemanticsAction(SemanticsActions.OnClick); waitForIdle()
                onNodeWithTag("dub-preview-fullscreen-window").assertExists()
                onAllNodesWithTag("dub-playhead").fetchSemanticsNodes().forEach {
                    assertEquals("0:20.0 / 1:00.0", it.config[SemanticsProperties.Text].joinToString { t -> t.text })
                }
                onNodeWithTag("dub-preview-fullscreen-exit").assert(hasContentDescription(UiText.PREVIEW_EXIT_FULLSCREEN.text(language)))
                    .performSemanticsAction(SemanticsActions.OnClick); waitForIdle()
                onNodeWithTag("dub-preview-fullscreen-window").assertDoesNotExist()
                onNodeWithTag("dub-preview").assertExists()
                assertEquals("0:20.0 / 1:00.0", time())
            } finally { scope.cancel() }
        }
    }

    @Test fun aVideoThatCannotPlayShowsTheDarkBoxAndNoControls() = runDesktopComposeUiTest(width = 360, height = 1200) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val h = Harness(lines, scope); h.voices(); h.failed = true
            setContent { DubbingScreen(h.script, h.f.model, h.f.session, h.f.editor, onBack = {}, player = h.player, export = h.export) }
            waitForIdle()
            onNodeWithTag("dub-step-SCRIPT").performSemanticsAction(SemanticsActions.OnClick); waitForIdle()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("dub-preview-unavailable").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("dub-preview-unavailable").assert(hasText(My.breakSyllables(UiText.SUB_PREVIEW_UNAVAILABLE.text(UiLanguage.ENGLISH)), substring = true)
                .or(hasAnyDescendant(hasText(UiText.SUB_PREVIEW_UNAVAILABLE.text(UiLanguage.ENGLISH)))))
            onNodeWithTag("dub-play").assertDoesNotExist()
            onNodeWithTag("dub-preview-fullscreen").assertDoesNotExist()
            onNodeWithTag("dub-preview").assertDoesNotExist()
        } finally { scope.cancel() }
    }

    /** 🆕-2 (Group 4 E): a line card's head is a 48 dp button that seeks the preview to the line's start, without playing. */
    @Test fun aLineHeadSeeksThePreviewWithoutPlaying() {
        for (language in UiLanguage.entries) for (font in listOf(1f, 2f)) runDesktopComposeUiTest(width = 360, height = 1400) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val h = Harness(lines, scope); h.voices()
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
                        LocalUiFontFamily provides BurmeseTestFont.family) {
                        DubbingScreen(h.script, h.f.model, h.f.session, h.f.editor, onBack = {}, player = h.player, export = h.export)
                    }
                }
                waitForIdle()
                onNodeWithTag("dub-step-SCRIPT").performSemanticsAction(SemanticsActions.OnClick); waitForIdle()
                onNodeWithTag("dub-rows").performScrollToKey("c3"); waitForIdle()
                onNodeWithTag("dub-row-head-c3").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
                    .assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
                onNode(hasText("#3") and hasAnyAncestor(hasTestTag("dub-row-head-c3"))).assertExists() // the texts stay readable
                onNodeWithTag("dub-row-head-c3").performSemanticsAction(SemanticsActions.OnClick); waitForIdle()
                onNodeWithTag("dub-rows").performScrollToKey("preview"); waitForIdle()
                assertEquals("0:20.0 / 1:00.0", time())
                onNodeWithTag("dub-play").assert(hasContentDescription(UiText.SUB_PLAY.text(language))) // not playing
                onNodeWithTag("dub-preview-line-chip").assertTextEquals("#3")
                assertEquals(emptyList(), textFitProblems(360, font))
            } finally { scope.cancel() }
        }
    }
}
