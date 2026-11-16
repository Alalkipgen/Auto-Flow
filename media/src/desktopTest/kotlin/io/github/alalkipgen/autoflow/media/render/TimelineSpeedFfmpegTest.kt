package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.*
import kotlinx.coroutines.runBlocking
import org.junit.AssumptionViolatedException
import java.io.File
import kotlin.test.*

/** Measures burned pixels around SPEED's end and the next block's lead, not just cue strings. */
class TimelineSpeedFfmpegTest {
    @Test fun spedCaptionEndsWithItsTakeBeforeTheNextBlock() {
        val (ffmpeg, ffprobe) = RealFfmpeg.orSkip("TimelineSpeedFfmpegTest")
            ?: throw AssumptionViolatedException("ffmpeg / ffprobe unavailable")
        val dir = RealFfmpeg.tempDir("timeline-speed")
        try {
            val font = RealFfmpeg.anyFont() ?: fail("Timing gate requires a real font")
            File(dir, "fonts").mkdirs(); font.copyTo(File(dir, "fonts/${font.name}"))
            val family = FontInfo.parse(font.readBytes())!!.family
            RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "color=c=black:s=320x180:r=25:d=8",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "movie.mp4"), dir)
            for ((name, duration) in listOf("a" to "2.6", "b" to "1")) RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin",
                "-f", "lavfi", "-i", "sine=f=1000:sample_rate=48000:d=$duration", "$name.wav"), dir)
            val a = RecapBlock("a", listOf(RecapClip(0, 1000), RecapClip(3000, 4000)), "FIRST")
            val b = RecapBlock("b", listOf(RecapClip(6000, 7500)), "SECOND")
            val ta = BlockTake(a.id, ArtifactId("au_000000000001"), TakeSource.RECORDED, durationMs = 2600, spokenHash = SpokenText.hash(a))
            val tb = BlockTake(b.id, ArtifactId("au_000000000002"), TakeSource.RECORDED, durationMs = 1000, spokenHash = SpokenText.hash(b))
            val story = RecapStory(source = "manual", blocks = listOf(a, b))
            val r = RecapV2(movie = RecapMovie("movie.mp4", 1, 8000, 320, 180, false), takes = listOf(ta, tb),
                fit = mapOf(a.id to FitChoice.SPEED.name), audio = RecapAudio(movieVolume = 0.0, polish = false, loudness = false))
            val job = RecapRender.job(r, story, Ratio.R16_9, dir.path, Quality.PREVIEW,
                { if (it.block == a.id) "a.wav" else "b.wav" }, family, "fonts", "exports/speed", "test")
            assertIs<RenderState.Done>(runBlocking { RealFfmpeg.runner(ffmpeg, ffprobe).run(job) })
            val mp4 = File(dir, "exports/speed.mp4")
            val measured = listOf(100L, 600L, 2050L, 2500L, 3400L).associateWith { at ->
                val image = RealFfmpeg.frame(ffmpeg, mp4, at, dir)
                var whites = 0
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val p = image.getRGB(x, y)
                    if ((p shr 16 and 255) > 200 && (p shr 8 and 255) > 200 && (p and 255) > 200) whites++
                }
                whites
            }
            println("SPEED_CAPTION_PIXELS $measured")
            assertTrue(measured.getValue(600) > 100 && measured.getValue(2500) > 100, "both timed captions actually burned: $measured")
            for (at in listOf(100L, 2050L, 3400L)) assertEquals(0, measured.getValue(at), "no caption at $at ms; SPEED must not spill into the next block's lead")
            assertTrue(RealFfmpeg.rms(RealFfmpeg.samples(ffmpeg, mp4, dir), 2000, 2150) < 0.01, "take and caption agree in the gap")
        } finally { dir.deleteRecursively() }
    }
}
