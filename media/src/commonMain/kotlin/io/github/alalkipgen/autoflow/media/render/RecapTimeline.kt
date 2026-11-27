package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.recap.SrtCue

/** One compiled movie interval. Identity is a story block ID + its clip ordinal, never a picked-scene index. */
data class RecapTimelineClip(
    val blockId: String,
    val clipOrdinal: Int,
    val outputFromMs: Long,
    val outputToMs: Long,
    val sourceFromMs: Long,
    val sourceRate: Float,
    val movieDurationMs: Long,
) {
    val key: String get() = "$blockId:$clipOrdinal"
    val durationMs: Long get() = outputToMs - outputFromMs
    /** The export holds the last movie frame when a fitted interval runs beyond EOF. */
    fun sourceAt(outputMs: Long): Long = (sourceFromMs + ((outputMs - outputFromMs).coerceIn(0, durationMs) * sourceRate).toLong())
        .coerceIn(0, (movieDurationMs - 1).coerceAtLeast(0))
    /** Only invert within THIS interval: repeated/disjoint source ranges are not globally invertible. */
    fun outputAt(sourceMs: Long): Long = (outputFromMs + ((sourceMs - sourceFromMs).coerceAtLeast(0) / sourceRate).toLong())
        .coerceIn(outputFromMs, outputToMs)
}

/** Shared explicit output timeline for player seeking, active block/clip, captions and native layers. */
data class RecapTimeline(val clips: List<RecapTimelineClip>, val captions: List<SrtCue>) {
    val durationMs: Long get() = clips.lastOrNull()?.outputToMs ?: 0
    fun clipAt(outputMs: Long): RecapTimelineClip? = clips.firstOrNull { outputMs >= it.outputFromMs && outputMs < it.outputToMs }
    fun captionsAt(outputMs: Long): List<SrtCue> = captions.filter { outputMs >= it.startMs && outputMs < it.endMs }
}
