package io.github.alalkipgen.autoflow.platform

import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs an external binary (the bundled ffmpeg on Android, D-23; ffmpeg on desktop, 1D) with `ProcessBuilder`:
 * a refused exec and the exit code become values, never exceptions. stdout and stderr are merged.
 */
object ProcessRunner {
    sealed interface Exec {
        /** [output] is the whole output when asked for, else empty; [tail] the last lines (progress lines collapsed). */
        data class Done(val exitCode: Int, val output: String, val tail: List<String>, val wallMs: Long, val timedOut: Boolean) : Exec

        /** Android refused to start the binary (noexec mount, SELinux, missing file): the D-23 fallback trigger. */
        data class CannotStart(val message: String) : Exec
    }

    /** [onLine] runs on an IO thread for every line; cancelling the caller kills the process. */
    suspend fun run(
        command: List<String>,
        workDir: File,
        timeoutMs: Long,
        keepOutput: Boolean = false,
        onLine: (String) -> Unit = {},
    ): Exec = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val process = try {
            ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
                .also { it.environment()["TMPDIR"] = workDir.path; it.environment()["HOME"] = workDir.path }
                .start()
        } catch (e: IOException) {
            return@withContext Exec.CannotStart("${e::class.simpleName}: ${e.message}")
        } catch (e: SecurityException) {
            return@withContext Exec.CannotStart("SecurityException: ${e.message}")
        }
        val all = StringBuilder()
        val tail = ArrayDeque<String>()
        var timedOut = false
        coroutineScope {
            launch {
                readLines(process.inputStream) { line ->
                    if (keepOutput) all.append(line).append('\n')
                    val progress = line.startsWith("frame=") || line.startsWith("size=")
                    if (progress && tail.lastOrNull()?.let { it.startsWith("frame=") || it.startsWith("size=") } == true) tail.removeLast()
                    tail.addLast(line)
                    while (tail.size > TAIL_LINES) tail.removeFirst()
                    onLine(line)
                }
            }
            // Killing the process on cancel/timeout also ends the blocking read above, so this scope can finish.
            try {
                val deadline = System.nanoTime() + timeoutMs * 1_000_000
                while (process.isAlive) {
                    if (System.nanoTime() > deadline) {
                        timedOut = true
                        break
                    }
                    delay(POLL_MS)
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
        val code = process.waitFor()
        Exec.Done(code, all.toString(), tail.toList(), (System.nanoTime() - started) / 1_000_000, timedOut)
    }

    /** ffmpeg ends progress lines with `\r`, so both `\r` and `\n` end a line. */
    private fun readLines(input: InputStream, onLine: (String) -> Unit) {
        val reader = input.bufferedReader()
        val buf = CharArray(8192)
        val line = StringBuilder()
        while (true) {
            val n = try { reader.read(buf) } catch (_: IOException) { -1 }
            if (n < 0) break
            for (i in 0 until n) {
                val c = buf[i]
                if (c == '\n' || c == '\r') {
                    if (line.isNotEmpty()) { onLine(line.toString()); line.setLength(0) }
                } else {
                    line.append(c)
                }
            }
        }
        if (line.isNotEmpty()) onLine(line.toString())
    }

    /** A copy-pasteable shell line for the report. */
    fun commandLine(command: List<String>): String = command.joinToString(" ") { a ->
        if (a.isNotEmpty() && a.all { it.isLetterOrDigit() || it in "-_./:=+,@%" }) a else "'" + a.replace("'", "'\\''") + "'"
    }

    private const val TAIL_LINES = 30
    private const val POLL_MS = 200L
}
