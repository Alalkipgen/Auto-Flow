package io.github.alalkipgen.autoflow.media.audio

import io.github.alalkipgen.autoflow.core.recap.AudioChunk
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner

/**
 * The sound of one Transcribe request (5B-1, D-44, spec §7): only the movie ranges of [AudioChunk] — never the
 * picture, never the whole movie — cut (`-ss <in> -t <length>` per range), joined in order and encoded as mono
 * 16 kHz AAC at 32 kbit/s (`.m4a`, ≈ 2.4 MB for the 10-minute maximum).
 */
class SceneAudio(private val tools: ToolRunner, private val ffmpeg: String) {
    /** Writes [out]; null = done, else the reason. */
    suspend fun cut(movie: String, chunk: AudioChunk, out: String, workDir: String): String? {
        if (chunk.scenes.isEmpty()) return "no scenes"
        val timeout = 120_000L + chunk.durationMs
        return when (val r = tools.run(listOf(ffmpeg) + args(movie, chunk, out), workDir, timeout, keepOutput = false) {}) {
            is ToolExec.CannotStart -> "ffmpeg cannot start: ${r.message}"
            is ToolExec.Done -> when {
                r.timedOut -> "ffmpeg took longer than ${timeout / 1000} s"
                r.exitCode != 0 -> r.tail.lastOrNull { it.isNotBlank() } ?: "ffmpeg exit ${r.exitCode}"
                else -> null
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BITRATE = "32k"
        const val MIME = "audio/mp4"

        fun args(movie: String, chunk: AudioChunk, out: String): List<String> = buildList {
            addAll(listOf("-hide_banner", "-y", "-nostdin"))
            chunk.scenes.forEach { s -> addAll(listOf("-ss", secs(s.inMs), "-t", secs(s.durationMs), "-i", movie)) }
            val n = chunk.scenes.size
            val graph = (0 until n).joinToString("") { "[$it:a:0]" } + "concat=n=$n:v=0:a=1[a]"
            addAll(listOf("-filter_complex", graph, "-map", "[a]", "-vn", "-sn", "-dn"))
            addAll(listOf("-ac", "1", "-ar", SAMPLE_RATE.toString(), "-c:a", "aac", "-b:a", BITRATE, "-f", "mp4", out))
        }

        /** Milliseconds → `s.mmm`. */
        fun secs(ms: Long): String = "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"
    }
}
