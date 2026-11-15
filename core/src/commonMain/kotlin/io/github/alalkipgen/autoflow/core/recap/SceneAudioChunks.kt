package io.github.alalkipgen.autoflow.core.recap

/**
 * One movie range inside a transcription chunk (5B-1, spec §7): movie time `[inMs, outMs)`, starting at [offsetMs]
 * in the chunk's audio (the ranges are joined one after another).
 */
data class ChunkScene(val inMs: Long, val outMs: Long, val offsetMs: Long) {
    val durationMs: Long get() = outMs - inMs
    val endOffsetMs: Long get() = offsetMs + durationMs

    /** Chunk time → movie time, kept inside this range. */
    fun toMovieMs(chunkMs: Long): Long = inMs + (chunkMs - offsetMs).coerceIn(0, durationMs)
}

/** The audio of one Transcribe request: [scenes] joined in order (at most [SceneAudioChunks.MAX_CHUNK_MS]). */
data class AudioChunk(val scenes: List<ChunkScene>) {
    val durationMs: Long get() = scenes.lastOrNull()?.endOffsetMs ?: 0L
    val ranges: List<TranscriptRange> get() = scenes.map { TranscriptRange(it.inMs, it.outMs) }
}

/**
 * "Transcribe picked scenes" planning (5B-1, D-44, spec §7), pure: which picked scenes need a transcript, how their
 * ranges are packed into chunks of at most 10 minutes (one request each), and the job item of each chunk.
 *
 * Item = `m<movie key>:<in>-<out>,<in>-<out>…` (movie ms): a TRANSCRIBE job item names its ranges and the movie they
 * were cut from, so a resumed job works on exactly those ranges and refuses a movie that changed meanwhile.
 */
object SceneAudioChunks {
    /** Spec §7: chunks ≤ 10 min (≈ 2.4 MB at 32 kbit/s, inside the inline-request limit). */
    const val MAX_CHUNK_MS = 600_000L

    /** The picked scenes to transcribe: no `.srt` line inside and not already in [transcript]. */
    fun toTranscribe(recap: RecapV2, cues: List<SrtCue>, transcript: Transcript?): List<RecapScene> =
        recap.scenes.filter { s ->
            s.picked && s.endMs > s.startMs &&
                cues.none { it.endMs > s.startMs && it.startMs < s.endMs } &&
                transcript?.covers(s.startMs, s.endMs) != true
        }

    /** [ranges] in movie order, a range longer than [maxChunkMs] cut into parts, packed in order into chunks. */
    fun plan(ranges: List<TranscriptRange>, maxChunkMs: Long = MAX_CHUNK_MS): List<AudioChunk> {
        require(maxChunkMs > 0)
        val parts = ranges.filter { it.outMs > it.inMs }.sortedBy { it.inMs }.flatMap { r ->
            generateSequence(r.inMs) { it + maxChunkMs }.takeWhile { it < r.outMs }
                .map { start -> TranscriptRange(start, minOf(start + maxChunkMs, r.outMs)) }.toList()
        }
        val chunks = mutableListOf<AudioChunk>()
        var current = mutableListOf<ChunkScene>()
        var length = 0L
        for (p in parts) {
            val d = p.outMs - p.inMs
            if (current.isNotEmpty() && length + d > maxChunkMs) {
                chunks += AudioChunk(current)
                current = mutableListOf()
                length = 0L
            }
            current += ChunkScene(p.inMs, p.outMs, length)
            length += d
        }
        if (current.isNotEmpty()) chunks += AudioChunk(current)
        return chunks
    }

    /** Identity of the imported movie (size and length): a new import gives another key. */
    fun movieKey(movie: RecapMovie): String = "${movie.sizeBytes}-${movie.durationMs}"

    fun item(movieKey: String, chunk: AudioChunk): String =
        "m$movieKey:" + chunk.scenes.joinToString(",") { "${it.inMs}-${it.outMs}" }

    /** (movie key, chunk) of a job [item]; null when it is not one. */
    fun parseItem(item: String): Pair<String, AudioChunk>? {
        if (!item.startsWith("m")) return null
        val colon = item.indexOf(':').takeIf { it > 1 } ?: return null
        val key = item.substring(1, colon)
        var offset = 0L
        val scenes = item.substring(colon + 1).split(',').map { part ->
            val dash = part.indexOf('-')
            val inMs = part.take(maxOf(dash, 0)).toLongOrNull() ?: return null
            val outMs = part.substring(dash + 1).toLongOrNull() ?: return null
            if (inMs < 0 || outMs <= inMs) return null
            ChunkScene(inMs, outMs, offset).also { offset += it.durationMs }
        }
        return if (scenes.isEmpty()) null else key to AudioChunk(scenes)
    }
}
