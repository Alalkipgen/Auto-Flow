package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.recap.SrtCue
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S-4c with a real ffmpeg: a 640×266 23.976 fps source with AAC sound, burned by [SubtitleBurn.args] as the task runs
 * it — the size, frame rate and sound codec are kept, and the text is on screen exactly while its cue runs (±1 frame).
 */
class RealSubtitleBurnTest {
    private val cue = SrtCue(1_000, 2_500, "HELLO WORLD")

    /** Bright pixels in the lower half: burned white text on a black picture. */
    private fun textPixels(img: BufferedImage): Int {
        var n = 0
        for (y in img.height / 2 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            if (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF) > 600) n++
        }
        return n
    }

    @Test
    fun burnKeepsPictureAndSoundAndCueTimes() {
        val (ffmpeg, ffprobe) = RealFfmpeg.orSkip("RealSubtitleBurnTest") ?: return
        val dir = RealFfmpeg.tempDir("burn")
        val font = RealFfmpeg.anyFont() ?: error("no TrueType font for the burned text")
        val info = FontInfo.parse(font.readBytes())!!
        File(dir, ".cache/fonts").mkdirs(); font.copyTo(File(dir, ".cache/fonts/${font.name}"))
        File(dir, "source").mkdirs()
        RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "color=c=black:s=640x266:r=24000/1001:d=4",
            "-f", "lavfi", "-i", "sine=f=440:d=4", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac",
            "-shortest", "source/in.mp4"), dir)
        File(dir, ".cache/burn").mkdirs()
        File(dir, ".cache/burn/a.ass").writeText(SubtitleBurn.ass(listOf(cue), 640, 266, info.family, BurnLook.BOXED, BurnSize.LARGE, 42, info.assScale))

        val args = SubtitleBurn.args("source/in.mp4", ".cache/burn/a.ass", ".cache/fonts", "aac", "ultrafast", 20)
        RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner") + args.map { if (it == MediaTaskJob.PART) "out.mp4" else it }, dir)
        val out = File(dir, "out.mp4")

        val (code, probe) = RealFfmpeg.run(listOf(ffprobe, "-v", "error", "-show_entries", "stream=codec_type,codec_name,width,height,r_frame_rate",
            "-of", "csv=p=0", out.path))
        assertEquals(0, code, probe)
        val lines = probe.trim().lines()
        assertTrue(lines.any { it.startsWith("h264,video,640,266,24000/1001") }, probe)
        assertTrue(lines.any { it.startsWith("aac,audio") }, probe)
        assertTrue(kotlin.math.abs(RealFfmpeg.durationMs(ffprobe, out) - 4_000) < 150, "duration")

        val frame = 1001.0 / 24.0
        fun at(ms: Double) = textPixels(RealFfmpeg.frame(ffmpeg, out, ms.toLong(), dir))
        assertEquals(0, at(500.0), "before the cue")
        assertEquals(0, at(cue.startMs - 1.5 * frame), "one frame before the start")
        assertTrue(at(cue.startMs + 1.5 * frame) > 50, "one frame after the start")
        assertTrue(at(1_800.0) > 50, "inside the cue")
        assertTrue(at(cue.endMs - 1.5 * frame) > 50, "one frame before the end")
        assertEquals(0, at(cue.endMs + 1.5 * frame), "one frame after the end")

        // Check one frame: the same text from the source at a cue time, nothing outside.
        RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner") + SubtitleBurn.frameArgs("source/in.mp4", ".cache/burn/a.ass", ".cache/fonts", 1_800, "f1.png"), dir)
        RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner") + SubtitleBurn.frameArgs("source/in.mp4", ".cache/burn/a.ass", ".cache/fonts", 3_200, "f2.png"), dir)
        assertTrue(textPixels(ImageIO.read(File(dir, "f1.png"))) > 50, "check frame inside")
        assertEquals(0, textPixels(ImageIO.read(File(dir, "f2.png"))), "check frame outside")
        dir.deleteRecursively()
    }
}
