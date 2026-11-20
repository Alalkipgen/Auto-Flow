package io.github.alalkipgen.autoflow.media.audio

import io.github.alalkipgen.autoflow.core.model.CleanChoice

/**
 * Clean up sound (specs/quick-tools.md §6, §9; V-2): Shorten long pauses · Reduce noise · Even loudness (−14 LUFS)
 * with the existing [AudioFilters]. One analysis pass over the whole input finds the pauses and measures the
 * loudness after noise reduction ([analyzeArgs] → [parseAnalysis]); then one command writes a result ([args]):
 * the first `previewMs` for Before / After, or the whole file as wav or m4a. Video input gives audio only.
 */
object CleanAudio {
    const val CLEAN_AUDIO = "clean-audio"

    /** What the analysis found: pauses in input seconds, the loudness (null when not asked or silent), the length. */
    data class Analysis(val silences: List<AudioFilters.Silence>, val loudness: LoudnessMeasurement?, val durationSec: Double?)

    /** ffmpeg arguments (after the binary) of the analysis; nothing is written. Null when [choice] needs none. */
    fun analyzeArgs(input: String, choice: CleanChoice, pauseMs: Long): List<String>? {
        val filters = buildList {
            if (choice.pauses) add("silencedetect=n=${AudioFilters.SILENCE_THRESHOLD_DB}dB:d=${secs(pauseMs / 1000.0)}")
            if (choice.loudness) {
                if (choice.noise) add(AudioFilters.voicePolish())
                add(AudioFilters.loudnormMeasure())
            }
        }
        if (filters.isEmpty()) return null
        return listOf("-hide_banner", "-nostdin", "-nostats", "-i", input, "-vn", "-af", filters.joinToString(","), "-f", "null", "-")
    }

    fun parseAnalysis(log: String): Analysis =
        Analysis(AudioFilters.parseSilences(log), AudioFilters.parseMeasurement(log), AudioFilters.parseInputDuration(log))

    /** The parts of the input kept by Shorten long pauses (one range `0 → end` = nothing to cut). */
    fun kept(analysis: Analysis, pauseMs: Long, keepMs: Long): List<Pair<Double, Double?>> =
        AudioFilters.keptRanges(analysis.silences, analysis.durationSec, pauseMs, keepMs)

    /** "Before": the first [limitMs] of [input] as it is, 48 kHz 16-bit wav at [output]. */
    fun beforeArgs(input: String, limitMs: Long, output: String): List<String> =
        listOf("-hide_banner", "-y", "-nostdin", "-t", secs(limitMs / 1000.0), "-i", input, "-vn", "-ar", "48000", "-c:a", "pcm_s16le", "-f", "wav", output)

    /**
     * The cleaned sound at [output] ([format] `wav` = 16-bit PCM, `m4a` = AAC at [bitrate]), 48 kHz, channels kept.
     * [kept] from [kept] when pauses are shortened; [loudness] = the analysis (two-pass), null = one-pass `loudnorm`.
     * [limitMs] = only the first part of the input (Before / After), its kept ranges cut to that length.
     */
    fun args(
        input: String,
        choice: CleanChoice,
        kept: List<Pair<Double, Double?>>?,
        loudness: LoudnessMeasurement?,
        format: String,
        bitrate: String,
        output: String,
        limitMs: Long? = null,
    ): List<String> {
        require(choice.any) { "nothing to clean" }
        val limit = limitMs?.let { it / 1000.0 }
        val chain = buildList {
            if (choice.noise) add(AudioFilters.voicePolish())
            if (choice.loudness) add(loudness?.let { AudioFilters.loudnormApply(it) } ?: AudioFilters.loudnormSinglePass())
            add("aresample=48000")
        }.joinToString(",")
        val ranges = kept?.takeIf { choice.pauses }?.let { clip(it, limit) }?.takeIf { !(it.size == 1 && it[0].first <= 0.0 && it[0].second == null) }
        fun trim(r: Pair<Double, Double?>) = "atrim=start=${secs(r.first)}" + (r.second?.let { ":end=${secs(it)}" } ?: "") + ",asetpts=PTS-STARTPTS"
        val graph = when {
            ranges == null -> "[0:a]$chain[out]"
            ranges.size == 1 -> "[0:a]${trim(ranges[0])},$chain[out]"
            else -> "[0:a]asplit=${ranges.size}" + ranges.indices.joinToString("") { "[k$it]" } + ";" +
                ranges.indices.joinToString(";") { "[k$it]${trim(ranges[it])}[c$it]" } + ";" +
                ranges.indices.joinToString("") { "[c$it]" } + "concat=n=${ranges.size}:v=0:a=1,$chain[out]"
        }
        val encode = if (format == "m4a") listOf("-c:a", "aac", "-b:a", bitrate, "-movflags", "+faststart", "-f", "mp4")
            else listOf("-c:a", "pcm_s16le", "-f", "wav")
        return listOf("-hide_banner", "-y", "-nostdin") + (limit?.let { listOf("-t", secs(it)) } ?: emptyList()) +
            listOf("-i", input, "-vn", "-filter_complex", graph, "-map", "[out]") + encode + output
    }

    /** `<name>-clean-<key>.<ext>`, the same for the same input and choice, so a finished result is reused. */
    fun fileName(source: String, choice: CleanChoice, sizeBytes: Long, ext: String): String {
        val base = source.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')
            .map { if (it.isLetterOrDigit() || it.code in 0x1000..0x109F) it else '-' }.joinToString("")
            .replace(Regex("-+"), "-").trim('-').ifEmpty { "sound" }.take(60)
        val key = (if (choice.pauses) "p" else "") + (if (choice.noise) "n" else "") + (if (choice.loudness) "l" else "")
        return "$base-clean-$key${sizeBytes.toString(36)}.$ext"
    }

    private fun clip(kept: List<Pair<Double, Double?>>, limit: Double?): List<Pair<Double, Double?>> {
        if (limit == null) return kept
        val inside = kept.map { (a, b) -> a to minOf(b ?: limit, limit) }.filter { (a, b) -> b - a > 0.001 }
        return inside.ifEmpty { listOf(0.0 to limit) }.map { (a, b) -> a to (if (b >= limit) null else b) }
    }

    /** Seconds with six decimals, never in exponent form. */
    private fun secs(v: Double): String {
        val us = kotlin.math.round(maxOf(0.0, v) * 1_000_000).toLong()
        return "${us / 1_000_000}.${(us % 1_000_000).toString().padStart(6, '0')}"
    }
}
