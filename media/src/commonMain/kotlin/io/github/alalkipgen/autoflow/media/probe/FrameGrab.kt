package io.github.alalkipgen.autoflow.media.probe

import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner

/**
 * One PNG frame of a video (specs/render-graph.md `EndFrame.extract`): the last frame for Frames-to-Video shot
 * chaining, or the frame at a time for thumbnails. Storing the PNG as an `im` artifact is the caller's job.
 */
class FrameGrab(private val tools: ToolRunner, private val ffmpeg: String) {
    sealed interface Result {
        data object Ok : Result

        data class Failed(val reason: String) : Result
    }

    suspend fun last(video: String, png: String, workDir: String): Result = grab(lastArgs(video, png), workDir)

    suspend fun at(video: String, ms: Long, png: String, workDir: String): Result = grab(atArgs(video, ms, png), workDir)

    private suspend fun grab(args: List<String>, workDir: String): Result =
        when (val r = tools.run(listOf(ffmpeg) + args, workDir, TIMEOUT_MS, keepOutput = false) {}) {
            is ToolExec.CannotStart -> Result.Failed("ffmpeg cannot start: ${r.message}")
            is ToolExec.Done -> if (r.exitCode == 0 && !r.timedOut) Result.Ok else Result.Failed(r.tail.lastOrNull { it.isNotBlank() } ?: "ffmpeg exit ${r.exitCode}")
        }

    companion object {
        private const val TIMEOUT_MS = 60_000L

        /** Decode the last second and keep overwriting one PNG, so the file ends as the very last frame. */
        fun lastArgs(video: String, png: String) =
            listOf("-hide_banner", "-y", "-nostdin", "-sseof", "-1", "-i", video, "-map", "0:v:0", "-fps_mode", "passthrough", "-update", "1", png)

        fun atArgs(video: String, ms: Long, png: String) =
            listOf("-hide_banner", "-y", "-nostdin", "-ss", "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}", "-i", video, "-map", "0:v:0", "-frames:v", "1", png)
    }
}
