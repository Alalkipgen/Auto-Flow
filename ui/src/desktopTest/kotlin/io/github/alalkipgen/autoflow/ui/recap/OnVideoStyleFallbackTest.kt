package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.media.render.RenderPresets
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.export.ExportHarness
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class OnVideoStyleFallbackTest {
    @Test fun actualStyleKeepsManualEditAndExactFallbackHonestWhenNativePlayerIsUnavailable() = runDesktopComposeUiTest(width = 412, height = 760) {
        ExportHarness().use { h ->
            h.settings.update { it.copy(vlcPath = File(h.root, "no-vlc-installed-here").path) }
            val before = h.bytes()
            setContent { CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family) {
                StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 412f) { StudioRecapScreen(h.recap, RecapStep.STYLE, 412f, {}, RecapNavigation({}, {}, {})) }
            } }
            onNodeWithTag("layer-edit-qa-blur").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("VLC path does not contain", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("output-layer-overlay").assertDoesNotExist(); onNodeWithTag("layer-move-handle").assertDoesNotExist()
            onNodeWithTag("layer-percent-0").performScrollTo().assertIsDisplayed().assertIsEnabled()
            // Group 2 A §1: on a phone the Exact frame is the top card's frame button; it waits for Save as before.
            onNodeWithTag("timeline-exact").assert(hasContentDescription(UiText.EXACT_FRAME.en)).performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            val evidence = onVideoEvidence("fallback")
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(evidence, "style-unsaved-exact-disabled.png"))
            onNodeWithText("VLC path does not contain", substring = true).performScrollTo().assertIsDisplayed()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(evidence, "style-vlc-unavailable-unsaved.png"))
            onNodeWithTag("layer-cancel").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("timeline-exact").assert(hasContentDescription(UiText.EXACT_FRAME.en)).performScrollTo().assertIsDisplayed().assertIsEnabled()
            assertEquals(before, h.bytes())
        }
    }
    @Test fun missingImageNeverPutsEditableHandlesOnAnUnavailableOrBlackStandIn() = runDesktopComposeUiTest(width = 412, height = 760) {
        ExportHarness().use { h ->
            h.recap.layerEditor.edit("qa-image")
            val source = BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB).toComposeImageBitmap()
            setContent { NativeLayerPreview(source, h.session.open!!.project.recap!!, RenderPresets.preview(Ratio.R16_9), 1500,
                emptyList(), emptyMap(), h.recap.layerEditor, true, Modifier.size(400.dp, 225.dp)) }
            onNodeWithContentDescription("Native output-frame proxy").assertDoesNotExist()
            onNodeWithTag("output-layer-overlay").assertDoesNotExist(); onNodeWithTag("layer-move-handle").assertDoesNotExist()
            onNodeWithTag("layer-resize-handle").assertDoesNotExist()
        }
    }
}
