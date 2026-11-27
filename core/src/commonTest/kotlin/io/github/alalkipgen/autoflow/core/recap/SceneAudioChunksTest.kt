package io.github.alalkipgen.autoflow.core.recap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 5B-1 / D-44 planning: which picked scenes are sent, how they become ≤ 10-minute chunks, and the job items. */
class SceneAudioChunksTest {
    private fun r(a: Long, b: Long) = TranscriptRange(a, b)

    @Test fun rangesArePackedInMovieOrderIntoChunksOfAtMostTenMinutes() {
        val chunks = SceneAudioChunks.plan(listOf(r(400_000, 700_000), r(0, 200_000), r(200_000, 350_000)))
        assertEquals(2, chunks.size)
        assertEquals(listOf(r(0, 200_000), r(200_000, 350_000)), chunks[0].ranges)
        assertEquals(listOf(0L, 200_000L), chunks[0].scenes.map { it.offsetMs })
        assertEquals(350_000, chunks[0].durationMs)
        assertEquals(listOf(r(400_000, 700_000)), chunks[1].ranges, "would make 650 s: next chunk")
        assertTrue(chunks.all { it.durationMs <= SceneAudioChunks.MAX_CHUNK_MS })
    }

    @Test fun aSceneLongerThanTheMaximumIsCutIntoParts() {
        val chunks = SceneAudioChunks.plan(listOf(r(10_000, 1_400_000)))
        assertEquals(listOf(r(10_000, 610_000), r(610_000, 1_210_000), r(1_210_000, 1_400_000)), chunks.flatMap { it.ranges })
        assertTrue(chunks.all { it.durationMs <= SceneAudioChunks.MAX_CHUNK_MS })
        assertTrue(SceneAudioChunks.plan(listOf(r(5, 5), r(9, 3))).isEmpty(), "empty ranges are not sent")
    }

    @Test fun chunkTimeMapsBackToMovieTimeInsideItsScene() {
        val chunk = SceneAudioChunks.plan(listOf(r(30_000, 40_000), r(90_000, 95_000))).single()
        val (a, b) = chunk.scenes
        assertEquals(31_500, a.toMovieMs(1_500))
        assertEquals(92_000, b.toMovieMs(12_000))
        assertEquals(95_000, b.toMovieMs(20_000), "kept inside the scene")
        assertEquals(90_000, b.toMovieMs(9_000), "kept inside the scene")
    }

    @Test fun itemsNameTheMovieAndRangesAndReadBack() {
        val movie = RecapMovie("source/movie.mp4", 123_456, 7_200_000)
        val chunk = SceneAudioChunks.plan(listOf(r(1_000, 4_000), r(10_000, 12_500))).single()
        val item = SceneAudioChunks.item(SceneAudioChunks.movieKey(movie), chunk)
        assertEquals("m123456-7200000:1000-4000,10000-12500", item)
        assertEquals(SceneAudioChunks.movieKey(movie) to chunk, SceneAudioChunks.parseItem(item))
        listOf("b_123", "m:1-2", "m1-2:", "m1-2:5-3", "m1-2:a-b", "m1-2:-5-9").forEach { assertNull(SceneAudioChunks.parseItem(it), it) }
    }

    @Test fun onlyPickedScenesWithoutSubtitleLinesOrTranscriptAreSent() {
        val recap = RecapV2(
            movie = RecapMovie("m.mp4", 1, 60_000),
            scenes = listOf(
                RecapScene(0, 10_000, picked = true), // has an .srt line
                RecapScene(10_000, 20_000, picked = true), // already transcribed
                RecapScene(20_000, 30_000, picked = false), // not picked
                RecapScene(30_000, 40_000, picked = true), // to send
                RecapScene(40_000, 50_000, picked = true), // to send
            ),
        )
        val transcript = Transcript(source = Transcript.SOURCE_GEMINI_SCENES, scenes = listOf(r(10_000, 20_000)))
        val hi = SrtCue(9_000, 9_999, "hi")
        assertEquals(listOf(30_000L, 40_000L), SceneAudioChunks.toTranscribe(recap, listOf(hi, SrtCue(50_000, 51_000, "after")), transcript).map { it.startMs })
        assertEquals(listOf(40_000L), SceneAudioChunks.toTranscribe(recap, listOf(hi, SrtCue(33_000, 33_500, "inside")), transcript).map { it.startMs })
        assertEquals(
            listOf(40_000L),
            SceneAudioChunks.toTranscribe(recap, listOf(hi, SrtCue(39_000, 40_000, "edge")), transcript).map { it.startMs },
            "a line ending where a scene starts belongs to the scene before",
        )
        assertEquals(listOf(0L, 10_000L, 30_000L, 40_000L), SceneAudioChunks.toTranscribe(recap, emptyList(), null).map { it.startMs }, "no subtitles, no transcript: every picked scene")
    }
}
