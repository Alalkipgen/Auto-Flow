package io.github.alalkipgen.autoflow.android.render

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderJobsTest {
    private val dir = File(tempDir(), "render-jobs")
    private val jobs = RenderJobs(dir)

    @Test
    fun aWrittenJobReadsBackWithoutATempFile() {
        val id = newId()
        val job = testJob(dir)
        jobs.write(id, job)
        assertEquals(job, jobs.read(id))
        assertEquals(listOf("$id.json"), dir.list()!!.toList())
    }

    @Test
    fun aMissingOrForeignIdCannotBeRead() {
        assertFailsWith<Exception> { jobs.read(newId()) }
        assertFailsWith<Exception> { jobs.read("../../etc/passwd") }
        assertFalse(jobs.cancel("../x"))
        assertFalse(dir.exists() && dir.list()!!.isNotEmpty())
    }

    @Test
    fun aJobCancelledBeforeItStartsNeverBegins() {
        val id = newId()
        var stopped = false
        assertFalse(jobs.cancel(id))
        assertFalse(jobs.begin(id) { stopped = true })
        assertFalse(stopped)
        assertFalse(jobs.isRunning(id))
        // The marker is a file, so another process (or the next app start) sees it too.
        assertTrue(RenderJobs(dir).isCancelled(id))
    }

    @Test
    fun cancelStopsOnlyTheRunningJob() {
        val a = newId()
        val b = newId()
        var stoppedA = false
        var stoppedB = false
        assertTrue(jobs.begin(a) { stoppedA = true })
        assertTrue(jobs.begin(b) { stoppedB = true })
        assertTrue(jobs.cancel(a))
        assertTrue(stoppedA)
        assertFalse(stoppedB)
        assertFalse(jobs.isCancelled(b))
        jobs.end(a)
        assertFalse(jobs.isRunning(a))
        assertFalse(jobs.cancel(a)) // no longer running: marked only
    }

    @Test
    fun pruneKeepsWhatWorkManagerMayStillRunAndYoungFiles() {
        val active = newId()
        val running = newId()
        val young = newId()
        val old = newId()
        listOf(active, running, young, old).forEach { jobs.write(it, testJob(dir)) }
        jobs.cancel(old)
        jobs.begin(running) {}
        val now = System.currentTimeMillis()
        val long = now - RenderJobs.GRACE_MS - 1_000
        listOf(active, running, old).forEach { id -> dir.listFiles()!!.filter { it.name.startsWith(id) }.forEach { it.setLastModified(long) } }

        assertEquals(2, jobs.prune(setOf(active), now)) // old.json + old.cancel
        assertTrue(jobs.exists(active))
        assertTrue(jobs.exists(running))
        assertTrue(jobs.exists(young))
        assertFalse(jobs.exists(old))
        assertFalse(jobs.isCancelled(old))
    }

    @Test
    fun removeDeletesTheJobAndItsMarker() {
        val id = newId()
        jobs.write(id, testJob(dir))
        jobs.cancel(id)
        jobs.remove(id)
        assertFalse(jobs.exists(id))
        assertFalse(jobs.isCancelled(id))
    }
}
