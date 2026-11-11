package io.github.alalkipgen.autoflow.desktop

import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.ProcessRunner
import io.github.alalkipgen.autoflow.platform.ProcessRunner.Exec
import java.io.File

/** The `:media` [ToolRunner] port over the platform [ProcessRunner] for the located ffmpeg (1D). */
class DesktopToolRunner : ToolRunner {
    override suspend fun run(command: List<String>, workDir: String, timeoutMs: Long, keepOutput: Boolean, onLine: (String) -> Unit): ToolExec =
        when (val r = ProcessRunner.run(command, File(workDir), timeoutMs, keepOutput, onLine)) {
            is Exec.Done -> ToolExec.Done(r.exitCode, r.output, r.tail, r.wallMs, r.timedOut)
            is Exec.CannotStart -> ToolExec.CannotStart(r.message)
        }
}
