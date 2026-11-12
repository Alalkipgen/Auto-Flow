package io.github.alalkipgen.autoflow.core.workflow

import io.github.alalkipgen.autoflow.core.model.Shot
import kotlin.test.Test
import kotlin.test.assertEquals

class BeatSnapTest {
    @Test
    fun cutsMoveToTheNearestBeatInsideTheWindow() {
        val beats = listOf(7_600L, 7_900L, 8_300L, 15_740L, 24_400L, 31_990L)
        assertEquals(
            listOf(7_900L, 16_000L, 24_000L, 31_990L),
            BeatSnap.cuts(listOf(8_000, 16_000, 24_000, 32_000), beats, endMs = 34_100),
            "8000 → 7900 (nearest); 15740 and 24400 are outside ±250 ms; 32000 → 31990",
        )
    }

    @Test
    fun shotsNeverGetShorterThanTwoSeconds() {
        // the last shot is 2.1 s; moving the last cut 200 ms later would leave 1.9 s
        assertEquals(listOf(8_000L), BeatSnap.cuts(listOf(8_000), listOf(8_200), endMs = 10_100))
        // a short shot (1.5 s, e.g. after a section snap) may not get shorter, but may get longer
        assertEquals(listOf(6_500L, 8_000L), BeatSnap.cuts(listOf(6_500, 8_000), listOf(7_800), endMs = 16_000), "would shorten the 1.5 s shot")
        assertEquals(listOf(6_500L, 8_200L), BeatSnap.cuts(listOf(6_500, 8_000), listOf(8_200), endMs = 16_000))
        assertEquals(listOf(6_300L, 8_000L), BeatSnap.cuts(listOf(6_500, 8_000), listOf(6_300), endMs = 16_000))
        assertEquals(listOf(6_500L, 8_000L), BeatSnap.cuts(listOf(6_500, 8_000), listOf(6_700), endMs = 16_000), "would shorten the 1.5 s shot")
    }

    @Test
    fun retimeUsesTheVersionTimingsAndKeepsTheEnd() {
        val shots = listOf(Shot(1, 0, 8_000), Shot(2, 8_100, 7_900), Shot(3, 16_000, 4_000))
        val timings = mapOf(1 to (0L to 8_000L), 2 to (8_000L to 8_000L), 3 to (16_000L to 4_000L))
        assertEquals(
            listOf(Shot(1, 0, 7_850), Shot(2, 7_850, 8_150), Shot(3, 16_000, 4_000)),
            BeatSnap.retime(shots, timings, listOf(7_850, 16_400)),
        )
        assertEquals(listOf(Shot(1, 0, 8_000), Shot(2, 8_000, 8_000), Shot(3, 16_000, 4_000)), BeatSnap.retime(shots, timings, null), "off = the version's cuts")
    }
}
