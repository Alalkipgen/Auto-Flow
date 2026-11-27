package io.github.alalkipgen.autoflow.core.subtitles

import io.github.alalkipgen.autoflow.core.recap.TranscriptLine
import io.github.alalkipgen.autoflow.core.recap.TranscriptRange
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One request of a whole-video transcription (D-80, specs/quick-tools.md §7): source time `[startMs, endMs)`.
 * Job item `k<startMs>-<endMs>` — the spec's key `k<startMs>` plus the end, so a resumed job needs nothing else to
 * cut the same sound; its saved lines are `.cache/jobs/<job>/k<startMs>.json`.
 */
data class MediaChunk(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
    val item: String get() = "k$startMs-$endMs"
    val file: String get() = "k$startMs.json"

    companion object {
        fun parse(item: String): MediaChunk? {
            if (!item.startsWith("k")) return null
            val (a, b) = item.drop(1).split('-').takeIf { it.size == 2 } ?: return null
            val start = a.toLongOrNull() ?: return null
            val end = b.toLongOrNull() ?: return null
            return if (start >= 0 && end > start) MediaChunk(start, end) else null
        }
    }
}

/**
 * What a MEDIA_TRANSCRIBE job was asked (written once at enqueue, `.cache/jobs/<job>/request.json`): the source video
 * it was planned for ([videoKey] = size-length, a new source fails the job), the spoken language hint and every chunk,
 * so the last saved chunk can join them all.
 */
@Serializable
@SerialName("MediaTranscribeRequest")
data class MediaTranscribeRequest(val videoKey: String, val lang: String, val chunks: List<TranscriptRange>) {
    val parts: List<MediaChunk> get() = chunks.map { MediaChunk(it.inMs, it.outMs) }
}

/** Planning and joining whole-video transcription chunks (spec §9 "Chunk cut points", D-80); pure. */
object MediaChunks {
    /** A pause found by `silencedetect`, source ms. */
    data class Pause(val startMs: Long, val endMs: Long)

    /**
     * Chunks of at most [maxMs] covering `[0, durationMs)`: each cut is the pause point nearest before the next
     * [maxMs] mark, no earlier than [windowMs] before it (a pause's middle, or the mark itself when the pause spans it);
     * with no pause there, a hard cut at the mark and the next chunk starts [overlapMs] earlier, so a word cut in two is
     * heard whole once.
     */
    fun plan(durationMs: Long, pauses: List<Pause>, maxMs: Long, windowMs: Long, overlapMs: Long): List<MediaChunk> {
        require(maxMs > 0 && overlapMs in 0 until maxMs && windowMs >= 0)
        if (durationMs <= 0) return emptyList()
        val out = mutableListOf<MediaChunk>()
        var start = 0L
        while (durationMs - start > maxMs) {
            val mark = start + maxMs
            val cut = pauses.filter { it.endMs > it.startMs && it.startMs <= mark }
                .map { minOf((it.startMs + it.endMs) / 2, mark) }
                .filter { it >= mark - windowMs && it > start }
                .maxOrNull()
            if (cut != null) { out += MediaChunk(start, cut); start = cut } else { out += MediaChunk(start, mark); start = mark - overlapMs }
        }
        out += MediaChunk(start, durationMs)
        return out
    }

    private fun overlapMs(a: TranscriptLine, b: TranscriptLine): Long = maxOf(0L, minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs))

    /**
     * The lines of every chunk (already in source time) as one list: kept inside their chunk, sorted, blank dropped.
     * In an overlap the same speech is heard twice — a later line sharing at least half of the shorter one's time with
     * earlier lines that reach into its chunk replaces them when its text is longer (the earlier one was cut), else it is dropped. Times stay increasing:
     * an end past the next line's start is moved back to it.
     */
    fun merge(parts: List<Pair<MediaChunk, List<TranscriptLine>>>): List<TranscriptLine> {
        val out = mutableListOf<TranscriptLine>()
        for ((chunk, lines) in parts.sortedBy { it.first.startMs }) {
            val inside = lines.mapNotNull { l ->
                val s = l.startMs.coerceIn(chunk.startMs, chunk.endMs)
                val e = l.endMs.coerceIn(chunk.startMs, chunk.endMs)
                if (e <= s || l.text.isBlank()) null else TranscriptLine(s, e, l.text.trim())
            }.sortedWith(compareBy({ it.startMs }, { it.endMs }))
            val earlier = out.filter { it.endMs > chunk.startMs }
            for (l in inside) {
                val same = earlier.filter { o -> o in out && 2 * overlapMs(o, l) >= minOf(o.endMs - o.startMs, l.endMs - l.startMs) }
                when {
                    same.isEmpty() -> out += l
                    l.text.length > same.sumOf { it.text.length } -> { out.removeAll(same); out += l }
                }
            }
        }
        val sorted = out.sortedWith(compareBy({ it.startMs }, { it.endMs }))
        return sorted.mapIndexed { i, l ->
            val next = sorted.getOrNull(i + 1)
            if (next != null && next.startMs > l.startMs && l.endMs > next.startMs) l.copy(endMs = next.startMs) else l
        }
    }
}
