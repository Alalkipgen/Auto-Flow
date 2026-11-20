package io.github.alalkipgen.autoflow.media.audio

import io.github.alalkipgen.autoflow.media.render.MediaTaskJob
import kotlin.math.roundToInt

/** Text → voice Save (specs/quick-tools.md §6, §9; V-1f): the takes in order with a gap, as one wav and an m4a. */
object VoiceJoin {
    const val VOICE_M4A = "voice-m4a"

    /**
     * The 16-bit mono WAV of [takes] (16-bit PCM WAVs, stereo averaged) one after another with [gapMs] of silence
     * between them; samples are copied exactly. Null when a take cannot be read or the sample rates differ.
     */
    fun join(takes: List<ByteArray>, gapMs: Long): ByteArray? {
        val wavs = takes.map { PcmWav.parse(it) ?: return null }
        val rate = wavs.firstOrNull()?.rate ?: return null
        if (wavs.any { it.rate != rate }) return null
        val gap = (rate * gapMs.coerceAtLeast(0) / 1000).toInt()
        val out = FloatArray(wavs.sumOf { it.samples.size } + gap * (wavs.size - 1))
        var at = 0
        wavs.forEachIndexed { i, w ->
            if (i > 0) at += gap
            w.samples.copyInto(out, at)
            at += w.samples.size
        }
        return encode(rate, out)
    }

    /** m4a (AAC) of the joined wav [source] (relative to the project folder), spec §9 "Sound only". */
    fun m4aArgs(source: String, bitrate: String): List<String> =
        listOf("-y", "-nostdin", "-i", source, "-vn", "-c:a", "aac", "-b:a", bitrate, "-movflags", "+faststart", "-f", "mp4", MediaTaskJob.PART)

    /** `<project>-voice-<id>.<ext>`: letters, digits and Burmese kept, the rest `-`. */
    fun fileName(project: String, joined: String, ext: String): String {
        val safe = project.map { if (it.isLetterOrDigit() || it.code in 0x1000..0x109F) it else '-' }.joinToString("")
            .replace(Regex("-+"), "-").trim('-').ifEmpty { "voice" }.take(60)
        return "$safe-voice-${joined.filter { it.isLetterOrDigit() }.takeLast(12)}.$ext"
    }

    /** [PcmWav.parse] reads `s / 32768`; this is its exact inverse, so joined samples keep their values. */
    private fun encode(rate: Int, samples: FloatArray): ByteArray {
        val wav = PcmWav.encode(rate, FloatArray(0))
        val out = wav.copyOf(44 + samples.size * 2)
        fun put32(at: Int, v: Int) { for (i in 0..3) out[at + i] = (v ushr (8 * i)).toByte() }
        put32(4, 36 + samples.size * 2); put32(40, samples.size * 2)
        samples.forEachIndexed { i, s ->
            val v = (s * 32768f).roundToInt().coerceIn(-32768, 32767)
            out[44 + i * 2] = v.toByte(); out[45 + i * 2] = (v ushr 8).toByte()
        }
        return out
    }
}
