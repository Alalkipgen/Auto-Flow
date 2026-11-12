package io.github.alalkipgen.autoflow.core.lyrics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One sung lyric line and when it is on screen (song time, ms). */
@Serializable
@SerialName("LyricLine")
data class LyricLine(val text: String, val startMs: Long, val endMs: Long)

/**
 * Line timings of the song (PLAN Q-11, D-30): stored as a `tx` artifact, pointed to by `project.lyricTiming`.
 * [source] is `tap` (tap-to-sync) or `gemini` ("Suggest timings").
 */
@Serializable
@SerialName("LyricTimings")
data class LyricTimings(
    val schemaVersion: Int = SCHEMA_VERSION,
    val source: String = SOURCE_TAP,
    val lines: List<LyricLine>,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val SOURCE_TAP = "tap"
        const val SOURCE_GEMINI = "gemini"
    }
}

object LyricSync {
    /** A line that is only a section marker, e.g. `[Chorus]` or `[Verse 2]`. */
    private val MARKER = Regex("^\\[[^\\]]*\\]$")

    /** The sung lines of [lyrics]: trimmed, without blank lines and section markers, in order. */
    fun lines(lyrics: String): List<String> =
        lyrics.lines().map { it.trim() }.filter { it.isNotEmpty() && !MARKER.matches(it) }

    /** True when [timings] were made for exactly the current [lyrics] lines (else the lyrics changed after sync). */
    fun matches(timings: LyricTimings, lyrics: String): Boolean = timings.lines.map { it.text } == lines(lyrics)
}

/**
 * Tap-to-sync state (immutable). Each [tap] marks the start of the next line at the player position; after the last
 * line one more tap marks where it ends. A tap closer than [MIN_GAP_MS] to the previous one is ignored (double tap).
 */
data class TapSync(val lines: List<String>, val starts: List<Long> = emptyList(), val lastEndMs: Long? = null) {
    /** Every line has a start. */
    val complete: Boolean get() = lines.isNotEmpty() && starts.size == lines.size

    /** The line whose start the next tap marks, or null when all are marked. */
    val nextLine: String? get() = lines.getOrNull(starts.size)

    /** The line being sung (last marked), or null before the first tap and after the end tap. */
    val currentLine: String? get() = if (lastEndMs != null) null else lines.getOrNull(starts.size - 1)

    fun tap(positionMs: Long): TapSync {
        val last = lastEndMs ?: starts.lastOrNull()
        if (last != null && positionMs < last + MIN_GAP_MS) return this
        return when {
            !complete -> copy(starts = starts + positionMs)
            lastEndMs == null -> copy(lastEndMs = positionMs)
            else -> this
        }
    }

    fun undo(): TapSync = when {
        lastEndMs != null -> copy(lastEndMs = null)
        else -> copy(starts = starts.dropLast(1))
    }

    fun restart(): TapSync = copy(starts = emptyList(), lastEndMs = null)

    /**
     * The saved lines. A line ends where the next starts, but stays at most [MAX_LINE_MS] on screen (instrumental
     * breaks); the last ends at the end tap, else after [LAST_LINE_MS] (cut at [songMs] when known).
     */
    fun timings(songMs: Long? = null): List<LyricLine> = starts.mapIndexed { i, start ->
        val end = when {
            i + 1 < starts.size -> minOf(starts[i + 1], start + MAX_LINE_MS)
            lastEndMs != null -> minOf(lastEndMs, start + MAX_LINE_MS)
            else -> minOf(start + LAST_LINE_MS, songMs?.takeIf { it > start } ?: Long.MAX_VALUE)
        }
        LyricLine(lines[i], start, end)
    }

    companion object {
        const val MIN_GAP_MS = 150L
        const val MAX_LINE_MS = 10_000L
        const val LAST_LINE_MS = 6_000L

        /** Starts from saved [timings] when they still match [lines] (to adjust them), else empty. */
        fun resume(lines: List<String>, timings: LyricTimings?): TapSync =
            if (timings != null && timings.lines.map { it.text } == lines) {
                TapSync(lines, timings.lines.map { it.startMs }, timings.lines.lastOrNull()?.endMs)
            } else {
                TapSync(lines)
            }
    }
}
