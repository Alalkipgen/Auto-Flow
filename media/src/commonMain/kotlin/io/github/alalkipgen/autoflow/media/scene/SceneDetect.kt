package io.github.alalkipgen.autoflow.media.scene

import io.github.alalkipgen.autoflow.core.recap.Scenes
import io.github.alalkipgen.autoflow.media.render.FfmpegProgress
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner

/**
 * Movie scene cuts with ffmpeg's `scdet` filter (docs/specs/recap.md §3): decodes a 320 px proxy of the picture
 * only and reads the `lavfi.scd.time` log lines. Progress comes from `-progress pipe:1`.
 */
class SceneDetect(private val tools: ToolRunner, private val ffmpeg: String) {
    sealed interface Result {
        data class Ok(val cutsMs: List<Long>) : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun cuts(movie: String, durationMs: Long, threshold: Int, workDir: String, onProgress: (Double) -> Unit = {}): Result {
        val progress = FfmpegProgress(durationMs)
        val cuts = mutableListOf<Long>()
        val command = listOf(ffmpeg) + args(movie, threshold)
        val r = tools.run(command, workDir, timeoutFor(durationMs), keepOutput = false) { line ->
            Scenes.cutMs(line)?.let { cuts += it }
            progress.feed(line)?.let { onProgress(it.fraction) }
        }
        return when (r) {
            is ToolExec.CannotStart -> Result.Failed("ffmpeg cannot start: ${r.message}")
            is ToolExec.Done -> when {
                r.timedOut -> Result.Failed("Finding scenes took too long and was stopped")
                r.exitCode != 0 -> Result.Failed(r.tail.lastOrNull { it.isNotBlank() } ?: "ffmpeg exit ${r.exitCode}")
                else -> Result.Ok(cuts.distinct().sorted())
            }
        }
    }

    companion object {
        fun args(movie: String, threshold: Int) = listOf(
            "-hide_banner", "-nostdin", "-progress", "pipe:1", "-nostats", "-i", movie, "-map", "0:v:0", "-an", "-sn", "-dn",
            "-vf", "scale=320:-2,scdet=threshold=$threshold", "-f", "null", "-",
        )

        /** 10 min plus 2× the movie length (a slow phone decodes a 320 px proxy at well over real time). */
        fun timeoutFor(durationMs: Long): Long = 10 * 60_000L + 2 * durationMs
    }
}
