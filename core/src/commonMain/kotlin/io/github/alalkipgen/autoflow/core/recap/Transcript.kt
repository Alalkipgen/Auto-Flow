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

/** A movie range `[inMs, outMs)` that was transcribed (5B-1): its lines, if any, are in [Transcript.lines]. */
@Serializable
@SerialName("TranscriptRange")
data class TranscriptRange(val inMs: Long, val outMs: Long)

/**
 * Transcript artifact (`tx`) saved after Transcribe (5B-1, studio-v2.md §7).
 * [schemaVersion] = 2. Source = "subtitle" (extracted from track) or "gemini-scenes" (transcribed by AI).
 * [scenes] = the movie ranges already transcribed (also those without speech), so a scene is sent once; older
 * files without it read as none transcribed.
 */
@Serializable
@SerialName("Transcript")
data class Transcript(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** "subtitle" | "gemini-scenes" */
    val source: String,
    /** BCP-47 language tag, e.g. "my" for Burmese; "und" = the movie's own language, not known. */
    val lang: String = "my",
    val lines: List<TranscriptLine> = emptyList(),
    val scenes: List<TranscriptRange> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 2
        const val SOURCE_SUBTITLE = "subtitle"
        const val SOURCE_GEMINI_SCENES = "gemini-scenes"
        const val LANG_UNKNOWN = "und"
    }

    /** True when [inMs]..[outMs] lies inside the union of the transcribed [scenes] (a long scene may be in parts). */
    fun covers(inMs: Long, outMs: Long): Boolean {
        if (outMs <= inMs) return true
        var reach = inMs
        for (r in scenes.sortedBy { it.inMs }) {
            if (r.inMs > reach) break
            if (r.outMs > reach) reach = r.outMs
            if (reach >= outMs) return true
        }
        return reach >= outMs
    }

    /**
     * This transcript with [ranges] transcribed again as [newLines] (movie time): older lines whose middle lies in
     * one of [ranges] are replaced, the rest stay; lines sorted by time; [ranges] recorded once each.
     */
    fun withScenes(ranges: List<TranscriptRange>, newLines: List<TranscriptLine>): Transcript {
        val kept = lines.filter { l -> val mid = (l.startMs + l.endMs) / 2; ranges.none { mid >= it.inMs && mid < it.outMs } }
        return copy(
            lines = (kept + newLines).sortedWith(compareBy({ it.startMs }, { it.endMs })),
            scenes = (scenes + ranges).distinct().sortedWith(compareBy({ it.inMs }, { it.outMs })),
        )
    }

    /** The text of the lines overlapping [startMs]..[endMs], like `Srt.textIn` for `.srt` lines (≤ [maxChars]). */
    fun textIn(startMs: Long, endMs: Long, maxChars: Int = 600): String =
        Srt.textIn(lines.map { SrtCue(it.startMs, it.endMs, it.text) }, startMs, endMs, maxChars)

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
