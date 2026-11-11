package io.github.alalkipgen.autoflow.android.render

import io.github.alalkipgen.autoflow.media.render.FfmpegProgress
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class RenderExecutionTest {
    private val root = tempDir()
    private val jobs = RenderJobs(File(root, "render-jobs"))
    private val deleted = mutableListOf<String>()

    private fun queued(): Pair<String, RenderJob> {
        val id = newId()
        val job = testJob(root)
        jobs.write(id, job)
        return id to job
    }

    private fun execution(render: suspend (RenderJob, (FfmpegProgress.Progress) -> Unit) -> RenderState) =
        RenderExecution(jobs, render) { deleted += it }

    @Test
    fun aDoneRenderKeepsItsOutputAndReportsProgress() = runTest {
        val (id, job) = queued()
        val seen = mutableListOf<Double>()
        var foregrounds = 0
        val out = execution { _, progress ->
            progress(FfmpegProgress.Progress(500, 0.5, 1.0, false))
            RenderState.Done("/x/test.mp4", 10, 1_000, null)
        }.run(id, foreground = { foregrounds++; true }, onProgress = { seen += it.fraction })
        assertEquals(RenderState.Done("/x/test.mp4", 10, 1_000, null), out.state)
        assertEquals(job, out.job)
        assertEquals(1, foregrounds)
        assertEquals(listOf(0.5), seen)
        assertTrue(deleted.isEmpty())
        assertFalse(jobs.isRunning(id))
    }

    @Test
    fun aJobCancelledWhileQueuedNeverRendersNorGoesForeground() = runTest {
        val (id, _) = queued()
        jobs.cancel(id)
        var rendered = false
        var foreground = false
        val out = execution { _, _ -> rendered = true; RenderState.Done("", 0, 0, null) }.run(id, foreground = { foreground = true; true })
        assertEquals(RenderState.Cancelled, out.state)
        assertFalse(rendered)
        assertFalse(foreground)
    }

    @Test
    fun cancelStopsTheRenderAndReturnsOnlyAfterTheProcessIsGone() = runTest {
        val (id, job) = queued()
        val started = CompletableDeferred<Unit>()
        var processAlive = false
        val run = async {
            execution { _, _ ->
                processAlive = true
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    // Like ProcessRunner: kill ffmpeg and wait until its output ends.
                    withContext(NonCancellable) { delay(200) }
                    processAlive = false
                }
            }.run(id, foreground = { true })
        }
        started.await()
        assertTrue(jobs.isRunning(id))
        assertTrue(jobs.cancel(id))
        val out = run.await()
        assertEquals(RenderState.Cancelled, out.state)
        assertFalse(processAlive, "the next render could start while ffmpeg still runs")
        assertEquals(listOf(RenderExecution.partPath(job)), deleted)
        assertFalse(jobs.isRunning(id))
    }

    @Test
    fun cancelDuringTheForegroundStartStopsBeforeRendering() = runTest {
        val (id, _) = queued()
        var rendered = false
        val out = execution { _, _ -> rendered = true; RenderState.Done("", 0, 0, null) }
            .run(id, foreground = { jobs.cancel(id); true })
        assertEquals(RenderState.Cancelled, out.state)
        assertFalse(rendered)
    }

    @Test
    fun aWorkerStoppedByWorkManagerRethrowsAndKeepsTheJobForTheRetry() = runTest {
        val (id, job) = queued()
        val started = CompletableDeferred<Unit>()
        val run = async {
            execution { _, _ -> started.complete(Unit); awaitCancellation() }.run(id, foreground = { true })
        }
        started.await()
        run.cancel()
        val thrown = runCatching { run.await() }.exceptionOrNull()
        assertIs<CancellationException>(thrown)
        assertTrue(jobs.exists(id))
        assertEquals(job, jobs.read(id))
        assertFalse(jobs.isRunning(id))
        assertEquals(listOf(RenderExecution.partPath(job)), deleted)
    }

    @Test
    fun aCrashBecomesAFailedRender() = runTest {
        val (id, _) = queued()
        val out = execution { _, _ -> error("boom") }.run(id, foreground = { true })
        assertEquals(RenderState.Failed("the render crashed: IllegalStateException: boom"), out.state)
        assertEquals(1, deleted.size)
    }

    @Test
    fun noForegroundServiceMeansNoRender() = runTest {
        val (id, _) = queued()
        var rendered = false
        val out = execution { _, _ -> rendered = true; RenderState.Done("", 0, 0, null) }.run(id, foreground = { false })
        assertIs<RenderState.Failed>(out.state)
        assertFalse(rendered)
        assertFalse(jobs.isRunning(id))
    }

    @Test
    fun aMissingJobFileFails() = runTest {
        val out = execution { _, _ -> RenderState.Done("", 0, 0, null) }.run(newId(), foreground = { true })
        assertTrue(assertIs<RenderState.Failed>(out.state).reason.startsWith("cannot read the render job"))
        assertEquals(null, out.job)
    }

    @Test
    fun thePartFileSitsNextToTheOutput() {
        val job = testJob(File("/p"))
        assertEquals("/p/exports/test.part.mp4", RenderExecution.partPath(job))
        assertEquals("/abs/out.part.mp4", RenderExecution.partPath(job.copy(outputName = "/abs/out")))
    }
}
