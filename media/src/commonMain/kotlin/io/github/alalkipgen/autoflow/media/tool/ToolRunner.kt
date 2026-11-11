package io.github.alalkipgen.autoflow.media.tool

/**
 * Port for starting ffmpeg / ffprobe (ARCHITECTURE.md: `:media` depends on `:core` only). The apps bind it to
 * `:platform` `ProcessRunner` (desktop: the located binary, Android: the bundled one, D-23); tests use a fake.
 * Cancelling the calling coroutine must kill the process.
 */
fun interface ToolRunner {
    /** [onLine] gets every line of the merged stdout + stderr (`\r` also ends a line). */
    suspend fun run(command: List<String>, workDir: String, timeoutMs: Long, keepOutput: Boolean, onLine: (String) -> Unit): ToolExec
}

sealed interface ToolExec {
    /** [output] is the whole output when asked for, else empty; [tail] the last lines. */
    data class Done(val exitCode: Int, val output: String, val tail: List<String>, val wallMs: Long, val timedOut: Boolean) : ToolExec

    /** The binary could not be started (missing, not executable, refused by the OS). */
    data class CannotStart(val message: String) : ToolExec
}
