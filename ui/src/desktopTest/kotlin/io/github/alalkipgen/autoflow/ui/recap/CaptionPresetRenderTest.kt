package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.media.render.*
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.bitmapFromArgb
import io.github.alalkipgen.autoflow.ui.export.ExportHarness
import io.github.alalkipgen.autoflow.ui.export.RecapFrames
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class CaptionPresetRenderTest {
    private fun variants() = CaptionLookPreset.entries.associate { it.name to it.style } +
        ("XL" to captionExtraLarge(CaptionLookPreset.CLEAN.style))
    private fun ImageBitmap.pixels() = IntArray(width * height).also { readPixels(it) }
    private fun evidence() = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "caption-presets/render").apply { mkdirs() }

    @Test fun everySupportedLookAndXlKeepsActualCaptionPixelsIndependentOfUiLanguageAndScale() {
        val rendered = mutableMapOf<String, IntArray>()
        for ((name, style) in variants()) for (bundled in listOf(true, false))
            runDesktopComposeUiTest(width = 320, height = 180) {
                val source = bitmapFromArgb(320, 180, IntArray(320 * 180) { 0xFF0000FF.toInt() })
                val cue = SrtCue(0, 2000, "QA မြန်မာစာတန်း")
                var language by mutableStateOf(UiLanguage.ENGLISH)
                var scale by mutableFloatStateOf(1f)
                var burn by mutableStateOf(true)
                setContent { CompositionLocalProvider(LocalUiLanguage provides language, LocalDensity provides Density(1f, scale),
                    LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                    key(language, scale) {
                        NativeOutputPicture(source, RecapV2(captionStyle = style, captions = RecapCaptions(burn = burn)),
                            RenderPresets.preview(Ratio.R16_9), 1000, listOf(cue), emptyMap(),
                            Modifier.size(320.dp, 180.dp).testTag("caption-preset-render"))
                    }
                } }
                fun capture() = onNodeWithTag("caption-preset-render").captureToImage()
                val baseline = capture(); val pixels = baseline.pixels()
                ImageIO.write(baseline.toAwtImage(), "png", File(evidence(), "native-$name-$bundled.png"))
                for (nextLanguage in UiLanguage.entries) for (nextScale in listOf(1f, 1.3f, 2f)) {
                    runOnIdle { language = nextLanguage; scale = nextScale }
                    val image = capture()
                    assertEquals(baseline.width to baseline.height, image.width to image.height)
                    assertContentEquals(pixels, image.pixels(), "$name/$bundled/${nextLanguage.code}/$nextScale")
                }
                runOnIdle { burn = false }
                val bare = capture().pixels()
                assertTrue(pixels.indices.count { pixels[it] != bare[it] } > 40, "must contain real caption ink: $name/$bundled")
                if (bundled) rendered[name] = pixels
            }
        for ((a, pixels) in rendered) for ((b, other) in rendered) if (a != b)
            assertFalse(pixels.contentEquals(other), "native looks really differ: $a/$b")
    }

    @Test fun existingExportCompilerProducesDistinctRealFfmpegFramesForPresetsAndXl() {
        val rendered = mutableMapOf<String, IntArray>()
        for ((name, style) in variants()) ExportHarness().use { h ->
            val before = h.session.open!!.project.recap!!
            val artifacts = h.bytes().filterKeys { it.replace('\\', '/').startsWith("artifacts/") }
            h.recap.setCaptionStyle { style }
            val open = h.session.open!!
            assertEquals(before.copy(captionStyle = style), open.project.recap)
            // Existing QA fixture FIRST is [200,1200); use its interior, not the blank at1500.
            val at = 700L
            assertTrue(h.recap.previewSource.resolve(h.recap.state.value)!!.timeline.captions.any { at >= it.startMs && at < it.endMs })
            val exact = assertIs<RecapFrames.Result.Ok>(runBlocking {
                h.frames.frame(at, image = ".cache/frames/caption-$name.png", open = open)
            }).frame
            val noCaption = open.copy(project = open.project.copy(recap = open.project.recap!!.copy(
                captions = open.project.recap!!.captions.copy(burn = false))))
            val bare = assertIs<RecapFrames.Result.Ok>(runBlocking {
                h.frames.frame(at, image = ".cache/frames/caption-$name-bare.png", open = noCaption)
            }).frame
            val a = ImageIO.read(exact.bytes.inputStream()); val b = ImageIO.read(bare.bytes.inputStream())
            assertEquals(a.width to a.height, b.width to b.height)
            var changed = 0
            for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) changed++
            assertTrue(changed > 40, "existing real ffmpeg must burn $name; changed=$changed")
            File(evidence(), "exact-$name.png").writeBytes(exact.bytes)
            File(evidence(), "exact-$name-bare.png").writeBytes(bare.bytes)
            File(evidence(), "exact-$name.txt").writeText("existing RecapFrames/RenderCompiler real ffmpeg, output=$at, changed=$changed, style=$style\n")
            rendered[name] = a.getRGB(0, 0, a.width, a.height, null, 0, a.width)
            assertEquals(artifacts, h.bytes().filterKeys { it.replace('\\', '/').startsWith("artifacts/") })
        }
        for ((a, pixels) in rendered) for ((b, other) in rendered) if (a != b)
            assertFalse(pixels.contentEquals(other), "real exported looks really differ: $a/$b")
    }
}
