package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.Layer
import io.github.alalkipgen.autoflow.core.recap.LayerParams
import io.github.alalkipgen.autoflow.core.recap.LayerType
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.*

/** Real production compile -> sidecars -> ffmpeg -> decoded pixels, not a filter-string approximation. */
class TextOrderFfmpegTest {
    @Test fun landscapeTextRespectsDrawingOrderTimingRectAndCaptionsLast() = verify(Ratio.R16_9, 320, 180)
    @Test fun portraitTextRespectsDrawingOrderTimingRectAndCaptionsLast() = verify(Ratio.R9_16, 180, 320)
    @Test fun squareTextRespectsDrawingOrderTimingRectAndCaptionsLast() = verify(Ratio.R1_1, 240, 240)

    private fun whites(frame: BufferedImage, x0: Int, y0: Int, x1: Int, y1: Int): List<Pair<Int, Int>> = buildList {
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = Color(frame.getRGB(x, y))
            if (c.red > 205 && c.green > 205 && c.blue > 205) add(x to y)
        }
    }

    private fun verify(ratio: Ratio, w: Int, h: Int) {
        val (ffmpeg, ffprobe) = RealFfmpeg.orSkip("TextOrderFfmpegTest") ?: return
        val font = RealFfmpeg.anyFont() ?: fail("TEXT ordering gate requires a real font")
        val dir = RealFfmpeg.tempDir("text-order")
        try {
            File(dir, "source").mkdirs(); File(dir, "fonts").mkdirs()
            font.copyTo(File(dir, "fonts/${font.name}"))
            val family = FontInfo.parse(font.readBytes())!!.family
            RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "color=c=black:s=${w}x$h:r=12:d=2",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "source/movie.mp4"), dir)
            val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().let { it.color = Color.RED; it.fillRect(0, 0, 64, 64); it.dispose() }
            ImageIO.write(image, "png", File(dir, "red.png"))
            val text = Layer("text", LayerType.TEXT, rect = listOf(.1f, .12f, .8f, .26f), fromMs = 400, toMs = 1100,
                params = LayerParams(textMy = "TOP", style = family))
            val cover = Layer("cover", LayerType.IMAGE, params = LayerParams(image = "red", opacity = 1f))
            val tools = ProcessTools()
            val traced = ToolRunner { cmd, cwd, timeout, keep, onLine ->
                println("ORDER_COMMAND cwd=$cwd ${cmd.joinToString(" ")}")
                tools.run(cmd, cwd, timeout, keep, onLine)
            }
            val runner = RenderJobRunner(LocalFs(), FfmpegRunner(traced, ffmpeg), MediaProbe(traced, ffprobe))
            val graph = RenderGraph(
                clips = listOf(Clip(null, trimInMs = 0, trimOutMs = 1500, source = "source/movie.mp4")),
                output = Output(ratio, w, h, 12, Quality.EXPORT, "order"),
            )
            fun render(name: String, layers: List<Layer>, captions: List<Overlay> = emptyList()): File {
                val job = RenderJob(title = name, graph = graph.copy(layers = layers, overlays = captions), workDir = dir.path,
                    files = mapOf("red" to "red.png"), fontsDir = "fonts", outputName = "exports/$name")
                val result = runBlocking { runner.run(job) }
                assertIs<RenderState.Done>(result, result.toString())
                val mp4 = File(dir, "exports/$name.mp4")
                val ms = RealFfmpeg.durationMs(ffprobe, mp4)
                assertTrue(abs(ms - 1500) <= 100, "$ratio duration=$ms")
                println("ORDER_RENDER $ratio $name ${w}x$h durationMs=$ms streams=${RealFfmpeg.streams(ffprobe, mp4)}")
                return mp4
            }
            val belowFile = render("text-then-image", listOf(text, cover))
            val aboveFile = render("image-then-text", listOf(cover, text))
            val below = RealFfmpeg.frame(ffmpeg, belowFile, 700, dir)
            val above = RealFfmpeg.frame(ffmpeg, aboveFile, 700, dir)
            val x0 = (w * .1).toInt(); val x1 = (w * .9).toInt(); val y0 = (h * .12).toInt(); val y1 = (h * .38).toInt()
            val hidden = whites(below, x0, y0, x1, y1)
            val visible = whites(above, x0, y0, x1, y1)
            val delta = RealFfmpeg.diff(below, above, x0, y0, x1, y1)
            println("ORDER_PIXELS $ratio TEXT->IMAGE white=${hidden.size}; IMAGE->TEXT white=${visible.size}; meanRgbDelta=$delta")
            val evidence = System.getenv("AUTOFLOW_RENDER_EVIDENCE")?.let { File(it, ratio.name).apply { mkdirs() } }
            if (evidence != null) {
                ImageIO.write(below, "png", File(evidence, "text-then-image.png"))
                ImageIO.write(above, "png", File(evidence, "image-then-text.png"))
            }
            // Collect the rendered measurements before asserting, so a failure is a usable reproduction.
            assertTrue(hidden.size < 5, "$ratio: opaque later IMAGE must hide TEXT (${hidden.size} white pixels)")
            assertTrue(visible.size > 50 && delta > 2, "$ratio: reversed order must expose TEXT (${visible.size}, delta=$delta)")
            val outside = whites(above, 0, 0, w, h).count { (x, y) -> x !in x0 until x1 || y !in y0 until y1 }
            assertEquals(0, outside, "TEXT must keep its rect")
            assertTrue(abs(visible.map { it.first }.average() - w * .5) < w * .08, "TEXT x placement")
            assertTrue(abs(visible.map { it.second }.average() - h * .25) < h * .05, "TEXT y placement")
            for (at in listOf(150L, 1300L)) {
                val inactive = RealFfmpeg.frame(ffmpeg, aboveFile, at, dir)
                assertEquals(0, whites(inactive, 0, 0, w, h).size, "TEXT not active at $at ms")
            }
            val caption = Overlay("CAPTION", 400, 1100, family, maxOf(14, h / 12), centerX = .5f, centerY = .25f)
            val captionFile = render("captions-last", listOf(text, cover), listOf(caption))
            val captionFrame = RealFfmpeg.frame(ffmpeg, captionFile, 700, dir)
            val captionPixels = whites(captionFrame, x0, y0, x1, y1).size
            println("ORDER_CAPTIONS_LAST $ratio white=$captionPixels")
            assertTrue(captionPixels > 50, "real captions must be drawn after the opaque IMAGE")
            if (evidence != null) ImageIO.write(captionFrame, "png", File(evidence, "captions-last.png"))
            // Exact frame also prepares every layer sidecar; its local time window must preserve layer timing.
            val stillJob = RenderJob(title = "exact", graph = graph.copy(layers = listOf(cover, text)), workDir = dir.path,
                files = mapOf("red" to "red.png"), fontsDir = "fonts", outputName = "exports/exact")
            assertIs<RenderState.Done>(runBlocking { runner.frame(stillJob, 700, ".cache/exact.png") })
            assertTrue(whites(ImageIO.read(File(dir, ".cache/exact.png")), x0, y0, x1, y1).size > 50)
        } finally { dir.deleteRecursively() }
    }
}
