package io.github.alalkipgen.autoflow.media.tool

/** Plays back [lines] and returns [result]; remembers every command it was asked to run. */
class FakeToolRunner(
    private val lines: List<String> = emptyList(),
    private val result: (List<String>) -> ToolExec = { ToolExec.Done(0, lines.joinToString("\n"), lines.takeLast(30), 1_234, false) },
) : ToolRunner {
    val commands = mutableListOf<List<String>>()
    val timeouts = mutableListOf<Long>()

    override suspend fun run(command: List<String>, workDir: String, timeoutMs: Long, keepOutput: Boolean, onLine: (String) -> Unit): ToolExec {
        commands += command
        timeouts += timeoutMs
        lines.forEach(onLine)
        return result(command)
    }
}
