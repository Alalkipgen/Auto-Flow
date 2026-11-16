package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.media.render.RenderPresets
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.bitmapFromArgb
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class NativeFramePainterTest {
    private fun solid(w: Int, h: Int, c: Int) = bitmapFromArgb(w, h, IntArray(w * h) { c })

    @Test fun outputFrameOrderTimingIgnoredBorderRectAndImmutableDecodedPixels() = runDesktopComposeUiTest(width = 360, height = 360) {
        val raw = solid(320, 180, 0xFFFF0000.toInt())
        val image = solid(20, 10, 0xFF0000FF.toInt())
        val bg = Layer("bg", LayerType.BACKGROUND, rect = emptyList(), params = LayerParams(mode = BackgroundMode.COLOUR, colour = "#00FF00"))
        val img = Layer("image", LayerType.IMAGE, listOf(.1f, .1f, .4f, .05f), 100, 500, LayerParams(image = "image"))
        val border = Layer("edge", LayerType.BORDER, rect = emptyList(), fromMs = 100, toMs = 500, params = LayerParams(px = 36, colour = "#FFFF00"))
        var time by mutableLongStateOf(50)
        var layers by mutableStateOf(listOf(img, bg, border))
        val recap = RecapV2(movie = RecapMovie("movie.mp4", 0, 1000, 320, 180), layers = layers)
        setContent { CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family) {
            NativeOutputPicture(raw, recap.copy(layers = layers), RenderPresets.preview(Ratio.R1_1), time, emptyList(), mapOf("image" to image), Modifier.size(320.dp))
        } }
        fun pixel(x: Int, y: Int) = onRoot().captureToImage().toPixelMap()[x, y]
        assertTrue(pixel(60, 40).green > .9f, "background is behind the fitted picture, not a list-position overlay")
        runOnIdle { time = 200 }
        assertTrue(pixel(60, 40).blue > .9f, "native image uses OUTPUT rect, not the movie picture")
        assertTrue(pixel(60, 80).blue > .9f, "image retains aspect, does not stretch into rect height")
        assertTrue(pixel(60, 110).red > .9f, "image height is aspect-preserving")
        assertTrue(pixel(3, 3).red > .9f && pixel(3, 3).green > .9f, "BORDER ignores rect")
        runOnIdle { layers = listOf(border, bg, img.copy(rect = listOf(0f, 0f, .4f, .4f))) }
        assertTrue(pixel(3, 3).blue > .9f, "IMAGE after BORDER wins, BACKGROUND remains behind both")
        runOnIdle { time = 501 }
        assertTrue(pixel(3, 3).green > .9f, "activation ends after toMs")
        assertEquals(Color.Red, raw.toPixelMap()[0, 0], "decoder buffer was never mutated")
    }

    @Test fun blurReadsEarlierNativeLayersAndTextNotAlwaysLast() = runDesktopComposeUiTest(width = 320, height = 180) {
        val checker = bitmapFromArgb(40, 40, IntArray(1600) { if ((it % 40 / 2 + it / 40 / 2) % 2 == 0) -1 else 0xFF000000.toInt() })
        val raw = solid(320, 180, 0xFFFF0000.toInt())
        val image = Layer("checker", LayerType.IMAGE, listOf(.25f, .2f, .5f, .5f), params = LayerParams(image = "checker"))
        val text = Layer("text", LayerType.TEXT, listOf(.25f, .2f, .5f, .5f), params = LayerParams(textMy = "TEXT"))
        val blur = Layer("blur", LayerType.BLUR, listOf(.3f, .25f, .4f, .6f), params = LayerParams(sigma = 20f, feather = .02f))
        var layers by mutableStateOf(listOf(text, image, blur))
        setContent { CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family) {
            NativeOutputPicture(raw, RecapV2(layers = layers), RenderPresets.preview(Ratio.R16_9), 0, emptyList(), mapOf("checker" to checker), Modifier.size(320.dp, 180.dp))
        } }
        val blurred = onRoot().captureToImage().toPixelMap()
        val center = blurred[160, 75]
        assertTrue(center.red in .35f.. .65f && center.green in .35f.. .65f, "blur uses earlier IMAGE, not raw red video")
        runOnIdle { layers = listOf(image, blur, text) }
        val topText = onRoot().captureToImage().toPixelMap()
        val difference = (65..100).sumOf { y -> (115..205).count { x -> kotlin.math.abs(topText[x, y].red - blurred[x, y].red) > .25f } }
        assertTrue(difference > 30, "native TEXT stays at its list position")
    }

    @Test fun inactiveMissingImageStillRefusesNativeOutput() = runDesktopComposeUiTest {
        setContent { NativeOutputPicture(solid(4, 4, -1), RecapV2(layers = listOf(Layer("bad", LayerType.IMAGE, fromMs = 1000, params = LayerParams(image = "gone")))), RenderPresets.preview(Ratio.R1_1), 0, emptyList(), emptyMap()) }
        onNodeWithText("Image unavailable: gone", substring = true).assertExists()
        onNodeWithContentDescription("Native output-frame proxy").assertDoesNotExist()
    }
}
