package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.*
import kotlin.math.abs
import kotlin.test.*

class RecapTimelineTest {
    private val a = RecapBlock("stable-a", listOf(RecapClip(10_000, 12_000), RecapClip(20_000, 23_000)), "first written caption")
    private val b = RecapBlock("stable-b", listOf(RecapClip(4_000, 6_000)), "second written caption")
    private val story = RecapStory(source = "manual", blocks = listOf(a, b))
    private val movie = RecapMovie("source/movie.mp4", 1, 25_000, 640, 360)
    private val out = RenderPresets.preview(Ratio.R16_9)
    private fun take(block: RecapBlock, ms: Long, tag: String = "0123456789ab") =
        BlockTake(block.id, ArtifactId("au_$tag"), TakeSource.RECORDED, durationMs = ms, spokenHash = SpokenText.hash(block))
    private fun timeline(recap: RecapV2, story: RecapStory = this.story, exists: Boolean = true) =
        RecapRender.timeline(recap, story, { "artifacts/${it.audio}.wav".takeIf { exists } }, out)

    @Test fun disjointAndRepeatedRangesUseOutputTimeAndStableBlockIdentity() {
        val r = RecapV2(movie = movie)
        val t = timeline(r)
        assertEquals(listOf("stable-a:0", "stable-a:1", "stable-b:0"), t.clips.map { it.key })
        assertEquals(listOf(0L, 2_000L, 5_000L), t.clips.map { it.outputFromMs })
        assertEquals(20_500L, t.clipAt(2_500)!!.sourceAt(2_500))
        assertEquals(4_100L, t.clipAt(5_100)!!.sourceAt(5_100))
        assertEquals("stable-a", t.clipAt(1_999)!!.blockId)
        assertEquals(1, t.clipAt(2_000)!!.clipOrdinal)
        assertNull(t.clipAt(-1)); assertNull(t.clipAt(7_000))
        val repeated = timeline(r, story.copy(blocks = listOf(b, a, b.copy(id = "repeat-b"))))
        assertEquals("repeat-b", repeated.clipAt(7_000)!!.blockId)
        assertEquals(4_000L, repeated.clipAt(7_000)!!.sourceAt(7_000))
    }

    @Test fun currentChosenTakeAndMissingFilesHaveTheSameIntervalsAsTheExportGraph() {
        val chosen = take(a, 6_000); val newer = take(a, 9_000, "abcdef012345")
        val r = RecapV2(movie = movie, takes = listOf(chosen, newer), chosenTakes = mapOf(a.id to chosen.audio))
        val t = timeline(r)
        val graph = RecapRender.graph(r, story, Ratio.R16_9, Quality.PREVIEW, { "take.wav" }, "Pyidaungsu")
        assertEquals(graph.clips.map { it.durationMs }, t.clips.map { it.durationMs })
        assertEquals(listOf(2_000L, 4_400L, 2_000L), t.clips.map { it.durationMs })
        assertEquals(8_400L, t.durationMs)
        assertEquals(7_000L, timeline(r, exists = false).durationMs, "missing take is no voice, exactly like graph()")
        assertEquals(emptyList(), timeline(r, exists = false).captions)
    }

    /** F-5: the live preview plays the same voice cues the export mixes. */
    @Test fun voiceCuesAreTheExportGraphsCues() {
        val chosen = take(a, 6_000); val other = take(b, 1_500, "abcdef012345")
        val r = RecapV2(movie = movie, takes = listOf(chosen, other), fit = mapOf(a.id to FitChoice.SPEED.name))
        val t = timeline(r)
        val graph = RecapRender.graph(r, story, Ratio.R16_9, Quality.PREVIEW, { "artifacts/${it.audio}.wav" }, "Pyidaungsu")
        assertEquals(graph.voice!!.cues, t.voice)
        assertEquals(listOf(RecapFit.LEAD_MS, t.clips.first { it.blockId == b.id }.outputFromMs + RecapFit.LEAD_MS), t.voice.map { it.startMs })
        assertTrue(t.voice.first().speedFactor > 1f)
        assertNull(t.voiceAt(RecapFit.LEAD_MS - 1))
        assertEquals(t.voice[0], t.voiceAt(RecapFit.LEAD_MS))
        assertEquals(t.voice[1], t.voiceAt(t.durationMs - 1))
        assertEquals(emptyList(), timeline(r, exists = false).voice, "a missing take is not played")
    }

    @Test fun speedMapsPersistedCaptionEdgesIntoTheOutputWithoutChangingSourceClips() {
        val take = take(a, 6_000)
        val r = RecapV2(movie = movie, takes = listOf(take), fit = mapOf(a.id to FitChoice.SPEED.name),
            captions = RecapCaptions(chunkEdits = mapOf(a.id to listOf(0, 6_000))))
        val t = timeline(r)
        val speed = RecapFit.rows(r, story).first().voiceSpeed
        assertTrue(speed > 1f)
        assertEquals(7_000L, t.durationMs)
        assertEquals(200L, t.captions.first().startMs)
        assertEquals(200L + (6_000 / speed).toLong(), t.captions.last().endMs)
        assertEquals(a.clips.map { it.inMs }, t.clips.take(2).map { it.sourceFromMs })
        assertEquals(listOf(0L, 6_000L), r.captions.chunkEdits[a.id])
        assertTrue(t.captionsAt(199).isEmpty()); assertTrue(t.captionsAt(200).isNotEmpty())
        assertTrue(t.captionsAt(t.captions.last().endMs).isEmpty())
    }

    @Test fun slowedClipsAndEofHoldMapLikeSetptsTrimAndTpad() {
        val take = take(a, 6_000)
        val r = RecapV2(movie = movie, takes = listOf(take), fit = mapOf(a.id to FitChoice.SLOW_VIDEO.name))
        val t = timeline(r); val first = t.clips.first()
        assertTrue(first.sourceRate < 1f)
        val source = first.sourceAt(1_000)
        assertTrue(source in 10_700..10_900)
        assertTrue(abs(first.outputAt(source) - 1_000) <= 2)
        assertEquals(6_400L, t.clips[2].outputFromMs)
        val tailBlock = b.copy(clips = listOf(RecapClip(24_000, 25_000)))
        val tail = timeline(RecapV2(movie = movie, takes = listOf(take(tailBlock, 3_000))), story.copy(blocks = listOf(tailBlock)))
        assertEquals(3_400L, tail.durationMs)
        assertEquals(24_999L, tail.clipAt(3_000)!!.sourceAt(3_000), "never seek beyond EOF during the export's frozen tail")
    }
}
