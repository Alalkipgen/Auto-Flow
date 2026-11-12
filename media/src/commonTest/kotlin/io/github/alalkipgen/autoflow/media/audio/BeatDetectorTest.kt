package io.github.alalkipgen.autoflow.media.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeatDetectorTest {
    private val rate = BeatDetector.RATE

    /** Kick-like clicks (decaying 60 Hz + noise burst) every [beatMs] from [firstMs], over a soft pad, for [seconds]. */
    private fun clicks(beatMs: Double, seconds: Int, firstMs: Double = 300.0, seed: Int = 1): FloatArray {
        val rnd = Random(seed)
        val out = FloatArray(rate * seconds)
        for (i in out.indices) out[i] = (0.05 * sin(2 * PI * 220 * i / rate)).toFloat()
        var t = firstMs
        while (t < seconds * 1000) {
            val start = (t * rate / 1000).toInt()
            for (k in 0 until rate / 10) {
                val i = start + k
                if (i >= out.size) break
                val env = exp(-k / (rate * 0.02))
                out[i] += (env * (0.6 * sin(2 * PI * 60 * k / rate) + 0.3 * (rnd.nextDouble() * 2 - 1))).toFloat()
            }
            t += beatMs
        }
        return out
    }

    private fun assertOnGrid(beats: Beats, beatMs: Double, firstMs: Double) {
        val inner = beats.beatsMs.filter { it in 2_000..(beats.beatsMs.last() - 1_000) }
        assertTrue(inner.size > 10, "found ${beats.beatsMs.size} beats")
        inner.forEach { b ->
            val phase = ((b - firstMs) % beatMs + beatMs) % beatMs
            val off = minOf(phase, beatMs - phase)
            assertTrue(off <= 40, "beat $b is $off ms off the ${beatMs} ms grid")
        }
    }

    @Test
    fun findsA120BpmClickTrack() {
        val beats = assertNotNull(BeatDetector.detect(clicks(500.0, 20), rate))
        assertEquals(120.0, beats.bpm, 3.0)
        assertOnGrid(beats, 500.0, 300.0)
    }

    @Test
    fun findsA92BpmClickTrack() {
        val ms = 60_000.0 / 92
        val beats = assertNotNull(BeatDetector.detect(clicks(ms, 24, firstMs = 1_000.0, seed = 7), rate))
        assertEquals(92.0, beats.bpm, 3.0)
        assertOnGrid(beats, ms, 1_000.0)
    }

    @Test
    fun silenceOrTooShortHasNoBeats() {
        assertNull(BeatDetector.detect(FloatArray(rate * 10), rate))
        assertNull(BeatDetector.detect(clicks(500.0, 2), rate))
    }

    @Test
    fun fftOfASineHasOnePeak() {
        val n = 64
        val re = DoubleArray(n) { sin(2 * PI * 5 * it / n) }
        val im = DoubleArray(n)
        BeatDetector.fft(re, im)
        val mags = DoubleArray(n / 2) { abs(re[it]) + abs(im[it]) }
        assertEquals(5, mags.indices.maxByOrNull { mags[it] })
    }

    @Test
    fun wavRoundTrip() {
        val samples = floatArrayOf(0f, 0.5f, -0.5f, 1f)
        val wav = assertNotNull(PcmWav.parse(PcmWav.encode(8_000, samples)))
        assertEquals(8_000, wav.rate)
        assertEquals(4, wav.samples.size)
        assertEquals(0.5f, wav.samples[1], 0.001f)
        assertNull(PcmWav.parse(byteArrayOf(1, 2, 3)))
    }
}
