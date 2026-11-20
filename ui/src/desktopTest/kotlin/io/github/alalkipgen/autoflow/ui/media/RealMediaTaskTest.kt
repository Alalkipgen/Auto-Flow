package io.github.alalkipgen.autoflow.ui.media

import io.github.alalkipgen.autoflow.media.render.MediaTaskJob
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.ProcessRunner
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.jobs.JobSource
import io.github.alalkipgen.autoflow.ui.jobs.jobRows
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** S-4b: a [MediaTaskJob] on the desktop queue with a real ffmpeg — progress, Done, a Jobs row, Cancel leaves no file. */
class RealMediaTaskTest {
    private fun works(bin: String) = runCatching {
        val p = ProcessBuilder(bin, "-hide_banner", "-version").redirectErrorStream(true).start()
        p.inputStream.readAllBytes(); p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    private val ffmpeg: String? by lazy { listOfNotNull(System.getenv("AUTOFLOW_FFMPEG"), "ffmpeg").firstOrNull(::works) }

    private val tools = ToolRunner { cmd, wd, timeout, keep, onLine ->
        when (val r = ProcessRunner.run(cmd, File(wd), timeout, keep, onLine)) {
            is ProcessRunner.Exec.Done -> ToolExec.Done(r.exitCode, r.output, r.tail, r.wallMs, r.timedOut)
            is ProcessRunner.Exec.CannotStart -> ToolExec.CannotStart(r.message)
        }
    }

    private fun encode(seconds: Int, out: String) = MediaTaskJob(MediaTaskJob.SUBTITLE_BURN,
        listOf("-y", "-nostdin", "-f", "lavfi", "-i", "testsrc2=s=320x180:r=24:d=$seconds", "-c:v", "libx264", "-preset", "ultrafast",
            "-pix_fmt", "yuv420p", "-f", "mp4", MediaTaskJob.PART), out, seconds * 1_000L)

    @Test
    fun runsWithProgressAndCancelLeavesNoFile() = runBlocking {
        val ff = ffmpeg ?: run {
            if (System.getenv("AUTOFLOW_EXPECT_FFMPEG") == "1") fail("AUTOFLOW_EXPECT_FFMPEG=1 but no ffmpeg")
            throw org.junit.AssumptionViolatedException("RealMediaTaskTest not executed: no ffmpeg")
        }
        val dir = Files.createTempDirectory("media-task").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val locator = object : ToolLocator {
                override fun findFfmpeg() = ff
                override fun findFfprobe(): String? = null
                override fun findVlc(): String? = null
                override fun isVlcDir(dir: String) = false
            }
            val fs = JvmFs()
            val queue = InProcessRenderQueue(fs, MediaTools(SettingsStore(fs, File(dir, "settings.json").path), locator, tools), scope)
            val tasks = MediaTasks(queue, scope)

            val ok = tasks.start(encode(2, "exports/short.mp4"), dir.path, "Burn", "Subtitles")!!
            val seen = mutableListOf<RenderState>()
            withTimeout(60_000) { queue.observe(ok.id).first { seen += it; it.finished } }
            assertIs<RenderState.Done>(seen.last(), seen.last().toString())
            assertTrue(File(dir, "exports/short.mp4").length() > 0)
            assertFalse(File(dir, "exports/short.part.mp4").exists())
            withTimeout(5_000) { while (tasks.run(ok.id)?.state !is RenderState.Done) delay(10) }
            val row = jobRows(null, null, null, emptyList(), tasks = tasks.runs.value).single()
            assertEquals(JobSource.MEDIA, row.source)
            assertEquals(File(dir, "exports/short.mp4").path, row.output)
            assertEquals("Subtitles", row.projectScreen)

            val long = tasks.start(encode(1_200, "exports/long.mp4"), dir.path, "Burn", "Subtitles")!!
            val part = File(dir, "exports/long.part.mp4")
            withTimeout(30_000) { while (tasks.run(long.id)?.state !is RenderState.Running || !part.exists()) delay(25) }
            assertTrue(jobRows(null, null, null, emptyList(), tasks = tasks.runs.value).last().canCancel)
            tasks.cancel(long.id)
            withTimeout(15_000) { while (part.exists()) delay(25) }
            assertEquals(RenderState.Cancelled, queue.observe(long.id).first())
            assertFalse(File(dir, "exports/long.mp4").exists(), "a cancelled task leaves no output")
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
