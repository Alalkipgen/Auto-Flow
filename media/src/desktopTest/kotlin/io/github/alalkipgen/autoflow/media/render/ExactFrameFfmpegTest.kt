package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.FrameBackground
import io.github.alalkipgen.autoflow.core.recap.MaskPreset
import io.github.alalkipgen.autoflow.core.recap.RecapMask
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

/**
 * "Exact frame" with a real ffmpeg (D-42, 5A-8): the still that [RenderJobRunner.frame] makes from one clip must be
 * the frame the full export shows at that time — same blur box, background, captions — and not a neighbour's.
 * ffmpeg as in [LayerFfmpegRenderTest] (`AUTOFLOW_FFMPEG` / PATH; CI sets `AUTOFLOW_EXPECT_FFMPEG=1`).
 */
class ExactFrameFfmpegTest {
    private val ffmpeg: String? by lazy {
        listOfNotNull(System.getenv("AUTOFLOW_FFMPEG"), "ffmpeg")
            .firstOrNull { runCatching { exec(listOf(it, "-hide_banner", "-version")).first == 0 }.getOrDefault(false) }
    }

    private fun ffmpegOrSkip(): String? {
        ffmpeg?.let { return it }
        if (System.getenv("AUTOFLOW_EXPECT_FFMPEG") == "1") fail("AUTOFLOW_EXPECT_FFMPEG=1 but no ffmpeg on PATH / AUTOFLOW_FFMPEG")
        println("SKIP ExactFrameFfmpegTest: no ffmpeg binary")
        return null
    }

    private fun exec(cmd: List<String>, dir: File? = null): Pair<Int, String> {
        val p = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(180, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to "timeout\n$text" }
        return p.exitValue() to text
    }

    /** The real process runner the app binds, in a few lines. */
    private val tools = ToolRunner { command, workDir, _, _, onLine ->
        val t0 = System.currentTimeMillis()
        val p = runCatching { ProcessBuilder(command).directory(File(workDir)).redirectErrorStream(true).start() }
            .getOrElse { return@ToolRunner ToolExec.CannotStart(it.message ?: "cannot start") }
        val lines = p.inputStream.bufferedReader().readLines()
        lines.forEach(onLine)
        p.waitFor(180, TimeUnit.SECONDS)
        ToolExec.Done(p.exitValue(), lines.joinToString("\n"), lines.takeLast(30), System.currentTimeMillis() - t0, false)
    }

    private val fs = object : FileSystem {
        override fun exists(path: String) = File(path).exists()
        override fun createDirectories(path: String) { File(path).mkdirs() }
        override fun list(path: String): List<String> = File(path).list()?.toList().orEmpty()
        override fun read(path: String) = File(path).readBytes()
        override fun writeDurably(path: String, bytes: ByteArray) { File(path).parentFile?.mkdirs(); File(path).writeBytes(bytes) }
        override fun atomicMove(from: String, to: String) {
            Files.move(File(from).toPath(), File(to).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** Any TrueType font on the machine, so the burned-in caption is part of the comparison. */
    private fun anyFont(): File? = listOf("/usr/share/fonts", "/usr/local/share/fonts", "C:/Windows/Fonts", "/System/Library/Fonts")
        .map(::File).filter { it.isDirectory }
        .flatMap { d -> d.walkTopDown().filter { it.isFile && it.name.endsWith(".ttf", true) }.toList() }
        .sortedBy { f -> listOf("DejaVuSans.ttf", "LiberationSans-Regular.ttf", "arial.ttf").indexOf(f.name).let { if (it < 0) 99 else it } }
        .firstOrNull { f -> FontInfo.parse(f.readBytes()) != null }

    /** Mean absolute RGB difference over rows [y0] until [y1] (whole width). */
    private fun diff(a: BufferedImage, b: BufferedImage, y0: Int = 0, y1: Int = a.height): Double {
        var sum = 0L
        for (y in y0 until y1) for (x in 0 until a.width) {
            val p = a.getRGB(x, y); val q = b.getRGB(x, y)
            for (s in intArrayOf(16, 8, 0)) sum += abs(((p shr s) and 0xFF) - ((q shr s) and 0xFF))
        }
        return sum.toDouble() / ((y1 - y0) * a.width * 3)
    }

    @Test
    fun exactFrameMatchesTheExportAtThatTime() = runBlocking {
        val bin = ffmpegOrSkip() ?: return@runBlocking
        val dir = Files.createTempDirectory("exact-frame").toFile()
        try {
            File(dir, "source").mkdirs()
            val (c0, l0) = exec(listOf(bin, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "testsrc2=s=640x360:r=30:d=12",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "30", File(dir, "source/movie.mp4").path))
            assertEquals(0, c0, l0)
            val font = anyFont()
            val family = font?.let { f ->
                File(dir, "fonts").mkdirs()
                f.copyTo(File(dir, "fonts/${f.name}"))
                FontInfo.parse(f.readBytes())!!.family
            }
            if (family == null) println("NOTE ExactFrameFfmpegTest: no .ttf found, comparing without a caption")
            val out = RenderPresets.exportAt(Ratio.R9_16, 540) // 540 × 960, the 16:9 movie on a blurred background
            val graph = RenderGraph(
                clips = listOf(
                    Clip(null, trimInMs = 0, trimOutMs = 3_000, source = "source/movie.mp4"),
                    Clip(null, trimInMs = 5_000, trimOutMs = 9_000, source = "source/movie.mp4"),
                ),
                overlays = family?.let { listOf(Overlay("EXACT 4s", 3_500, 5_000, it, 64, centerY = 0.8f, strokeWidth = 3)) }.orEmpty(),
                output = out,
                voice = VoiceTrack(emptyList(), clipsVolume = 0.5),
                masks = listOf(RecapMask(MaskPreset.BOTTOM, MaskPreset.BOTTOM.rect)),
                background = FrameBackground.BLUR,
            )
            val job = RenderJob(title = "t", graph = graph, workDir = dir.path, files = emptyMap(), fontsDir = "fonts", outputName = "exports/export")
            val runner = RenderJobRunner(fs, FfmpegRunner(tools, bin), MediaProbe(tools, "ffprobe-not-needed"))

            val still = runner.frame(job, 4_000, ".cache/frames/4000.png")
            assertIs<RenderState.Done>(still, "exact frame failed: $still")
            val exact = ImageIO.read(File(dir, ".cache/frames/4000.png"))
            assertEquals(out.width to out.height, exact.width to exact.height, "the output size, not the movie's")

            // The full export (probe-free: no clip sound is needed for this comparison) and its frame at 4.000 s.
            val exportArgs = assertIs<RenderCompiler.Compiled.Ok>(RenderCompiler.compile(graph.copy(voice = VoiceTrack(emptyList(), clipsVolume = 0.0)),
                RenderCompiler.Env(resolve = { null }, songPath = null, assPath = File(dir, "export.ass").path, fontsDir = File(dir, "fonts").path,
                    outputPath = File(dir, "export.mp4").path, assScale = { s -> font?.let { FontInfo.parse(it.readBytes()) }?.takeIf { it.family == s }?.assScale },
                    resolveSource = { RenderCompiler.ClipMedia(File(dir, it).path, hasAudio = false) })))
            exportArgs.ass?.let { File(dir, "export.ass").writeText(it) }
            val (c1, l1) = exec(listOf(bin) + exportArgs.args, dir)
            assertEquals(0, c1, l1.takeLast(3000))
            fun exportFrame(t: String): BufferedImage {
                val png = File(dir, "ref-$t.png")
                val (c, l) = exec(listOf(bin, "-hide_banner", "-y", "-nostdin", "-i", File(dir, "export.mp4").path, "-ss", t, "-frames:v", "1", png.path))
                assertEquals(0, c, l)
                return ImageIO.read(png)
            }
            val same = exportFrame("4.000")
            val before = exportFrame("3.700") // same clip, 0.3 s earlier: no caption yet, the picture has moved
            val other = exportFrame("1.000")  // the first clip

            val dSame = diff(exact, same)
            val dBefore = diff(exact, before)
            val dOther = diff(exact, other)
            println("ExactFrameFfmpegTest: mean diff vs export@4.0=$dSame, @3.7=$dBefore, @1.0=$dOther")
            assertTrue(dSame < 2.5, "exact frame must look like the export at 4.0 s: $dSame")
            assertTrue(dBefore > dSame * 2 && dOther > dSame * 2, "and clearly unlike other times ($dBefore, $dOther vs $dSame)")
            if (family != null) {
                // The caption band (centre line 0.8 × 960 = 768): the burned-in text is there and in the same place.
                val band = diff(exact, same, 728, 808)
                val withoutCaption = diff(exact, before, 728, 808)
                println("ExactFrameFfmpegTest: caption band diff vs @4.0=$band, vs @3.7 (no caption)=$withoutCaption")
                assertTrue(band < 4.0 && withoutCaption > band * 2, "caption must match the export's ($band vs $withoutCaption)")
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
