package io.github.alalkipgen.autoflow.platform

import io.github.alalkipgen.autoflow.platform.ProcessRunner.Exec
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** Child processes are small single-file Java programs, so the test runs the same on Linux and Windows. */
class ProcessRunnerTest {
    private val dir: File = Files.createTempDirectory("af-process").toFile()
    private val java = File(System.getProperty("java.home"), "bin/" + if (File.separatorChar == '\\') "java.exe" else "java").path

    private fun program(name: String, body: String): List<String> {
        val f = File(dir, "$name.java")
        f.writeText("public class $name { public static void main(String[] a) throws Exception { $body } }")
        return listOf(java, f.path)
    }

    @Test
    fun linesEndAtCrOrLfAndProgressCollapsesInTheTail() = runBlocking {
        val cmd = program("Out", """System.out.print("frame=1\rframe=2\rframe=3\nbench: maxrss=10KiB\nlast"); System.out.flush(); System.exit(3);""")
        val lines = mutableListOf<String>()
        val r = assertIs<Exec.Done>(ProcessRunner.run(cmd, dir, 60_000, keepOutput = true) { lines += it })
        assertEquals(3, r.exitCode)
        assertEquals(false, r.timedOut)
        assertEquals(listOf("frame=1", "frame=2", "frame=3", "bench: maxrss=10KiB", "last"), lines)
        assertEquals(listOf("frame=3", "bench: maxrss=10KiB", "last"), r.tail)
        assertEquals("frame=1\nframe=2\nframe=3\nbench: maxrss=10KiB\nlast\n", r.output)
    }

    @Test
    fun aTimeoutKillsTheProcess() = runBlocking {
        val cmd = program("Sleep", "Thread.sleep(60000);")
        val r = assertIs<Exec.Done>(ProcessRunner.run(cmd, dir, timeoutMs = 3_000))
        assertTrue(r.timedOut)
        assertTrue(r.wallMs < 30_000, "took ${r.wallMs} ms")
    }

    @Test
    fun cancellingTheCallerKillsTheProcess() = runBlocking {
        val cmd = program("Sleep2", """System.out.println("started"); Thread.sleep(60000);""")
        val started = System.nanoTime()
        val job = async(Dispatchers.Default) { ProcessRunner.run(cmd, dir, timeoutMs = 120_000) }
        delay(3_000)
        job.cancel()
        runCatching { job.await() }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 30_000, "cancel did not stop the process")
    }

    @Test
    fun aMissingBinaryCannotStart() = runBlocking {
        val r = ProcessRunner.run(listOf(File(dir, "no-such-binary").path), dir, 5_000)
        assertTrue(assertIs<Exec.CannotStart>(r).message.startsWith("IOException"), r.toString())
    }

    @Test
    fun commandLineQuotesOnlyWhatNeedsIt() {
        assertEquals(
            "/x/libffmpeg.so -i /in/a.mp4 -filter_complex '[0:v]null[v]' 'it'\\''s'",
            ProcessRunner.commandLine(listOf("/x/libffmpeg.so", "-i", "/in/a.mp4", "-filter_complex", "[0:v]null[v]", "it's")),
        )
    }
}
