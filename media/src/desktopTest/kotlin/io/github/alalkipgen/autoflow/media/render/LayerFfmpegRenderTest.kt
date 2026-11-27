package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.Layer
import io.github.alalkipgen.autoflow.core.recap.LayerParams
import io.github.alalkipgen.autoflow.core.recap.LayerType
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Renders layers with a real ffmpeg binary (not filter-string substrings) and measures the pixels.
 *
 * ffmpeg comes from `AUTOFLOW_FFMPEG` or PATH. CI sets `AUTOFLOW_EXPECT_FFMPEG=1` (after tools/ci-ffmpeg.sh), so a
 * missing binary fails there; on a machine without ffmpeg the tests are skipped with a note.
 */
class LayerFfmpegRenderTest {
    private val out = RenderPresets.exportAt(Ratio.R9_16, 720) // 720 × 1280
    private val rect = listOf(0f, 0.5f, 1f, 0.3f)              // y 640, h 384
    private val src = "testsrc2=s=${out.width}x${out.height}:r=${out.fps}:d=1"

    private val ffmpeg: String? by lazy {
        val candidates = listOfNotNull(System.getenv("AUTOFLOW_FFMPEG"), "ffmpeg")
        candidates.firstOrNull { runCatching { run(listOf(it, "-hide_banner", "-version")).first == 0 }.getOrDefault(false) }
    }

    private fun ffmpegOrSkip(): String? {
        ffmpeg?.let { return it }
        if (System.getenv("AUTOFLOW_EXPECT_FFMPEG") == "1") fail("AUTOFLOW_EXPECT_FFMPEG=1 but no ffmpeg on PATH / AUTOFLOW_FFMPEG")
        println("SKIP LayerFfmpegRenderTest: no ffmpeg binary")
        return null
    }

    private fun run(cmd: List<String>): Pair<Int, String> {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to "timeout\n$text" }
        return p.exitValue() to text
    }

    /** Draws one frame of [src] through [layers] (LayerCompiler output exactly as RenderCompiler appends it). */
    private fun renderFrame(bin: String, dir: File, name: String, vararg layers: Layer): BufferedImage {
        val r = LayerCompiler.compile(layers.toList(), out, totalMs = 1_000, resolveImage = { null }, baseInputCount = 1,
            textFilter = { _, _ -> error("This fixture has no TEXT layers") })
        val graph = (listOf("[0:v]null[vseq]") + r.chains).joinToString(";")
        val png = File(dir, "$name.png")
        val (code, log) = run(listOf(bin, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", src,
            "-filter_complex", graph, "-map", "[vlayer]", "-frames:v", "1", "-pix_fmt", "rgb24", png.path))
        assertEquals(0, code, "ffmpeg failed for $name.\nGraph: $graph\n$log")
        return ImageIO.read(png)
    }

    private fun original(bin: String, dir: File): BufferedImage {
        val png = File(dir, "orig.png")
        val (code, log) = run(listOf(bin, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", src,
            "-frames:v", "1", "-pix_fmt", "rgb24", png.path))
        assertEquals(0, code, log)
        return ImageIO.read(png)
    }

    /** Mean absolute RGB difference along row [y], columns [x0] until [x1]. */
    private fun rowDiff(a: BufferedImage, b: BufferedImage, y: Int, x0: Int = 200, x1: Int = 520): Double {
        var sum = 0L
        for (x in x0 until x1) {
            val p = a.getRGB(x, y); val q = b.getRGB(x, y)
            for (s in intArrayOf(16, 8, 0)) sum += abs(((p shr s) and 0xFF) - ((q shr s) and 0xFF))
        }
        return sum.toDouble() / ((x1 - x0) * 3)
    }

    private fun blur(feather: Float) = Layer("l1", LayerType.BLUR, rect = rect, params = LayerParams(sigma = 10f, feather = feather))

    @Test fun featheredBlur_rendersAndFadesFromTheEdges() {
        val bin = ffmpegOrSkip() ?: return
        val dir = Files.createTempDirectory("layer-ffmpeg").toFile()
        try {
            val orig = original(bin, dir)
            val hard = renderFrame(bin, dir, "hard", blur(0f))
            val soft = renderFrame(bin, dir, "soft", blur(0.1f)) // fade = 0.1 × 1280 = 128 px
            val top = 640; val bottom = 640 + 384 - 1; val centre = 640 + 192

            // Sanity: the hard blur really changes the box (testsrc2 has sharp detail there).
            assertTrue(rowDiff(hard, orig, centre) > 8.0, "hard blur should change the box centre")
            // Centre of the feathered box: alpha 255 → identical to the hard blur, clearly not the original.
            assertTrue(rowDiff(soft, hard, centre) < 2.0, "feather centre must equal the full blur: ${rowDiff(soft, hard, centre)}")
            assertTrue(rowDiff(soft, orig, centre) > 8.0, "feather centre must be blurred, not transparent: ${rowDiff(soft, orig, centre)}")
            // First / last rows of the box: alpha ≈ 0 → the original picture shows through.
            for (y in intArrayOf(top, bottom)) {
                assertTrue(rowDiff(soft, orig, y) < 3.0, "feather edge row $y must show the original: ${rowDiff(soft, orig, y)}")
            }
            // Halfway into the fade the blur is partly applied: between the original and the full blur.
            val mid = top + 64
            val dOrig = rowDiff(soft, orig, mid); val dHard = rowDiff(soft, hard, mid)
            assertTrue(dOrig > 1.0 && dHard > 1.0, "row $mid should be a blend (vs orig $dOrig, vs blur $dHard)")
            // Outside the box nothing changes.
            assertTrue(rowDiff(soft, orig, top - 10) < 1.0, "pixels above the box must be untouched")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun featheredBlur_fullRenderCompilerExportRuns() {
        val bin = ffmpegOrSkip() ?: return
        val dir = Files.createTempDirectory("layer-ffmpeg-full").toFile()
        try {
            val clipFile = File(dir, "clip.mp4")
            val (c0, l0) = run(listOf(bin, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", src,
                "-c:v", "libx264", "-pix_fmt", "yuv420p", clipFile.path))
            assertEquals(0, c0, l0)
            val clip = ArtifactId("vd_aaaaaaaaaaaa")
            val mp4 = File(dir, "out.mp4")
            val graph = RenderGraph(clips = listOf(Clip(clip, trimOutMs = 1_000)), output = out, layers = listOf(blur(0.1f)))
            val env = RenderCompiler.Env(
                resolve = { if (it == clip) RenderCompiler.ClipMedia(clipFile.path, hasAudio = false) else null },
                songPath = null, assPath = File(dir, "out.ass").path, fontsDir = dir.path, outputPath = mp4.path,
            )
            val compiled = assertIs<RenderCompiler.Compiled.Ok>(RenderCompiler.compile(graph, env))
            compiled.ass?.let { File(env.assPath).writeText(it) }
            val (code, log) = run(listOf(bin) + compiled.args)
            assertEquals(0, code, "export with a feathered blur failed:\n$log")
            assertTrue(mp4.length() > 0, "no output file")
        } finally {
            dir.deleteRecursively()
        }
    }
}
