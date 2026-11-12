package io.github.alalkipgen.autoflow.core.workflow

import io.github.alalkipgen.autoflow.core.model.Shot
import kotlin.math.abs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Detected beats of the song (`.cache/beats.json`); [songSha256] ties them to the song file. */
@Serializable
@SerialName("SongBeats")
data class SongBeats(val songSha256: String, val bpm: Double, val beatsMs: List<Long>)

/**
 * "Snap cuts to beat" (Q-12, D-32): every cut moves to the nearest beat within [WINDOW_MS], unless that would make a
 * shot shorter than [MIN_SHOT_MS] (or shorter than it already was, for a shot that was shorter to begin with); a cut
 * without a usable beat stays. Cuts stay strictly increasing; the song end never moves.
 */
object BeatSnap {
    const val WINDOW_MS = 250L
    const val MIN_SHOT_MS = 2_000L

    /** [cuts]: inner cut points, strictly increasing inside (0, [endMs]). */
    fun cuts(cuts: List<Long>, beats: List<Long>, endMs: Long): List<Long> {
        val sorted = beats.sorted()
        val out = mutableListOf<Long>()
        cuts.forEachIndexed { i, cut ->
            val prev = out.lastOrNull() ?: 0L
            val next = cuts.getOrNull(i + 1) ?: endMs
            val minBefore = minOf(MIN_SHOT_MS, cut - (cuts.getOrNull(i - 1) ?: 0L))
            val minAfter = minOf(MIN_SHOT_MS, next - cut)
            val pick = sorted.filter { abs(it - cut) <= WINDOW_MS }
                .sortedBy { abs(it - cut) }
                .firstOrNull { b -> b - prev >= minBefore && next - b >= minAfter && b > prev && b < next }
            out += pick ?: cut
        }
        return out
    }

    /**
     * Shots re-timed from [timings] (number → start, duration: the storyboard version's unsnapped cuts) with
     * [beats], or exactly [timings] when [beats] is null. Shots not in [timings] keep their times.
     */
    fun retime(shots: List<Shot>, timings: Map<Int, Pair<Long, Long>>, beats: List<Long>?): List<Shot> {
        val ordered = timings.entries.sortedBy { it.value.first }
        if (ordered.isEmpty()) return shots
        val end = ordered.last().value.let { it.first + it.second }
        val inner = ordered.drop(1).map { it.value.first }
        val snapped = if (beats.isNullOrEmpty()) inner else cuts(inner, beats, end)
        val edges = listOf(ordered.first().value.first) + snapped + end
        val times = ordered.mapIndexed { i, e -> e.key to (edges[i] to edges[i + 1] - edges[i]) }.toMap()
        return shots.map { s -> times[s.number]?.let { (start, length) -> s.copy(startMs = start, durationMs = length) } ?: s }
    }
}
