package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Real ffmpeg / ffprobe for render tests that measure the output (pixels, duration, sound), not filter strings.
 * Binaries from `AUTOFLOW_FFMPEG` / `AUTOFLOW_FFPROBE` or PATH; with `AUTOFLOW_EXPECT_FFMPEG=1` (CI) a missing
 * binary fails, otherwise the test is skipped with a note.
 */
internal object RealFfmpeg {
    private fun works(bin: String) = runCatching { run(listOf(bin, "-hide_banner", "-version")).first == 0 }.getOrDefault(false)

    val ffmpeg: String? by lazy { listOfNotNull(System.getenv("AUTOFLOW_FFMPEG"), "ffmpeg").firstOrNull(::works) }
    val ffprobe: String? by lazy {
        listOfNotNull(System.getenv("AUTOFLOW_FFPROBE"), ffmpeg?.let { File(File(it).parentFile ?: File("."), "ffprobe").path }, "ffprobe")
            .firstOrNull(::works)
    }

    /** ffmpeg + ffprobe, or null (skip) — a failure when CI expects them. */
    fun orSkip(test: String): Pair<String, String>? {
        val f = ffmpeg; val p = ffprobe
        if (f != null && p != null) return f to p
        if (System.getenv("AUTOFLOW_EXPECT_FFMPEG") == "1") fail("AUTOFLOW_EXPECT_FFMPEG=1 but no ffmpeg / ffprobe")
        println("SKIP $test: no ffmpeg / ffprobe binary")
        return null
    }

    fun run(cmd: List<String>, dir: File? = null, timeoutSec: Long = 300): Pair<Int, String> {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).also { if (dir != null) it.directory(dir) }.start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to "timeout\n$text" }
        return p.exitValue() to text
    }

    fun ok(cmd: List<String>, dir: File? = null) {
        val (code, log) = run(cmd, dir)
        assertEquals(0, code, "${cmd.joinToString(" ")}\n$log")
    }

    /** The production runner over real processes and files. */
    fun runner(ffmpeg: String, ffprobe: String): RenderJobRunner =
        RenderJobRunner(LocalFs(), FfmpegRunner(ProcessTools(), ffmpeg), MediaProbe(ProcessTools(), ffprobe))

    /** Output length in ms (container duration). */
    fun durationMs(ffprobe: String, file: File): Long {
        val (code, out) = run(listOf(ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", file.path))
        assertEquals(0, code, out)
        return (out.trim().lines().first().toDouble() * 1000).toLong()
    }

    /** Stream summary lines `codec_type,width,height,...` for assertions. */
    fun streams(ffprobe: String, file: File): List<String> {
        val (code, out) = run(listOf(ffprobe, "-v", "error", "-show_entries", "stream=codec_type,width,height", "-of", "csv=p=0", file.path))
        assertEquals(0, code, out)
        return out.trim().lines().filter { it.isNotBlank() }
    }

    /** The frame at [atMs] as an image. */
    fun frame(ffmpeg: String, file: File, atMs: Long, dir: File): BufferedImage {
        val png = File(dir, "f_${file.nameWithoutExtension}_$atMs.png")
        ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-ss", "%.3f".format(atMs / 1000.0), "-i", file.path,
            "-frames:v", "1", "-pix_fmt", "rgb24", png.path))
        return ImageIO.read(png)
    }

    /** Mono 48 kHz samples (−1 … 1) of [file]'s audio. */
    fun samples(ffmpeg: String, file: File, dir: File): FloatArray {
        val raw = File(dir, "${file.nameWithoutExtension}.f32")
        ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-i", file.path, "-vn", "-ac", "1", "-ar", "48000", "-f", "f32le", raw.path))
        val buf = ByteBuffer.wrap(raw.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buf.remaining()).also { buf.get(it) }
    }

    fun rms(s: FloatArray, fromMs: Long, toMs: Long): Double {
        val a = (fromMs * 48).toInt().coerceIn(0, s.size); val b = (toMs * 48).toInt().coerceIn(a, s.size)
        if (b == a) return 0.0
        var sum = 0.0
        for (i in a until b) sum += s[i] * s[i]
        return sqrt(sum / (b - a))
    }

    /** Rough pitch from zero crossings in [fromMs, toMs). */
    fun hz(s: FloatArray, fromMs: Long, toMs: Long): Double {
        val a = (fromMs * 48).toInt().coerceIn(0, s.size); val b = (toMs * 48).toInt().coerceIn(a, s.size)
        var crossings = 0
        for (i in a + 1 until b) if ((s[i - 1] < 0f) != (s[i] < 0f)) crossings++
        return crossings / 2.0 / ((b - a) / 48_000.0)
    }

    /** Mean RGB of a [size]² square centred at ([x], [y]). */
    fun rgb(img: BufferedImage, x: Int, y: Int, size: Int = 6): Triple<Int, Int, Int> {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (yy in (y - size / 2) until (y + size / 2)) for (xx in (x - size / 2) until (x + size / 2)) {
            if (xx !in 0 until img.width || yy !in 0 until img.height) continue
            val p = img.getRGB(xx, yy); r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
        }
        return Triple((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** Mean absolute RGB difference of [a] and [b] over the box [x0, x1) × [y0, y1). */
    fun diff(a: BufferedImage, b: BufferedImage, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var sum = 0L; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            if (x !in 0 until a.width || y !in 0 until a.height) continue
            val p = a.getRGB(x, y); val q = b.getRGB(x, y)
            for (s in intArrayOf(16, 8, 0)) sum += kotlin.math.abs(((p shr s) and 0xFF) - ((q shr s) and 0xFF))
            n++
        }
        return if (n == 0) 0.0 else sum.toDouble() / (n * 3)
    }

    /** Any TrueType font on the machine (DejaVu / Liberation / Arial first), for burned-in text. */
    fun anyFont(): File? = listOf("/usr/share/fonts", "/usr/local/share/fonts", "C:/Windows/Fonts", "/System/Library/Fonts")
        .map(::File).filter { it.isDirectory }
        .flatMap { d -> d.walkTopDown().filter { it.isFile && it.name.endsWith(".ttf", true) }.toList() }
        .sortedBy { f -> listOf("DejaVuSans.ttf", "LiberationSans-Regular.ttf", "arial.ttf").indexOf(f.name).let { if (it < 0) 99 else it } }
        .firstOrNull { f -> FontInfo.parse(f.readBytes()) != null }

    fun tempDir(name: String): File = Files.createTempDirectory(name).toFile()
}

/** [ToolRunner] over [ProcessBuilder] (stdout + stderr merged, `\r` ends a line too). */
internal class ProcessTools : ToolRunner {
    override suspend fun run(command: List<String>, workDir: String, timeoutMs: Long, keepOutput: Boolean, onLine: (String) -> Unit): ToolExec {
        val started = System.currentTimeMillis()
        val p = try {
            ProcessBuilder(command).directory(File(workDir)).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return ToolExec.CannotStart(e.message ?: e.toString())
        }
        val all = StringBuilder()
        val tail = ArrayDeque<String>()
        val reader = Thread {
            val line = StringBuilder()
            fun flush() {
                val l = line.toString(); line.clear()
                onLine(l); if (keepOutput) all.append(l).append('\n')
                tail.addLast(l); while (tail.size > 40) tail.removeFirst()
            }
            p.inputStream.bufferedReader().use { r ->
                while (true) {
                    val c = r.read(); if (c < 0) break
                    if (c == '\n'.code || c == '\r'.code) flush() else line.append(c.toChar())
                }
                if (line.isNotEmpty()) flush()
            }
        }.also { it.start() }
        val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) p.destroyForcibly()
        reader.join(5_000)
        return ToolExec.Done(if (done) p.exitValue() else -1, all.toString(), tail.toList(), System.currentTimeMillis() - started, !done)
    }
}

/** [FileSystem] over java.io for real-file render tests. */
internal class LocalFs : FileSystem {
    override fun exists(path: String) = File(path).exists()
    override fun createDirectories(path: String) { File(path).mkdirs() }
    override fun list(path: String): List<String> = File(path).list()?.sorted().orEmpty()
    override fun read(path: String): ByteArray = File(path).readBytes()
    override fun writeDurably(path: String, bytes: ByteArray) { File(path).parentFile?.mkdirs(); File(path).writeBytes(bytes) }
    override fun atomicMove(from: String, to: String) {
        Files.move(File(from).toPath(), File(to).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
