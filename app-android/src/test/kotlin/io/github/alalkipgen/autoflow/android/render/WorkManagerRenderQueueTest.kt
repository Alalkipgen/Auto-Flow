package io.github.alalkipgen.autoflow.android.render

import io.github.alalkipgen.autoflow.media.render.RenderState
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** WorkManager as the queue sees it; it has no cancel at all — the queue must never cancel WorkManager work. */
private class FakeWork : RenderWork {
    val appended = mutableListOf<Pair<UUID, String?>>()
    val callbacks = mutableMapOf<UUID, (Throwable?) -> Unit>()
    val states = mutableMapOf<UUID, MutableStateFlow<RenderState?>>()
    var unfinished = emptySet<String>()

    fun state(id: String) = states.getOrPut(UUID.fromString(id)) { MutableStateFlow(null) }

    override fun append(id: UUID, openActivity: String?, done: (Throwable?) -> Unit) {
        appended += id to openActivity
        callbacks[id] = done
    }

    /** WorkManager stored it: it is queued (or [error]: it could not). */
    fun store(id: String, error: Throwable? = null) {
        if (error == null) state(id).value = RenderState.Queued
        callbacks.getValue(UUID.fromString(id))(error)
    }

    override fun observe(id: UUID): Flow<RenderState?> = states.getOrPut(id) { MutableStateFlow(null) }

    override suspend fun unfinished(): Set<String> = unfinished
}

class WorkManagerRenderQueueTest {
    private val root = tempDir()
    private val dir = File(root, "render-jobs")
    private val jobs = RenderJobs(dir)
    private val work = FakeWork()

    private fun TestScope.queue() = WorkManagerRenderQueue(jobs, work, "screen", CoroutineScope(StandardTestDispatcher(testScheduler)))

    @Test
    fun jobsAreSavedAndAppendedInOrder() = runTest {
        val q = queue()
        val a = q.enqueue(testJob(root, "a"))
        val b = q.enqueue(testJob(root, "b"))
        assertEquals(listOf(a, b), work.appended.map { it.first.toString() })
        assertEquals("screen", work.appended[0].second)
        assertEquals("a", jobs.read(a).title)
        assertEquals("b", jobs.read(b).title)
    }

    @Test
    fun aJobWorkManagerHasNotStoredYetIsQueued() = runTest {
        val q = queue()
        val id = q.enqueue(testJob(root))
        assertEquals(RenderState.Queued, q.observe(id).first())
    }

    @Test
    fun anEnqueueFailureIsReportedAndItsJobFileRemoved() = runTest {
        val q = queue()
        val id = q.enqueue(testJob(root))
        work.store(id, IOException("database full"))
        assertEquals(RenderState.Failed("could not queue the render: database full"), q.observe(id).first())
        assertFalse(jobs.exists(id))
    }

    @Test
    fun aJobFileThatCannotBeWrittenFailsWithoutReachingWorkManager() = runTest {
        root.resolve("render-jobs").writeText("not a directory")
        val q = queue()
        val id = q.enqueue(testJob(root))
        assertTrue((q.observe(id).first() as RenderState.Failed).reason.startsWith("cannot save the render job"))
        assertTrue(work.appended.isEmpty())
    }

    @Test
    fun cancellingAQueuedJobLeavesTheJobsBehindItQueued() = runTest {
        val q = queue()
        val running = q.enqueue(testJob(root, "running"))
        val cancelled = q.enqueue(testJob(root, "cancelled"))
        val behind = q.enqueue(testJob(root, "behind"))
        listOf(running, cancelled, behind).forEach { work.store(it) }
        jobs.begin(running) {}
        work.state(running).value = RenderState.Running(0.3, 1.0)

        q.cancel(cancelled)

        assertEquals(RenderState.Cancelled, q.observe(cancelled).first())
        assertEquals(RenderState.Queued, q.observe(behind).first())
        assertEquals(RenderState.Running(0.3, 1.0), q.observe(running).first())
        assertTrue(jobs.isCancelled(cancelled))
        assertFalse(jobs.isCancelled(behind))
    }

    @Test
    fun cancellingTheRunningJobStopsItAndShowsItsRealEnd() = runTest {
        val q = queue()
        val id = q.enqueue(testJob(root))
        work.store(id)
        var stopped = false
        jobs.begin(id) { stopped = true }
        work.state(id).value = RenderState.Running(0.5, null)

        q.cancel(id)

        assertTrue(stopped)
        // Still rendering until the worker has killed ffmpeg and cleaned up; then WorkManager reports the end.
        assertEquals(RenderState.Running(0.5, null), q.observe(id).first())
        jobs.end(id)
        work.state(id).value = RenderState.Cancelled
        assertEquals(RenderState.Cancelled, q.observe(id).first())
    }

    @Test
    fun aFinishedRenderWinsOverALateCancel() = runTest {
        val q = queue()
        val id = q.enqueue(testJob(root))
        work.store(id)
        work.state(id).value = RenderState.Done("/p/out.mp4", 1, 2, null)
        q.cancel(id)
        assertEquals(RenderState.Done("/p/out.mp4", 1, 2, null), q.observe(id).first())
    }

    @Test
    fun anIdWorkManagerDoesNotKnowFails() = runTest {
        val q = queue()
        assertEquals(RenderState.Failed(RenderData.UNKNOWN), q.observe(newId()).first())
        assertEquals(RenderState.Failed(RenderData.UNKNOWN), q.observe("not-an-id").first())
    }

    @Test
    fun oldJobFilesArePrunedOnceANewJobIsStored() = runTest {
        val q = queue()
        val old = newId()
        val retrying = newId()
        listOf(old, retrying).forEach { jobs.write(it, testJob(root)) }
        dir.listFiles()!!.forEach { it.setLastModified(System.currentTimeMillis() - RenderJobs.GRACE_MS - 1_000) }
        work.unfinished = setOf(retrying)

        val id = q.enqueue(testJob(root))
        advanceUntilIdle()
        assertTrue(jobs.exists(old), "nothing is pruned before WorkManager stored the new job")
        work.store(id)
        advanceUntilIdle()

        assertFalse(jobs.exists(old))
        assertTrue(jobs.exists(retrying))
        assertTrue(jobs.exists(id))
    }
}
