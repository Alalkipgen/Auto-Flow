package io.github.alalkipgen.autoflow.android.ai

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Audit F11 (Android): every AI job goes to the one "ai" chain (one at a time, one shared pacer) instead of a unique
 * work per job; finished jobs are not run again; a damaged job file is kept aside; cancel is a locked write.
 * WorkManager itself (and an OS process kill) is not exercised here: that needs a device.
 */
class WorkManagerAiJobRunnerTest {
    private val fs = InMemoryFileSystem()
    private val store = AiJobStore(fs, "/jobs")
    private val chain = mutableListOf<String>()

    private fun TestScope.runner() =
        WorkManagerAiJobRunner(store, { chain += it }, CoroutineScope(StandardTestDispatcher(testScheduler)))

    private fun job(id: String, kind: AiJobKind = AiJobKind.TTS_ALL, state: AiJobState = AiJobState.QUEUED) =
        AiJob(id, kind, "/projects/P.autoflow", items = listOf("b1", "b2"), state = state)

    @Test fun jobsOfEveryKindAreAppendedToTheOneChainInOrder() = runTest {
        val r = runner()
        r.enqueue(job("a"))
        r.enqueue(job("b", AiJobKind.TRANSCRIBE))
        r.enqueue(job("c"))
        assertEquals(listOf("a", "b", "c"), chain)
        assertEquals(AiJobState.QUEUED, store.load("b")!!.state)
    }

    @Test fun finishedJobsStayFinishedAndLeftOrStoppedOnesGoOn() = runTest {
        store.save(job("done", state = AiJobState.DONE))
        store.save(job("cancelled", state = AiJobState.CANCELLED))
        store.save(job("running", state = AiJobState.RUNNING))
        store.save(job("paused", state = AiJobState.PAUSED).copy(items = listOf("b2"), error = "busy"))
        val r = runner()
        r.enqueue(job("done"))
        r.enqueue(job("cancelled"))
        r.enqueue(job("running"))
        r.enqueue(job("paused"))
        assertEquals(listOf("running", "paused"), chain)
        assertEquals(AiJobState.DONE, store.load("done")!!.state)
        assertEquals(AiJobState.CANCELLED, store.load("cancelled")!!.state)
        val paused = store.load("paused")!!
        assertEquals(AiJobState.QUEUED, paused.state)
        assertEquals(listOf("b2"), paused.items, "resumes from its remaining items")
        assertEquals(null, paused.error)
    }

    @Test fun aDamagedJobFileIsKeptAsideNotOverwritten() = runTest {
        val damaged = "{\"id\":\"x\",trunc".encodeToByteArray()
        fs.createDirectories("/jobs")
        fs.writeDurably("/jobs/x.json", damaged)
        val id = runner().enqueue(job("x"))
        assertEquals("x-r", id)
        assertEquals(listOf("x-r"), chain)
        assertTrue(damaged.contentEquals(fs.read("/jobs/x.json")))
        assertEquals(listOf("x"), store.damaged())
    }

    @Test fun cancelMarksTheJobCancelledButNeverUndoesDone() = runTest {
        store.save(job("a", state = AiJobState.RUNNING))
        store.save(job("d", state = AiJobState.DONE))
        val r = runner()
        r.cancel("a")
        r.cancel("d")
        r.cancel("missing")
        assertEquals(AiJobState.CANCELLED, store.load("a")!!.state)
        assertEquals(AiJobState.DONE, store.load("d")!!.state)
        assertEquals(emptyList(), chain)
    }
}
