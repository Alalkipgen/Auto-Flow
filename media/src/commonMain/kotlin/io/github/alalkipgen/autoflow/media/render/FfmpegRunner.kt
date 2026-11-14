package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.media.FfmpegBenchmark
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner

/**
 * Runs one compiled render ([RenderCompiler.Compiled.Ok.args]) with progress (PLAN 1D / 1F): adds
 * `-progress pipe:1 -nostats -benchmark`, turns the output into [FfmpegProgress.Progress] and the end into an
 * [Outcome] value. Cancelling the caller stops ffmpeg (the [ToolRunner] contract).
 */
class FfmpegRunner(private val tools: ToolRunner, private val ffmpeg: String) {
    sealed interface Outcome {
        data class Rendered(val wallMs: Long, val maxRssKb: Long?) : Outcome

        /** ffmpeg ran and failed (or ran past the timeout); [tail] holds its last lines for the error screen / report. */
        data class Failed(val exitCode: Int, val timedOut: Boolean, val tail: List<String>) : Outcome

        data class CannotStart(val message: String) : Outcome
    }

    suspend fun render(
        args: List<String>,
        workDir: String,
        durationMs: Long,
        timeoutMs: Long = timeoutFor(durationMs),
        onProgress: (FfmpegProgress.Progress) -> Unit = {},
    ): Outcome {
        val progress = FfmpegProgress(durationMs)
        val log = ArrayDeque<String>()
        val command = listOf(ffmpeg, "-progress", "pipe:1", "-nostats", "-benchmark") + args
        val r = tools.run(command, workDir, timeoutMs, keepOutput = false) { line ->
            progress.feed(line)?.let(onProgress)
            // The progress blocks would push the real error lines out of the tail.
            if (!ProgressKey.containsMatchIn(line)) {
                log.addLast(line)
                if (log.size > TAIL_LINES) log.removeFirst()
            }
        }
        return when (r) {
            is ToolExec.CannotStart -> Outcome.CannotStart(r.message)
            is ToolExec.Done ->
                if (r.exitCode == 0 && !r.timedOut) {
                    Outcome.Rendered(r.wallMs, FfmpegBenchmark.maxRssKb(log.joinToString("\n")))
                } else {
                    Outcome.Failed(r.exitCode, r.timedOut, log.toList())
                }
        }
    }

    /**
     * Runs ffmpeg for a loudnorm pass-1 measurement (audio only, no output file): collects every stderr line
     * and returns them as a string so [io.github.alalkipgen.autoflow.media.audio.AudioFilters.parseMeasurement]
     * can extract the JSON block. Silently returns an empty string on error (the caller falls back to single-pass).
     */
    suspend fun measure(args: List<String>, workDir: String, durationMs: Long): String {
        val buf = StringBuilder()
        val command = listOf(ffmpeg, "-nostats") + args
        runCatching {
            tools.run(command, workDir, timeoutFor(durationMs), keepOutput = false) { buf.appendLine(it) }
        }
        return buf.toString()
    }

    companion object {
        /** 30 min plus 20× the output length: generous for slow phones, still ends a hung ffmpeg. */
        fun timeoutFor(durationMs: Long): Long = 30 * 60_000L + 20 * durationMs

        const val TAIL_LINES = 30

        private val ProgressKey = Regex("""^\s*[a-z_0-9]+=""")
    }
}
