package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.BlockTake
import io.github.alalkipgen.autoflow.core.recap.MaskPreset
import io.github.alalkipgen.autoflow.core.recap.RecapAudio
import io.github.alalkipgen.autoflow.core.recap.RecapBlock
import io.github.alalkipgen.autoflow.core.recap.RecapClip
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapStory
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.RecapVisual
import io.github.alalkipgen.autoflow.core.recap.RecapVisuals
import io.github.alalkipgen.autoflow.core.recap.SpokenText
import io.github.alalkipgen.autoflow.core.recap.TakeSource
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 5B-2 custom box with a real render: a detailed test picture with one Custom box moved and widened to the left
 * side (not a full-width strip, not a corner preset). Rendered twice through the production [RenderJobRunner] — with
 * the box and without — in 16:9, 9:16 and 1:1: inside the box (mapped into the fitted picture) the frame must be
 * blurred, everywhere else it must be the same picture.
 */
class CustomMaskFfmpegTest {
    private val block = RecapBlock("b_a", listOf(RecapClip(0, 1_000)), "ကောင်လေး")
    private val story = RecapStory(source = RecapStory.SOURCE_MANUAL, blocks = listOf(block))
    private val take = BlockTake(block.id, ArtifactId("au_000000000001"), TakeSource.TTS, durationMs = 1_000, spokenHash = SpokenText.hash(block))

    private fun recap(visual: RecapVisual) = RecapV2(
        rightsConfirmed = true,
        movie = RecapMovie("source/movie.mp4", 1, 2_000, 320, 180, hasAudio = true),
        story = ArtifactId("rs_aaaaaaaaaaaa"),
        storyApproved = true,
        takes = listOf(take),
        chosenTakes = mapOf(block.id to take.audio),
        audio = RecapAudio(movieVolume = 0.0, polish = false, loudness = false),
        visual = visual,
    )

    /** Mean absolute difference outside the box [x0, x1) × [y0, y1). */
    private fun diffOutside(a: BufferedImage, b: BufferedImage, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var sum = 0.0; var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (x in x0 until x1 && y in y0 until y1) continue
            sum += RealFfmpeg.diff(a, b, x, y, x + 1, y + 1); n++
        }
        return sum / n
    }

    @Test fun customBoxBlursOnlyItsOwnPlaceInEveryRatio() {
        val (ffmpeg, ffprobe) = RealFfmpeg.orSkip("CustomMaskFfmpegTest") ?: return
        val dir = RealFfmpeg.tempDir("custom-mask")
        try {
            File(dir, "source").mkdirs(); File(dir, "artifacts").mkdirs()
            RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "testsrc2=s=320x180:r=25:d=2",
                "-f", "lavfi", "-i", "sine=f=3000:sample_rate=48000:d=2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
                "-shortest", "source/movie.mp4"), dir)
            RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "sine=f=1000:sample_rate=48000:d=1",
                "-ac", "1", "artifacts/${take.audio.value}.wav"), dir)

            // The user's box: Custom box, three steps left, one step wider → [0.22, 0.3, 0.44, 0.2] (upper half: the
            // captions stay in their usual place, the same in both renders).
            var visual = RecapVisuals.toggle(RecapVisual(), MaskPreset.CUSTOM)
            repeat(3) { visual = RecapVisuals.moveX(visual, MaskPreset.CUSTOM, -RecapVisuals.STEP) }
            visual = RecapVisuals.resizeW(visual, MaskPreset.CUSTOM, 2 * RecapVisuals.STEP)
            val r = visual.masks.single().rect
            assertTrue(listOf(0.22f, 0.3f, 0.44f, 0.2f).zip(r).all { (e, a) -> abs(e - a) < 0.0011f }, "box $r")
            assertTrue(visual.captionMask == null, "a box above the middle does not carry the captions")

            for (ratio in listOf(Ratio.R16_9, Ratio.R9_16, Ratio.R1_1)) {
                fun render(v: RecapVisual, name: String): BufferedImage {
                    val job = RecapRender.job(recap(v), story, ratio, dir.path, Quality.PREVIEW, { "artifacts/${it.audio.value}.wav" },
                        null, ".cache/fonts", "exports/$name", "t")
                    val state = runBlocking { RealFfmpeg.runner(ffmpeg, ffprobe).run(job) }
                    assertIs<RenderState.Done>(state, "$ratio $name: $state")
                    return RealFfmpeg.frame(ffmpeg, File(dir, "exports/$name.mp4"), 500, dir)
                }
                val boxed = render(visual, "box_${ratio.name}")
                val plain = render(RecapVisual(), "plain_${ratio.name}")

                // The movie (320×180) fitted inside the frame with bars; the box in frame pixels.
                val w = boxed.width; val h = boxed.height
                val s = minOf(w / 320.0, h / 180.0)
                val left = (w - 320 * s) / 2; val top = (h - 180 * s) / 2
                val bx0 = (left + r[0] * 320 * s).toInt(); val bx1 = (left + (r[0] + r[2]) * 320 * s).toInt()
                val by0 = (top + r[1] * 180 * s).toInt(); val by1 = (top + (r[1] + r[3]) * 180 * s).toInt()
                val mx = (bx1 - bx0) / 6; val my = (by1 - by0) / 6
                val inside = RealFfmpeg.diff(boxed, plain, bx0 + mx, by0 + my, bx1 - mx, by1 - my)
                val pad = maxOf(6, (6 * s).toInt())
                val outside = diffOutside(boxed, plain, bx0 - pad, by0 - pad, bx1 + pad, by1 + pad)
                println("CustomMaskFfmpegTest $ratio ${w}x$h box ($bx0,$by0)-($bx1,$by1): inside diff %.1f, outside diff %.2f".format(inside, outside))
                assertTrue(inside > 12, "$ratio: the box must be blurred, inside diff $inside")
                assertTrue(outside < 3, "$ratio: nothing else changes, outside diff $outside")
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
