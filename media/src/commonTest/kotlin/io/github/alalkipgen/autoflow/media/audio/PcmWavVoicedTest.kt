package io.github.alalkipgen.autoflow.media.audio

import kotlin.test.*

/** D-2 (specs/dubbing.md §4): a take is measured without its leading and trailing silence. */
class PcmWavVoicedTest {
    @Test fun voicedSpanSkipsSilenceAtBothEnds() {
        val rate = 1_000
        val samples = FloatArray(2_000) { i -> if (i in 300 until 1_500) (if (i % 2 == 0) 0.2f else -0.2f) else 0.001f }
        val wav = PcmWav.parse(PcmWav.encode(rate, samples))!!
        assertEquals(300L to 1_200L, wav.voicedMs())
        assertEquals(2_000L, wav.durationMs)
        assertNull(PcmWav(rate, FloatArray(500) { 0.005f }).voicedMs(), "all silence")
        assertEquals(0L to 2_000L, PcmWav(rate, FloatArray(2_000) { 0.5f }).voicedMs())
    }
}
