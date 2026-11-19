package io.github.alalkipgen.autoflow.ui.export

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.recap.WatermarkWidth
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.WatermarkSize
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.render.*
import io.github.alalkipgen.autoflow.ui.recap.NativeOutputPicture
import io.github.alalkipgen.autoflow.ui.recap.NativePreviewAssets
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WatermarkOutputTest {
    @Test fun realSizePointersWorkInEnglishAndBurmeseAtPhoneWidths() {
        val cases = listOf(360 to 2f) + listOf(412 to 1f, 412 to 2f)
        for ((width, font) in cases) for (language in UiLanguage.entries) {
            runDesktopComposeUiTest(width = width, height = 640) {
                var size by mutableIntStateOf(22)
                setContent { CompositionLocalProvider(LocalUiLanguage provides language, LocalDensity provides Density(1f, font),
                    LocalUiFontFamily provides BurmeseTestFont.family) {
                    androidx.compose.foundation.layout.Column { WatermarkWidth(size, true) { size = it } }
                } }
                onNodeWithTag("watermark-size").assertIsDisplayed()
                onNodeWithText(My.breakSyllables(UiText.LARGE.text(language)), substring = true).performMouseInput { click() }
                runOnIdle { assertEquals(35, size) }
                onNodeWithText(My.breakSyllables(UiText.WATERMARK_CUSTOM.text(language)), substring = true).performMouseInput { click() }
                onNodeWithTag("watermark-width-slider").assertIsDisplayed().performTouchInput { click(androidx.compose.ui.geometry.Offset(right - 2, center.y)) }
                runOnIdle { assertEquals(55, size) }
            }
        }
    }

    @Test fun legacyProjectOpensWithoutAnOnDiskMigration() = ExportHarness().use { h ->
        val open = requireNotNull(h.session.open)
        val manifest = File(open.dir, "project.json")
        val before = manifest.readBytes()
        assertFalse(before.decodeToString().contains("watermarkWidthPercent"))
        val visual = assertIs<CoreResult.Ok<io.github.alalkipgen.autoflow.core.model.Project>>(h.store.open(open.dir)).value.recap!!.visual
        assertEquals(visual.watermarkAnchor.rect(.22f, .14f), visual.watermarkRect())
        assertEquals(55, visual.copy(watermarkWidthPercent = 90).watermarkPercent)
        assertContentEquals(before, manifest.readBytes())
    }

    @Test fun styleExactAndExportMatchAtEachWidthAndRatio() = runBlocking {
        ExportHarness().use { h ->
            val open = requireNotNull(h.session.open)
            val recap = requireNotNull(open.project.recap)
            val logo = BufferedImage(160, 40, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().apply { color = java.awt.Color.RED; fillRect(0, 0, width, height); dispose() }
            }
            ImageIO.write(logo, "png", File(open.dir, "test-watermark.png"))
            val ffmpeg = requireNotNull(h.media.ffmpeg())
            fun ff(vararg args: String) {
                val p = ProcessBuilder(listOf(ffmpeg, "-v", "error", "-y", "-threads", "2") + args).redirectErrorStream(true).start()
                val log = p.inputStream.bufferedReader().readText()
                assertEquals(0, p.waitFor(), log)
            }
            ff("-f", "lavfi", "-i", "color=gray:s=320x180:d=10", "-c:v", "libx264", "-pix_fmt", "yuv420p", File(open.dir, recap.movie!!.file).path)
            val picture = BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().apply { color = java.awt.Color.GRAY; fillRect(0, 0, width, height); dispose() }
            }.toComposeImageBitmap()
            val tool = requireNotNull(h.media.runner)
            val runner = RenderJobRunner(h.fs, FfmpegRunner(tool, ffmpeg), MediaProbe(tool, requireNotNull(h.media.ffprobe())))
            for (ratio in listOf(Ratio.R16_9, Ratio.R9_16, Ratio.R1_1)) {
                val base = recap.copy(layers = emptyList(), looks = emptyList(), takes = emptyList(), chosenTakes = emptyMap(),
                    captions = recap.captions.copy(burn = false), audio = recap.audio.copy(loudness = false, polish = false), visual = recap.visual.copy(outputRatio = ratio, masks = emptyList(), watermarkFile = "test-watermark.png"))
                val widths = listOf(WatermarkSize.SMALL, WatermarkSize.MEDIUM, WatermarkSize.LARGE, WatermarkSize.MAX)
                val positions = widths.map { width ->
                    val r = base.copy(visual = base.visual.copy(watermarkWidthPercent = width.takeUnless { it == WatermarkSize.MEDIUM }))
                    val sized = open.copy(project = open.project.copy(recap = r))
                    val job = assertNotNull(h.frames.job(sized, Quality.EXPORT, null, "exports/test-watermark", "synthetic"))
                    val exact = assertIs<RecapFrames.Result.Ok>(h.frames.frame(300L, quality = Quality.EXPORT, open = sized)).frame
                    val rendered = runner.run(job.copy(graph = job.graph.copy(clips = job.graph.clips.take(1))))
                    val video = assertIs<RenderState.Done>(rendered, "$ratio/$width: $rendered").output
                    val snapshot = File(h.root, "frame.png")
                    ff("-i", video, "-ss", "0.3", "-frames:v", "1", "-threads:v", "1", snapshot.path)
                    val ex = redBounds(ImageIO.read(exact.bytes.inputStream())); val out = redBounds(ImageIO.read(snapshot))
                    assertTrue(abs((out[2] - out[0]) - width / 100f) <= .02, "$ratio/$width width=$out")
                    assertNear(ex, out, "$ratio/$width Exact Frame")
                    width to out
                }.toMap()
                var width by mutableIntStateOf(WatermarkSize.SMALL)
                val assets = NativePreviewAssets(h.fs).load(open.dir, base)
                val out = RenderPresets.preview(ratio)
                runDesktopComposeUiTest(width = 450, height = 450) {
                    setContent { CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family) {
                        NativeOutputPicture(picture, base.copy(visual = base.visual.copy(watermarkWidthPercent = width)), out,
                            300, emptyList(), assets, Modifier.size((400 * ratio.width / maxOf(ratio.width, ratio.height)).dp,
                                (400 * ratio.height / maxOf(ratio.width, ratio.height)).dp))
                    } }
                    for ((w, expected) in positions) {
                        runOnIdle { width = w }
                        val actual = onNodeWithContentDescription("Native output-frame proxy").captureToImage().toAwtImage()
                        assertNear(redBounds(actual), expected, "$ratio/$w Style")
                    }
                }
            }
        }
    }

    private fun assertNear(a: List<Float>, b: List<Float>, title: String) =
        assertTrue(a.zip(b).all { (x, y) -> abs(x - y) <= .02f }, "$title: $a != $b within ±2%")

    private fun redBounds(image: BufferedImage): List<Float> {
        var x0 = image.width; var y0 = image.height; var x1 = -1; var y1 = -1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val rgb = image.getRGB(x, y)
            val red = rgb ushr 16 and 255; val green = rgb ushr 8 and 255; val blue = rgb and 255
            if (red > green + 80 && red > blue + 80) {
                x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y)
            }
        }
        assertTrue(x1 >= x0 && y1 >= y0, "rendered watermark pixels not found")
        return listOf(x0.toFloat() / image.width, y0.toFloat() / image.height, (x1 + 1f) / image.width, (y1 + 1f) / image.height)
    }
}
