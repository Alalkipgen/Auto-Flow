package io.github.alalkipgen.autoflow.media.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** [bpm] is the estimated tempo; [beatsMs] the beat times (song time). */
data class Beats(val bpm: Double, val beatsMs: List<Long>)

/**
 * Beat tracking for "Snap cuts to beat" (Q-12, D-32), in plain Kotlin (ffmpeg has no beat filter): spectral-flux
 * onset strength (Hann-windowed [FRAME] FFT, [HOP] hop, log magnitude, positive differences), tempo from the onset
 * autocorrelation (60–200 BPM, weighted towards 120 BPM), then dynamic-programming beat tracking (Ellis 2007:
 * each beat = onset strength + best earlier beat about one period back, penalised by `(ln(gap / period))²`).
 */
object BeatDetector {
    const val RATE = 11_025
    private const val FRAME = 1024
    private const val HOP = 256
    private const val TIGHTNESS = 100.0

    /** Null when the audio is too short (< 4 s) or silent. */
    fun detect(samples: FloatArray, rate: Int): Beats? {
        val onset = onsetStrength(samples)
        val fps = rate.toDouble() / HOP
        if (onset.size < (4 * fps).toInt() || onset.all { it == 0.0 }) return null
        val period = tempoPeriod(onset, fps) ?: return null
        val frames = track(onset, period)
        val offsetMs = FRAME / 2 * 1000.0 / rate
        return Beats(bpm = 60.0 * fps / period, beatsMs = frames.map { (it * 1000.0 / fps + offsetMs).toLong() })
    }

    /** Spectral flux per hop, mean-removed (local 1 s window) and clipped at 0, scaled to unit standard deviation. */
    internal fun onsetStrength(x: FloatArray): DoubleArray {
        val n = if (x.size < FRAME) 0 else (x.size - FRAME) / HOP + 1
        if (n < 2) return DoubleArray(0)
        val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / FRAME) }
        val half = FRAME / 2
        var prev = DoubleArray(half)
        val re = DoubleArray(FRAME)
        val im = DoubleArray(FRAME)
        val flux = DoubleArray(n)
        for (t in 0 until n) {
            val off = t * HOP
            for (i in 0 until FRAME) { re[i] = x[off + i] * window[i]; im[i] = 0.0 }
            fft(re, im)
            val cur = DoubleArray(half) { k -> ln(1.0 + 100.0 * sqrt(re[k] * re[k] + im[k] * im[k])) }
            if (t > 0) {
                var s = 0.0
                for (k in 0 until half) { val d = cur[k] - prev[k]; if (d > 0) s += d }
                flux[t] = s
            }
            prev = cur
        }
        val w = 22 // ≈ 0.5 s each side at 43 frames/s
        val out = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until minOf(n, w)) sum += flux[i]
        for (t in 0 until n) {
            if (t + w < n) sum += flux[t + w]
            if (t - w - 1 >= 0) sum -= flux[t - w - 1]
            val count = minOf(n - 1, t + w) - maxOf(0, t - w) + 1
            out[t] = maxOf(0.0, flux[t] - sum / count)
        }
        val mean = out.average()
        val sd = sqrt(out.sumOf { (it - mean) * (it - mean) } / n)
        if (sd > 0) for (t in 0 until n) out[t] /= sd
        return out
    }

    /** Beat period in frames: the autocorrelation lag with the best tempo-weighted score. */
    internal fun tempoPeriod(onset: DoubleArray, fps: Double): Double? {
        val minLag = (fps * 60 / 200).toInt().coerceAtLeast(1)
        val maxLag = (fps * 60 / 60).toInt().coerceAtMost(onset.size - 1)
        if (maxLag <= minLag) return null
        var best = -1.0
        var bestLag = -1
        val ac = DoubleArray(maxLag + 2)
        for (lag in minLag..minOf(maxLag + 1, onset.size - 1)) {
            var s = 0.0
            for (t in lag until onset.size) s += onset[t] * onset[t - lag]
            ac[lag] = s
        }
        for (lag in minLag..maxLag) {
            val bpm = 60 * fps / lag
            val octaves = ln(bpm / 120.0) / ln(2.0)
            val score = ac[lag] * exp(-0.5 * octaves * octaves)
            if (score > best) { best = score; bestLag = lag }
        }
        if (bestLag < 0 || best <= 0) return null
        // parabolic refinement between neighbours for a sub-frame period
        val l = bestLag
        val a = ac[l - 1].takeIf { l - 1 >= minLag } ?: ac[l]
        val c = ac[l + 1]
        val denom = a - 2 * ac[l] + c
        val shift = if (denom < 0) (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5) else 0.0
        return l + shift
    }

    /** Dynamic-programming beat frames, in order. */
    internal fun track(onset: DoubleArray, period: Double): List<Int> {
        val n = onset.size
        val score = DoubleArray(n)
        val back = IntArray(n) { -1 }
        val lo = (period / 2).toInt().coerceAtLeast(1)
        val hi = (period * 2).toInt()
        val firstLevel = 0.01 * (onset.maxOrNull() ?: 0.0)
        var started = false
        for (t in 0 until n) {
            var best = Double.NEGATIVE_INFINITY
            var arg = -1
            for (gap in lo..hi) {
                val p = t - gap
                if (p < 0) break
                val r = ln(gap / period)
                val v = score[p] - TIGHTNESS * r * r
                if (v > best) { best = v; arg = p }
            }
            // Until the first real onset, nothing chains back (leading silence has no beats).
            val chain = arg >= 0 && started
            score[t] = onset[t] + if (chain) best else 0.0
            back[t] = if (chain) arg else -1
            if (onset[t] > firstLevel) started = true
        }
        // last beat: the best score in the final period
        var t = (maxOf(0, n - period.toInt() - 1) until n).maxByOrNull { score[it] } ?: return emptyList()
        val beats = mutableListOf<Int>()
        while (t >= 0) {
            beats += t
            t = back[t]
        }
        return beats.reversed()
    }

    /** In-place radix-2 complex FFT; [re].size must be a power of two. */
    internal fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
