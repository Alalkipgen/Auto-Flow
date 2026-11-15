package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.BlockTake
import io.github.alalkipgen.autoflow.core.recap.FitChoice
import io.github.alalkipgen.autoflow.core.recap.Layer
import io.github.alalkipgen.autoflow.core.recap.LayerParams
import io.github.alalkipgen.autoflow.core.recap.LayerType
import io.github.alalkipgen.autoflow.core.recap.RecapBlock
import io.github.alalkipgen.autoflow.core.recap.RecapCaptions
import io.github.alalkipgen.autoflow.core.recap.RecapClip
import io.github.alalkipgen.autoflow.core.recap.RecapFit
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapStory
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.SpokenText
import io.github.alalkipgen.autoflow.core.recap.Srt
import io.github.alalkipgen.autoflow.core.recap.TakeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The v2-native recap render (audit F05 / F07): every clip, every layer, the chosen take, caption chunk edits. */
class RecapRenderV2Test {
    private val block = RecapBlock("b_a", listOf(RecapClip(0, 1_000), RecapClip(3_000, 5_000)), "ကောင်လေး")
    private val second = RecapBlock("b_b", listOf(RecapClip(6_000, 8_000)), "သူငယ်ချင်း")
    private val story = RecapStory(source = RecapStory.SOURCE_MANUAL, blocks = listOf(block, second))
    private val border = Layer("border", LayerType.BORDER, params = LayerParams(px = 12, colour = "#FF00FF"))
    private val recap = RecapV2(
        rightsConfirmed = true,
        movie = RecapMovie("source/movie.mp4", 9, 10_000, 1920, 1080),
        story = ArtifactId("rs_aaaaaaaaaaaa"),
        storyApproved = true,
        layers = listOf(border),
    )
    private fun take(b: RecapBlock, audio: String, ms: Long) =
        BlockTake(b.id, ArtifactId(audio), TakeSource.TTS, durationMs = ms, spokenHash = SpokenText.hash(b))
    private val files = { t: BlockTake -> "artifacts/${t.audio.value}.wav" }

    @Test fun exportMustKeepAllV2LayersAndClips() {
        val g = RecapRender.graph(recap, story, Ratio.R16_9, Quality.PREVIEW, files, null)
        // Both clips of the first block in order (1 s + 2 s = 3 s), then the second block.
        assertEquals(listOf(0L to 1_000L, 3_000L to 5_000L, 6_000L to 8_000L), g.clips.map { it.trimInMs to it.trimOutMs })
        assertEquals(5_000, g.clips.sumOf { it.durationMs })
        assertTrue(g.clips.all { it.source == "source/movie.mp4" })
        assertEquals(listOf(border), g.layers, "the project's BORDER layer is kept")
        // The job says the same; the fit footer total equals the render length.
        val job = RecapRender.job(recap, story, Ratio.R16_9, "/p", Quality.PREVIEW, files, null, ".cache/fonts", "exports/x", "x")
        assertEquals(RecapFit.totalMs(RecapFit.rows(recap, story)), job.graph.clips.sumOf { it.durationMs })
    }

    @Test fun exportMustUseTheChosenTake() {
        val old = take(block, "va_000000000old", 1_000)
        val newer = take(block, "va_00000000new0", 2_000)
        val chosen = recap.copy(takes = listOf(old, newer), chosenTakes = mapOf(block.id to old.audio))
        val g = RecapRender.graph(chosen, story, Ratio.R16_9, Quality.PREVIEW, files, null)
        assertEquals(listOf("artifacts/va_000000000old.wav"), g.voice!!.cues.map { it.file })
        assertEquals(listOf(RecapFit.LEAD_MS), g.voice!!.cues.map { it.startMs })
        // No choice → the newest (documented fallback).
        val newest = RecapRender.graph(chosen.copy(chosenTakes = emptyMap()), story, Ratio.R16_9, Quality.PREVIEW, files, null)
        assertEquals(listOf("artifacts/va_00000000new0.wav"), newest.voice!!.cues.map { it.file })
    }

    @Test fun aRunOnExtendsTheLastClipOfTheBlock() {
        // A 3.5 s take on the 3 s block: clip = 3.9 s; the extra 0.9 s goes on the block's last clip.
        val r = recap.copy(takes = listOf(take(block, "va_aaaaaaaaaaaa", 3_500)))
        val g = RecapRender.graph(r, story, Ratio.R16_9, Quality.PREVIEW, files, null)
        assertEquals(listOf(0L to 1_000L, 3_000L to 5_900L, 6_000L to 8_000L), g.clips.map { it.trimInMs to it.trimOutMs })
        // The second block's voice would start after 3.9 s; here it has none. Slow video stretches each clip.
        val slow = RecapRender.graph(r.copy(fit = mapOf(block.id to FitChoice.SLOW_VIDEO.name)), story, Ratio.R16_9, Quality.PREVIEW, files, null)
        val firstBlock = slow.clips.take(2)
        assertEquals(3_900, firstBlock.sumOf { it.durationMs })
        assertTrue(firstBlock.all { it.slowFactor < 1f })
        assertEquals(listOf(0L, 3_000L), firstBlock.map { it.trimInMs })
    }

    @Test fun chunkEditsGiveTheSameCuesInTheSrtAndBurnedIn() {
        val r = recap.copy(
            takes = listOf(take(block, "va_aaaaaaaaaaaa", 2_400), take(second, "va_bbbbbbbbbbbb", 1_500)),
            captions = RecapCaptions(burn = true, chunkEdits = mapOf(block.id to listOf(300L, 1_900L))),
        )
        val job = RecapRender.job(r, story, Ratio.R16_9, "/p", Quality.PREVIEW, files, "Padauk", ".cache/fonts", "exports/x", "x")
        val srt = Srt.parse(job.srt!!)
        // Block a: the owner's window, 0.2 s lead + 300 … 1900 ms into the take. Block b (automatic): its whole take,
        // after block a's 3 s.
        assertEquals(listOf(500L to 2_100L, 3_200L to 4_700L), srt.map { it.startMs to it.endMs })
        assertEquals(listOf("ကောင်လေး", "သူငယ်ချင်း"), srt.map { it.text })
        assertEquals(srt.map { Triple(it.startMs, it.endMs, it.text) }, job.graph.overlays.map { Triple(it.startMs, it.endMs, it.text) })
        // Edges that do not fit the take (left from a longer take) fall back to automatic timing.
        val longer = r.copy(captions = r.captions.copy(chunkEdits = mapOf(block.id to listOf(300L, 9_000L))))
        val auto = Srt.parse(RecapRender.job(longer, story, Ratio.R16_9, "/p", Quality.PREVIEW, files, null, ".cache/fonts", "exports/x", "x").srt!!)
        assertEquals(200L to 2_600L, auto.first().let { it.startMs to it.endMs })
    }
}
