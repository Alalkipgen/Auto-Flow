package io.github.alalkipgen.autoflow.ui.media

import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.media.render.Clip
import io.github.alalkipgen.autoflow.media.render.Output
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.render.RenderGraph
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.ProcessRunner
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Audit F16 with a real ffmpeg and real files: cancelling a running desktop render removes its own
 * `<name>.part.mp4`, keeps the earlier finished `<name>.mp4` (a real, playable video) byte for byte, and leaves no
 * ffmpeg running. `AUTOFLOW_EXPECT_FFMPEG=1` (CI) turns a missing ffmpeg into a failure, otherwise it is skipped.
 */
class RealRenderCancelTest {
    private fun works(bin: String) = runCatching {
        val p = ProcessBuilder(bin, "-hide_banner", "-version").redirectErrorStream(true).start()
        p.inputStream.readAllBytes(); p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    private val ffmpeg: String? by lazy { listOfNotNull(System.getenv("AUTOFLOW_FFMPEG"), "ffmpeg").firstOrNull(::works) }
    private val ffprobe: String? by lazy {
        listOfNotNull(System.getenv("AUTOFLOW_FFPROBE"), ffmpeg?.let { File(File(it).parentFile ?: File("."), "ffprobe").path }, "ffprobe")
            .firstOrNull(::works)
    }

    /** The desktop runner (app-desktop DesktopToolRunner) over [ProcessRunner]. */
    private val tools = ToolRunner { cmd, wd, timeout, keep, onLine ->
        when (val r = ProcessRunner.run(cmd, File(wd), timeout, keep, onLine)) {
            is ProcessRunner.Exec.Done -> ToolExec.Done(r.exitCode, r.output, r.tail, r.wallMs, r.timedOut)
            is ProcessRunner.Exec.CannotStart -> ToolExec.CannotStart(r.message)
        }
    }

    private fun sh(vararg cmd: String, dir: File): String {
        val p = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "timed out: ${cmd.joinToString(" ")}")
        assertEquals(0, p.exitValue(), "${cmd.joinToString(" ")}\n$out")
        return out
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes())

    /** Only this test JVM's children. Windows does not expose ProcessHandle.Info.commandLine reliably;
     * there we identify the real ffmpeg executable (CI bypasses Chocolatey shims). Tests run serially.
     * Both platforms still require exactly one live encoder before cancel and none after it.
     */
    private fun ffmpegsFor(marker: String): Long = ProcessHandle.current().descendants().use { children ->
        val windows = System.getProperty("os.name").startsWith("Windows")
        children.filter { child -> child.isAlive && if (windows) {
            File(child.info().command().orElse("")).name.equals("ffmpeg.exe", ignoreCase = true)
        } else child.info().commandLine().orElse("").let { c -> "ffmpeg" in c && marker in c } }.count()
    }

    @Test
    fun cancellingRealDesktopRenderMustCleanPartialFile() = runBlocking {
        val ff = ffmpeg; val fp = ffprobe
        if (ff == null || fp == null) {
            if (System.getenv("AUTOFLOW_EXPECT_FFMPEG") == "1") fail("AUTOFLOW_EXPECT_FFMPEG=1 but no ffmpeg / ffprobe")
            throw org.junit.AssumptionViolatedException("RealRenderCancelTest not executed: no ffmpeg / ffprobe")
        }
        val dir = Files.createTempDirectory("render-cancel").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            sh(ff, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "testsrc2=s=320x180:r=24:d=2",
                "-f", "lavfi", "-i", "sine=f=440:sample_rate=48000:d=2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
                "-shortest", "movie.mp4", dir = dir)
            val locator = object : ToolLocator {
                override fun findFfmpeg() = ff
                override fun findFfprobe() = fp
                override fun findVlc(): String? = null
                override fun isVlcDir(dir: String) = false
            }
            val fs = JvmFs()
            val q = InProcessRenderQueue(fs, MediaTools(SettingsStore(fs, File(dir, "settings.json").path), locator, tools), scope)
            val output = Output(Ratio.R16_9, 640, 360, 24, Quality.PREVIEW, "veryfast")
            fun job(ms: Long) = RenderJob(
                title = "cancel", graph = RenderGraph(clips = listOf(Clip(null, trimOutMs = ms, source = File(dir, "movie.mp4").path)), output = output),
                workDir = dir.path, files = emptyMap(), fontsDir = dir.path, outputName = "exports/cancel-test",
            )
            val good = File(dir, "exports/cancel-test.mp4")
            val part = File(dir, "exports/cancel-test.part.mp4")

            // 1. A finished export (a real, playable video) — what the cancelled render must not touch.
            val first = q.enqueue(job(1_000))
            val done = withTimeout(60_000) { q.observe(first).first { it.finished } }
            assertIs<RenderState.Done>(done, done.toString())
            val goodSha = sha(good)
            val goodDuration = sh(fp, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", good.path, dir = dir).trim().toDouble()
            assertEquals(1.0, goodDuration, 0.15, "the earlier export plays for 1 s")
            assertFalse(part.exists())

            // 2. A 20-minute render, cancelled once ffmpeg has written into its part file.
            val second = q.enqueue(job(1_200_000))
            withTimeout(30_000) { while (!part.exists() || part.length() < 32) delay(25) }
            val partBefore = part.length()
            assertEquals(1L, ffmpegsFor(part.path), "one ffmpeg writes the part file")
            q.cancel(second)
            withTimeout(15_000) { while (part.exists() || ffmpegsFor(part.path) > 0) delay(25) }
            delay(300) // nothing comes back afterwards
            val state = q.observe(second).first()
            println("F16 real cancel: state=$state; partBytesBefore=$partBefore; partExistsAfter=${part.exists()}; " +
                "ffmpegLeft=${ffmpegsFor(part.path)}; goodSameBytes=${goodSha.contentEquals(sha(good))}")
            assertEquals(RenderState.Cancelled, state)
            assertFalse(part.exists(), "a cancelled render leaves no .part.mp4")
            assertEquals(0L, ffmpegsFor(part.path), "no ffmpeg is left running")
            assertContentEquals(goodSha, sha(good), "the earlier export is untouched")
            assertEquals(goodDuration, sh(fp, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", good.path, dir = dir).trim().toDouble())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
