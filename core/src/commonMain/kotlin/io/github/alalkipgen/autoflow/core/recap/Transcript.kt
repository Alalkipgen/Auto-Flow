package io.github.alalkipgen.autoflow.core.recap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subtitle track discovered via ffprobe (5B-1, studio-v2.md §7).
 * Text codecs: subrip, ass, ssa, mov_text, webvtt, text.
 * Picture codecs (hdmv_pgs_subtitle, dvd_subtitle, dvb_subtitle, xsub) → show message + offer Transcribe.
 */
@Serializable
@SerialName("SubtitleTrack")
data class SubtitleTrack(
    /** Stream index from ffprobe (0-based within all streams). */
    val index: Int,
    val codecName: String,
    val language: String? = null,
    val title: String? = null,
) {
    val isText: Boolean get() = codecName in TEXT_CODECS
    val isPicture: Boolean get() = codecName in PICTURE_CODECS

    companion object {
        val TEXT_CODECS = setOf("subrip", "ass", "ssa", "mov_text", "webvtt", "text")
        val PICTURE_CODECS = setOf("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "xsub")
    }
}

/**
 * One line of a transcript (5B-1, studio-v2.md §7).
 * [startMs] / [endMs] are in movie time.
 */
@Serializable
@SerialName("TranscriptLine")
data class TranscriptLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * Transcript artifact (`tx`) saved after Transcribe (5B-1, studio-v2.md §7).
 * [schemaVersion] = 2. Source = "subtitle" (extracted from track) or "gemini-scenes" (transcribed by AI).
 */
@Serializable
@SerialName("Transcript")
data class Transcript(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** "subtitle" | "gemini-scenes" */
    val source: String,
    /** BCP-47 language tag, e.g. "my" for Burmese. */
    val lang: String = "my",
    val lines: List<TranscriptLine> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 2
        const val SOURCE_SUBTITLE = "subtitle"
        const val SOURCE_GEMINI_SCENES = "gemini-scenes"
    }

    /**
     * Lines inside [startMs]..[endMs] (movie time), sorted by startMs.
     * Text length ≤ [maxChars] per scene (spec: 600 chars).
     */
    fun linesInRange(startMs: Long, endMs: Long, maxChars: Int = 600): List<TranscriptLine> {
        var total = 0
        return lines
            .filter { it.startMs >= startMs && it.endMs <= endMs }
            .sortedBy { it.startMs }
            .takeWhile { line ->
                total += line.text.length
                total <= maxChars
            }
    }
}
