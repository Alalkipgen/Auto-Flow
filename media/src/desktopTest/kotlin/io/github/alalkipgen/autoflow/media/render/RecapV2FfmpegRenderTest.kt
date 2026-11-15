package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.BlockTake
import io.github.alalkipgen.autoflow.core.recap.Layer
import io.github.alalkipgen.autoflow.core.recap.LayerParams
import io.github.alalkipgen.autoflow.core.recap.LayerType
import io.github.alalkipgen.autoflow.core.recap.RecapAudio
import io.github.alalkipgen.autoflow.core.recap.RecapBlock
import io.github.alalkipgen.autoflow.core.recap.RecapClip
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapStory
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.SpokenText
import io.github.alalkipgen.autoflow.core.recap.Srt
import io.github.alalkipgen.autoflow.core.recap.TakeSource
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Audit F07 with a real render: a block of two movie clips ([0, 1 s] blue + [3 s, 5 s] red; the green 1–3 s between
 * them must never show), a BORDER layer and two takes (the chosen older one: 1 s of 1 kHz; a newer one: 2 s of
 * 440 Hz). The mp4 must be 3 s long, show blue then red inside a magenta border, and play only the 1 kHz take — in
 * 16:9, 9:16 and 1:1, through the production [RenderJobRunner].
 */
class RecapV2FfmpegRenderTest {
    private val block = RecapBlock("b_a", listOf(RecapClip(0, 1_000), RecapClip(3_000, 5_000)), "ကောင်လေး")
    private val story = RecapStory(source = RecapStory.SOURCE_MANUAL, blocks = listOf(block))
    private val old = BlockTake(block.id, ArtifactId("au_000000000001"), TakeSource.TTS, durationMs = 1_000, spokenHash = SpokenText.hash(block))
    private val newer = BlockTake(block.id, ArtifactId("au_000000000002"), TakeSource.TTS, durationMs = 2_000, spokenHash = SpokenText.hash(block))

    private fun fixture(ffmpeg: String, dir: File) {
        File(dir, "source").mkdirs(); File(dir, "artifacts").mkdirs()
        val c = { colour: String, d: Int -> listOf("-f", "lavfi", "-i", "color=c=$colour:s=320x180:r=25:d=$d") }
        RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin") + c("blue", 1) + c("lime", 2) + c("red", 2) + c("yellow", 1) +
            listOf("-f", "lavfi", "-i", "sine=f=3000:sample_rate=48000:d=6",
                "-filter_complex", "[0:v][1:v][2:v][3:v]concat=n=4:v=1:a=0[v]", "-map", "[v]", "-map", "4:a",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", "source/movie.mp4"), dir)
        RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "sine=f=1000:sample_rate=48000:d=1",
            "-ac", "1", "artifacts/${old.audio.value}.wav"), dir)
        RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "sine=f=440:sample_rate=48000:d=2",
            "-ac", "1", "artifacts/${newer.audio.value}.wav"), dir)
    }

    private fun recap() = RecapV2(
        rightsConfirmed = true,
        movie = RecapMovie("source/movie.mp4", 1, 6_000, 320, 180, hasAudio = true),
        story = ArtifactId("rs_aaaaaaaaaaaa"),
        storyApproved = true,
        takes = listOf(old, newer),
        chosenTakes = mapOf(block.id to old.audio),
        layers = listOf(Layer("border", LayerType.BORDER, params = LayerParams(px = 16, colour = "#FF00FF"))),
        // Movie sound off: anything but the chosen take is a bug; no polish / loudness so the tone stays clean.
        audio = RecapAudio(movieVolume = 0.0, polish = false, loudness = false),
    )

    private fun isBlue(p: Triple<Int, Int, Int>) = p.third > 180 && p.first < 70 && p.second < 70
    private fun isRed(p: Triple<Int, Int, Int>) = p.first > 180 && p.second < 70 && p.third < 70
    private fun isMagenta(p: Triple<Int, Int, Int>) = p.first > 180 && p.third > 180 && p.second < 80

    @Test fun multiClipLayeredRecapRendersWhatTheProjectSays() {
        val (ffmpeg, ffprobe) = RealFfmpeg.orSkip("RecapV2FfmpegRenderTest") ?: return
        val dir = RealFfmpeg.tempDir("recap-v2-render")
        try {
            fixture(ffmpeg, dir)
            for (ratio in listOf(Ratio.R16_9, Ratio.R9_16, Ratio.R1_1)) {
                val name = "exports/out_${ratio.name}"
                val job = RecapRender.job(recap(), story, ratio, dir.path, Quality.PREVIEW, { "artifacts/${it.audio.value}.wav" },
                    null, ".cache/fonts", name, "t")
                val state = runBlocking { RealFfmpeg.runner(ffmpeg, ffprobe).run(job) }
                assertIs<RenderState.Done>(state, "$ratio: $state")
                val mp4 = File(dir, "$name.mp4")
                val out = job.graph.output

                // Duration: both clips, 1 s + 2 s.
                val ms = RealFfmpeg.durationMs(ffprobe, mp4)
                assertTrue(abs(ms - 3_000) <= 100, "$ratio: rendered $ms ms, expected 3000")
                assertTrue(RealFfmpeg.streams(ffprobe, mp4).any { it == "video,${out.width},${out.height}" }, RealFfmpeg.streams(ffprobe, mp4).toString())

                // Pixels: clip 1 (blue), then clip 2 (red) — never the green between them; the border all round.
                val cx = out.width / 2; val cy = out.height / 2
                val f05 = RealFfmpeg.frame(ffmpeg, mp4, 500, dir)
                val f15 = RealFfmpeg.frame(ffmpeg, mp4, 1_500, dir)
                val f25 = RealFfmpeg.frame(ffmpeg, mp4, 2_500, dir)
                assertTrue(isBlue(RealFfmpeg.rgb(f05, cx, cy)), "$ratio 0.5 s: ${RealFfmpeg.rgb(f05, cx, cy)}")
                assertTrue(isRed(RealFfmpeg.rgb(f15, cx, cy)), "$ratio 1.5 s must be the second clip: ${RealFfmpeg.rgb(f15, cx, cy)}")
                assertTrue(isRed(RealFfmpeg.rgb(f25, cx, cy)), "$ratio 2.5 s: ${RealFfmpeg.rgb(f25, cx, cy)}")
                for ((x, y) in listOf(6 to cy, out.width - 7 to cy, cx to 6, cx to out.height - 7)) {
                    assertTrue(isMagenta(RealFfmpeg.rgb(f15, x, y, 4)), "$ratio border at ($x, $y): ${RealFfmpeg.rgb(f15, x, y, 4)}")
                }

                // Sound: the chosen 1 kHz take from 0.2 s to 1.2 s, then silence (no 440 Hz newer take, no movie tone).
                val s = RealFfmpeg.samples(ffmpeg, mp4, dir)
                val hz = RealFfmpeg.hz(s, 350, 1_100)
                assertTrue(abs(hz - 1_000) < 60, "$ratio: voice pitch $hz Hz, expected the chosen 1 kHz take")
                assertTrue(RealFfmpeg.rms(s, 350, 1_100) > 0.05, "$ratio: the take is audible")
                assertTrue(RealFfmpeg.rms(s, 1_400, 2_900) < 0.01, "$ratio: silence after the take, got rms ${RealFfmpeg.rms(s, 1_400, 2_900)}")

                // .srt next to it, the caption over the take.
                val srt = Srt.parse(File(dir, "$name.srt").readText())
                assertEquals(listOf(200L to 1_200L), srt.map { it.startMs to it.endMs })
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
