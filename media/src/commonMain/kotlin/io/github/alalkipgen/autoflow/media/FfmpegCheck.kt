package io.github.alalkipgen.autoflow.media

import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner

/** Result of the ffmpeg version check (PLAN 1D): what the binary can do, or why it could not be asked. */
sealed interface FfmpegCheck {
    data class Ok(val caps: FfmpegCapabilities) : FfmpegCheck

    data class Failed(val message: String) : FfmpegCheck

    companion object {
        /**
         * Runs `ffmpeg -version`, `-filters` and `-encoders` through [runner]. [workDir] must exist; nothing is
         * written there. A failing `-filters` / `-encoders` only leaves those sets empty.
         */
        suspend fun run(runner: ToolRunner, ffmpeg: String, workDir: String, timeoutMs: Long = 15_000): FfmpegCheck {
            suspend fun exec(arg: String) = runner.run(listOf(ffmpeg, "-hide_banner", arg), workDir, timeoutMs, keepOutput = true) {}
            val version = when (val v = exec("-version")) {
                is ToolExec.CannotStart -> return Failed("Cannot start ffmpeg: ${v.message}")
                is ToolExec.Done -> when {
                    v.timedOut -> return Failed("ffmpeg -version did not answer in ${timeoutMs / 1000} s")
                    v.exitCode != 0 -> return Failed("ffmpeg -version exited with ${v.exitCode}: ${v.tail.takeLast(2).joinToString(" / ")}")
                    else -> v.output
                }
            }
            val filters = (exec("-filters") as? ToolExec.Done)?.output.orEmpty()
            val encoders = (exec("-encoders") as? ToolExec.Done)?.output.orEmpty()
            val caps = FfmpegCapabilities.parse(version, filters, encoders)
            return if (caps.version == null) Failed("$ffmpeg does not look like ffmpeg (no version line)") else Ok(caps)
        }
    }
}
