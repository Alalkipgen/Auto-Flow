package io.github.alalkipgen.autoflow.media.audio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ffmpeg audio filter text for the Recap Audio step (5B-4, spec §2 `recap.audio`): voice polish, silence trim
 * (pause finder + cut, [SilenceTrim] runs them), background music (volume, loop, fades), ducking under the narration
 * and two-pass `loudnorm`. Pure strings so the render compiler and tests share one source; nothing here runs ffmpeg.
 */
object AudioFilters {
    const val DEFAULT_LUFS = -14.0
    const val TRUE_PEAK_DB = -1.5
    const val LRA = 11.0
    const val SILENCE_GAP_MS = 350L
    const val SILENCE_KEEP_MS = 200L
    const val SILENCE_THRESHOLD_DB = -40
    const val BGM_FADE_MS = 1_500L

    /** High-pass (rumble), FFT de-noise, gentle compressor. Order matters: clean before compressing. */
    fun voicePolish(): String =
        "highpass=f=80,afftdn=nf=-25,acompressor=threshold=-18dB:ratio=3:attack=5:release=80:makeup=2"

    /**
     * The pause finder for the silence trim (D-46 fix 1, studio-v2 §8): ffmpeg arguments (after the binary) whose
     * `silencedetect` log lists every pause of at least [gapMs] below [thresholdDb] in [input] ([parseSilences]).
     * Audio only, nothing is written.
     */
    fun silenceDetectArgs(input: String, gapMs: Long = SILENCE_GAP_MS, thresholdDb: Int = SILENCE_THRESHOLD_DB): List<String> =
        listOf("-hide_banner", "-nostdin", "-nostats", "-i", input, "-vn", "-af", "silencedetect=n=${thresholdDb}dB:d=${sec(gapMs)}", "-f", "null", "-")

    /** A pause found by `silencedetect`, in seconds of the input; [end] null = it runs to the end. */
    data class Silence(val start: Double, val end: Double?)

    private val SilenceStart = Regex("""silence_start:\s*(-?[0-9.]+)""")
    private val SilenceEnd = Regex("""silence_end:\s*(-?[0-9.]+)""")
    private val InputDuration = Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""")

    /** The pauses in a [silenceDetectArgs] log, in order; a start without an end runs to the end of the input. */
    fun parseSilences(log: String): List<Silence> {
        val out = mutableListOf<Silence>()
        var open: Double? = null
        for (line in log.lineSequence()) {
            SilenceStart.find(line)?.let { open = it.groupValues[1].toDoubleOrNull()?.coerceAtLeast(0.0) }
            SilenceEnd.find(line)?.let { m -> open?.let { s -> m.groupValues[1].toDoubleOrNull()?.let { out += Silence(s, it) }; open = null } }
        }
        open?.let { out += Silence(it, null) }
        return out
    }

    /** The input's length in seconds from ffmpeg's `Duration:` line, or null. */
    fun parseInputDuration(log: String): Double? = InputDuration.find(log)?.groupValues?.let { g ->
        g[1].toDouble() * 3600 + g[2].toDouble() * 60 + g[3].toDouble()
    }

    /**
     * The parts of the input to keep (seconds; end null = to the end): every pause longer than [gapMs] becomes
     * [keepMs] long — half after the speech before it, half before the speech after it; a pause at the start keeps
     * only its last [keepMs], one at the end (it reaches [totalSec], or has no end) only its first [keepMs]. Shorter
     * pauses and all speech stay as they are. One range `0 → end` = nothing to cut.
     */
    fun keptRanges(
        silences: List<Silence>,
        totalSec: Double? = null,
        gapMs: Long = SILENCE_GAP_MS,
        keepMs: Long = SILENCE_KEEP_MS,
    ): List<Pair<Double, Double?>> {
        require(gapMs > keepMs) { "gapMs must be longer than keepMs" }
        val gap = gapMs / 1000.0; val keep = keepMs / 1000.0
        val removed = mutableListOf<Pair<Double, Double?>>()
        for (s in silences) {
            val atEnd = s.end == null || (totalSec != null && s.end >= totalSec - 0.001)
            if (s.end != null && s.end - s.start <= gap + 1e-6) continue // up to the gap (float noise aside) stays
            when {
                s.start <= 0.001 && !atEnd -> removed += 0.0 to s.end - keep
                atEnd -> removed += s.start + keep to null
                else -> removed += s.start + keep / 2 to s.end - keep / 2
            }
        }
        val kept = mutableListOf<Pair<Double, Double?>>()
        var from = 0.0
        for ((a, b) in removed) {
            if (a > from) kept += from to a
            from = b ?: return kept
        }
        kept += from to null
        return kept
    }

    /**
     * ffmpeg arguments (after the binary) that write the [kept] ranges of [input] one after the other (sample exact
     * `atrim` + `concat`) as a mono 48 kHz 16-bit WAV [output] — the take's real new length.
     */
    fun cutArgs(input: String, output: String, kept: List<Pair<Double, Double?>>): List<String> {
        require(kept.isNotEmpty()) { "nothing to keep" }
        fun trim(r: Pair<Double, Double?>) = "atrim=start=${secs(r.first)}" + (r.second?.let { ":end=${secs(it)}" } ?: "") + ",asetpts=PTS-STARTPTS"
        val graph = if (kept.size == 1) "[0:a]${trim(kept[0])}[out]" else
            "[0:a]asplit=${kept.size}" + kept.indices.joinToString("") { "[k$it]" } + ";" +
                kept.indices.joinToString(";") { "[k$it]${trim(kept[it])}[c$it]" } + ";" +
                kept.indices.joinToString("") { "[c$it]" } + "concat=n=${kept.size}:v=0:a=1[out]"
        return listOf("-hide_banner", "-y", "-nostdin", "-i", input, "-filter_complex", graph, "-map", "[out]",
            "-c:a", "pcm_s16le", "-ar", "48000", "-ac", "1", output)
    }

    /**
     * Background music for a [totalMs] long video: looped (or padded with silence) to the full length, set to
     * [volume], faded in and out. Fades shrink to a third of the length for very short videos.
     */
    fun bgm(totalMs: Long, volume: Double, loop: Boolean = true, fadeMs: Long = BGM_FADE_MS): String {
        require(totalMs > 0) { "totalMs must be > 0" }
        val fade = minOf(fadeMs, totalMs / 3)
        val fill = if (loop) "aloop=loop=-1:size=2147483647," else "apad,"
        val fades = if (fade > 0) {
            ",afade=t=in:st=0:d=${sec(fade)},afade=t=out:st=${sec(totalMs - fade)}:d=${sec(fade)}"
        } else ""
        return "${fill}atrim=duration=${sec(totalMs)},asetpts=PTS-STARTPTS,volume=${num(volume)}$fades"
    }

    /**
     * Filter-graph chains that mix [voice] over [music] and, when [duck] is on, lower the music while the voice
     * speaks (sidechain). Labels are without brackets; the result ends in [out].
     */
    fun mixWithMusic(voice: String, music: String, out: String, duck: Boolean): List<String> = if (duck) {
        listOf(
            "[$voice]asplit=2[${voice}_mix][${voice}_key]",
            "[$music][${voice}_key]sidechaincompress=threshold=0.05:ratio=8:attack=20:release=300[${music}_ducked]",
            "[${voice}_mix][${music}_ducked]amix=inputs=2:duration=first:normalize=0[$out]",
        )
    } else {
        listOf("[$voice][$music]amix=inputs=2:duration=first:normalize=0[$out]")
    }

    /** `loudnorm` pass 1: measures only; ffmpeg prints a JSON block on stderr (see [parseMeasurement]). */
    fun loudnormMeasure(lufs: Double = DEFAULT_LUFS): String =
        "loudnorm=I=${num(lufs)}:TP=${num(TRUE_PEAK_DB)}:LRA=${num(LRA)}:print_format=json"

    /** `loudnorm` pass 2 with the pass-1 [m]easurement; `linear=true` keeps dynamics instead of compressing them. */
    fun loudnormApply(m: LoudnessMeasurement, lufs: Double = DEFAULT_LUFS): String =
        "loudnorm=I=${num(lufs)}:TP=${num(TRUE_PEAK_DB)}:LRA=${num(LRA)}" +
            ":measured_I=${num(m.inputI)}:measured_TP=${num(m.inputTp)}:measured_LRA=${num(m.inputLra)}" +
            ":measured_thresh=${num(m.inputThresh)}:offset=${num(m.targetOffset)}:linear=true"

    /** Single-pass fallback when pass 1 failed or was skipped. */
    fun loudnormSinglePass(lufs: Double = DEFAULT_LUFS): String =
        "loudnorm=I=${num(lufs)}:TP=${num(TRUE_PEAK_DB)}:LRA=${num(LRA)}"

    /**
     * Reads the last `{ … }` block of ffmpeg's stderr after [loudnormMeasure]. Null when it is missing, not JSON,
     * or a value is not a finite number (silent input gives `-inf`).
     */
    fun parseMeasurement(stderr: String): LoudnessMeasurement? {
        val end = stderr.lastIndexOf('}')
        if (end < 0) return null
        val start = stderr.lastIndexOf('{', end)
        if (start < 0) return null
        val obj: JsonObject = try {
            Json.parseToJsonElement(stderr.substring(start, end + 1)).jsonObject
        } catch (_: Exception) {
            return null
        }
        fun d(key: String): Double? = obj[key]?.jsonPrimitive?.content?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
        return LoudnessMeasurement(
            inputI = d("input_i") ?: return null,
            inputTp = d("input_tp") ?: return null,
            inputLra = d("input_lra") ?: return null,
            inputThresh = d("input_thresh") ?: return null,
            targetOffset = d("target_offset") ?: return null,
        )
    }

    private fun sec(ms: Long): String = "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"

    /** Seconds with six decimals (microseconds), never in exponent form. */
    private fun secs(v: Double): String {
        val us = kotlin.math.round(maxOf(0.0, v) * 1_000_000).toLong()
        return "${us / 1_000_000}.${(us % 1_000_000).toString().padStart(6, '0')}"
    }

    private fun num(v: Double): String {
        val r = kotlin.math.round(v * 1000) / 1000
        val s = r.toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }
}

/** `loudnorm` pass-1 numbers (LUFS / dBTP / LU). */
data class LoudnessMeasurement(
    val inputI: Double,
    val inputTp: Double,
    val inputLra: Double,
    val inputThresh: Double,
    val targetOffset: Double,
)
