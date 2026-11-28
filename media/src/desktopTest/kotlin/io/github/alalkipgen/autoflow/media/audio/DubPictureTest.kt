package io.github.alalkipgen.autoflow.media.audio

import io.github.alalkipgen.autoflow.core.model.DubMask
import io.github.alalkipgen.autoflow.core.model.DubMaskKind
import io.github.alalkipgen.autoflow.media.render.DubPicture
import io.github.alalkipgen.autoflow.media.render.MediaTaskJob
import io.github.alalkipgen.autoflow.media.render.RealFfmpeg
import java.io.File
import kotlin.math.abs
import kotlin.test.*

/** D-107 (specs/dubbing.md §2 Style): what the dub draws on the picture — blur / colour boxes under the subtitles. */
class DubPictureTest {
    private val bottom = DubMask(DubMaskKind.BOTTOM, DubMaskKind.BOTTOM.rect)
    private val red = DubMask(DubMaskKind.LOGO_TR, DubMaskKind.LOGO_TR.rect, "#FF0000")

    private fun graph(args: List<String>) = args[args.indexOf("-filter_complex") + 1]

    @Test fun subtitlesAloneMakeTheSameCommandAsBefore() {
        val before = DubMixer.exportArgs("v.mp4", "v.wav", 6_000, 0.15, true, "mp4", "192k", ass = "a.ass", fontsDir = "fonts")
        assertEquals(before, DubMixer.exportArgs("v.mp4", "v.wav", 6_000, 0.15, true, "mp4", "192k", DubPicture(ass = "a.ass", fontsDir = "fonts")))
        assertTrue(graph(before).startsWith("[0:v]ass=filename=a.ass:fontsdir=fonts[vout];"), graph(before))
        assertEquals(DubMixer.exportArgs("v.mkv", "v.wav", 6_000, 0.15, true, "mkv", "192k"),
            DubMixer.exportArgs("v.mkv", "v.wav", 6_000, 0.15, true, "mkv", "192k", DubPicture()), "nothing drawn = the picture copied")
    }

    @Test fun boxesComeFirstThenTheSubtitles() {
        val chain = DubPicture(listOf(bottom, red), sigma = 20, ass = "a.ass", fontsDir = "fonts").chain()
        assertEquals("[0:v]split[dm0a][dm0b];[dm0b]crop=w=iw*1:h=ih*0.17:x=iw*0:y=ih*0.8,gblur=sigma=20[dm0c];" +
            "[dm0a][dm0c]overlay=x=main_w*0:y=main_h*0.8[dm0];" +
            "[dm0]drawbox=x=iw*0.76:y=ih*0.03:w=iw*0.22:h=ih*0.13:color=0xFF0000@1:t=fill[dm1];" +
            "[dm1]ass=filename=a.ass:fontsdir=fonts[vout]", chain)
        assertEquals("[0:v]drawbox=x=iw*0.76:y=ih*0.03:w=iw*0.22:h=ih*0.13:color=0xFF0000@1:t=fill[dm0];[dm0]null[vout]", DubPicture(listOf(red)).chain())
        val args = DubMixer.exportArgs("v.mkv", "v.wav", 6_000, 0.15, true, "mkv", "192k", DubPicture(listOf(bottom)))
        assertTrue("libx264" in args && "copy" !in args && args.takeLast(3) == listOf("-f", "mp4", MediaTaskJob.PART), "boxes make a new mp4")
    }

    @Test fun realFfmpegHidesTheBoxesAndKeepsEveryFrame() {
        val (ffmpeg, ffprobe) = RealFfmpeg.orSkip("DubPictureTest") ?: return
        val dir = RealFfmpeg.tempDir("dub-picture")
        try {
            File(dir, "source").mkdirs()
            RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "testsrc2=s=320x180:r=25:d=4", "-f", "lavfi",
                "-i", "sine=f=300:d=4", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", "source/v.mp4"), dir)
            RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "sine=f=800:d=4:sample_rate=48000", "-ac", "2",
                "-c:a", "pcm_s16le", "voice.wav"), dir)
            val out = File(dir, "out.mp4")
            val args = DubMixer.exportArgs("source/v.mp4", "voice.wav", 4_000, 0.15, true, "mp4", "192k", DubPicture(listOf(bottom, red), sigma = 12),
                x264Preset = "ultrafast")
            RealFfmpeg.ok(listOf(ffmpeg) + args.map { if (it == MediaTaskJob.PART) out.path else it }, dir)
            assertTrue(abs(RealFfmpeg.durationMs(ffprobe, out) - 4_000) <= 100, "the dub keeps the video's length")
            val frames = RealFfmpeg.run(listOf(ffprobe, "-v", "error", "-count_frames", "-select_streams", "v:0", "-show_entries", "stream=nb_read_frames",
                "-of", "csv=p=0", out.path)).second.trim()
            assertEquals("100", frames, "every frame of the 4 s at 25 fps")
            val src = File(dir, "source/v.mp4")
            for (at in listOf(1_000L, 3_000L)) {
                val a = RealFfmpeg.frame(ffmpeg, src, at, dir)
                val b = RealFfmpeg.frame(ffmpeg, out, at, dir)
                val (r, g, bl) = RealFfmpeg.rgb(b, (320 * .87).toInt(), (180 * .095).toInt())
                assertTrue(r > 200 && g < 50 && bl < 50, "the colour box is red at $at ms: $r $g $bl")
                assertTrue(RealFfmpeg.diff(a, b, 0, (180 * .82).toInt(), 320, (180 * .95).toInt()) > 8, "the bottom strip is blurred at $at ms")
                assertTrue(RealFfmpeg.diff(a, b, 20, 50, 220, 120) < 4, "the rest of the picture stays as it was at $at ms")
            }
            assertTrue(RealFfmpeg.diff(RealFfmpeg.frame(ffmpeg, out, 1_000, dir), RealFfmpeg.frame(ffmpeg, out, 3_000, dir), 20, 50, 220, 120) > 4,
                "the picture moves on (not one frame held)")
        } finally {
            dir.deleteRecursively()
        }
    }
}
