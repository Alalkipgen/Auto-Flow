package io.github.alalkipgen.autoflow.ui.export

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.recap.PreflightCheck
import io.github.alalkipgen.autoflow.media.probe.MediaInfo
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.recap.*
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * Group 2 B (PR 5): the Movie Recap Export step in the glass look — no emoji / ✓ / ⚠ left in its labels (the icons carry
 * them), Preflight items without the ⛔ / ⚠ / ℹ text prefixes, the running render's bar shows the same fraction as its
 * text and Cancel still cancels, and the Render preview's Fullscreen (D 25c–d look) opens and exits on the same tag.
 */
@OptIn(ExperimentalTestApi::class)
class StudioExportGlassTest {
    private val symbols = listOf("✓", "⚠", "⛔", "ℹ", "📦", "🎬", "⏳", "→", "←")

    private fun ComposeUiTest.texts(): List<String> = onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.ContentDescription)).flatten() }

    @Test fun glassLabelsHaveNoSymbolsPreflightHasNoPrefixAndRunningShowsTheBarAndCancels() {
        for ((language, font) in listOf(UiLanguage.ENGLISH to 1f, UiLanguage.BURMESE to 2f)) runDesktopComposeUiTest(width = 360, height = 760) {
            ExportHarness().use { h ->
                h.preflightFixture(PreflightCheck.Reason.UNVOICED)
                setContent { CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 360f) {
                        StudioExportScreen(h.model, h.recap, 360f, RecapNavigation({}, {}, {}))
                    }
                } }
                waitForIdle()
                val report = h.model.state.value.recap!!.preflight
                assertTrue(report.isNotEmpty())
                report.forEachIndexed { index, item ->
                    val shown = onNodeWithTag("preflight-message-$index", useUnmergedTree = true).fetchSemanticsNode()
                        .config[SemanticsProperties.Text].joinToString("") { it.text }
                    assertEquals(preflightMessage(item, language), shown.replace("\u200B", ""), "item $index has no text prefix")
                }
                // BACK ("← Back") is the shared back label; it loses its arrow in PR 6 (C §3), not in this PR.
                val found = texts().filter { text -> text != UiText.BACK.text(language) && symbols.any { it in text } }
                assertEquals(emptyList(), found, "${language.code} labels with symbols")
                assertEquals(emptyList(), textFitProblems(360, font))

                onNodeWithTag("export-render").assertIsEnabled().performMouseInput { click() }
                assertEquals(Quality.EXPORT, h.model.state.value.run!!.quality)
                runOnIdle { h.run.value = RenderState.Running(.62, null) }
                onNodeWithTag("export-status").performScrollTo().assertIsDisplayed()
                onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(.62f, 0f..1f)))
                    .assertExists()
                onNodeWithText(UiText.CANCEL.text(language)).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                assertEquals(listOf("render-1"), h.cancelled)
                onNodeWithTag("export-status").assertTextEquals(UiText.RENDER_CANCELLED.text(language, "what" to UiText.EXPORT.text(language)))
            }
        }
    }

    @Test fun renderPreviewFullscreenOpensAndExitsOnTheSameTag() = runDesktopComposeUiTest(width = 360, height = 760) {
        val poster = ByteArrayOutputStream().use { out ->
            ImageIO.write(BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB), "png", out); out.toByteArray()
        }
        val media = RecapFrames.PreviewMedia(MediaInfo(3000, 960, 540, 25.0, "h264", null), poster)
        setContent { Column { PreviewPlayback("preview.mp4", media, null, glass = true) { _, _, _ -> } } }
        onNodeWithTag("export-preview-poster").assertIsDisplayed()
        onNodeWithTag("export-preview-fullscreen").assertTextContains(UiText.PREVIEW_FULLSCREEN.en).performMouseInput { click() }
        waitForIdle()
        onNodeWithTag("export-preview-fullscreen").assertContentDescriptionEquals(UiText.PREVIEW_EXIT_FULLSCREEN.en)
        onNodeWithTag("export-preview-playback").assertExists()
        onNodeWithTag("export-preview-fullscreen").performMouseInput { click() }
        waitForIdle()
        onNodeWithTag("export-preview-fullscreen").assertTextContains(UiText.PREVIEW_FULLSCREEN.en)
    }
}
