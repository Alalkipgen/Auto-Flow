package io.github.alalkipgen.autoflow.media.audio

import io.github.alalkipgen.autoflow.core.recap.RecapAudio
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ffmpeg audio filter text for the Recap Audio step (5B-4, spec §2 `recap.audio`): voice polish, silence trim,
 * background music (volume, loop, fades), ducking under the narration and two-pass `loudnorm`.
 * Pure strings so the render compiler and tests share one source; nothing here runs ffmpeg.
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
     * Shortens every pause longer than [gapMs] to [keepMs] (D-46 fix 1). Leading silence is cut to [keepMs] too;
     * speech is never touched.
     */
    fun trimSilence(gapMs: Long = SILENCE_GAP_MS, keepMs: Long = SILENCE_KEEP_MS, thresholdDb: Int = SILENCE_THRESHOLD_DB): String {
        require(gapMs > keepMs) { "gapMs must be longer than keepMs" }
        val t = "${thresholdDb}dB"
        return "silenceremove=start_periods=1:start_duration=0:start_threshold=$t:start_silence=${sec(keepMs)}" +
            ":stop_periods=-1:stop_duration=${sec(gapMs)}:stop_threshold=$t:stop_silence=${sec(keepMs)}"
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

    /** The narration chain for one take as [RecapAudio] asks: polish, then silence trim (either may be off). */
    fun narration(audio: RecapAudio): String? =
        listOfNotNull(voicePolish().takeIf { audio.polish }, trimSilence().takeIf { audio.trimSilence })
            .joinToString(",").ifEmpty { null }

    private fun sec(ms: Long): String = "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"

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
