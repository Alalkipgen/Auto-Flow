package io.github.alalkipgen.autoflow.ui.jobs

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.recap.AiItemPaused
import io.github.alalkipgen.autoflow.core.recap.AiItemProcessor
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobExecutor
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.core.recap.RpmPacer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Audit F11 on the desktop runner: no processor is never Done; a job a closed app left RUNNING / QUEUED resumes
 * (on start and on enqueue) from its remaining items without redoing done ones; restart / resume / cancel again and
 * again; one job at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopAiJobRunnerTest {
    private val fs = InMemoryFileSystem()
    private val store = AiJobStore(fs, "/jobs")
    private val fast = RpmPacer(1_000)

    /** "The project": outputs made so far; [gate] holds an item until completed. */
    private val outputs = linkedSetOf<String>()
    private val calls = mutableListOf<String>()
    private var gate: Pair<String, CompletableDeferred<Unit>>? = null
    private val processor = object : AiItemProcessor {
        override suspend fun process(job: AiJob, item: String) {
            calls += "${job.id}:$item"
            gate?.takeIf { it.first == item }?.second?.await()
            outputs += "${job.id}:$item"
        }
        override suspend fun alreadyDone(job: AiJob, item: String) = "${job.id}:$item" in outputs
    }

    /** A runner in its own child scope of the test ("one run of the app"): cancel the scope = close the app. */
    private fun TestScope.app(p: AiItemProcessor? = processor): Pair<DesktopAiJobRunner, CoroutineScope> {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        return DesktopAiJobRunner(store, scope, p, pacer = { fast }) to scope
    }

    private fun job(id: String, vararg items: String, state: AiJobState = AiJobState.QUEUED) =
        AiJob(id, AiJobKind.TTS_ALL, "/projects/P.autoflow", items = items.toList(), state = state)

    @Test fun defaultDesktopProcessorMustNotReportUnprocessedJobDone() = runTest {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val runner = DesktopAiJobRunner(store, scope) // the public constructor: no processor given
        runner.enqueue(job("noop", "0"))
        advanceUntilIdle()
        val saved = store.load("noop")!!
        assertNotEquals(AiJobState.DONE, saved.state)
        assertEquals(AiJobState.FAILED, saved.state)
        assertEquals(AiJobExecutor.NO_PROCESSOR, saved.error)
        assertEquals(listOf("0"), saved.items)
        scope.cancel()
    }

    @Test fun restartedDesktopRunnerMustResumeRunningJob() = runTest {
        store.save(job("restart", "0", state = AiJobState.RUNNING))
        val (runner, scope) = app()
        runner.enqueue(job("restart", "0"))
        advanceUntilIdle()
        assertEquals(listOf("restart:0"), calls, "resumed once, not twice (start-up recovery + enqueue)")
        assertEquals(AiJobState.DONE, store.load("restart")!!.state)
        scope.cancel()
    }

    @Test fun onStartJobsLeftRunningOrQueuedResumeAndTheOthersWait() = runTest {
        store.save(job("running", "a", "b", state = AiJobState.RUNNING))
        store.save(job("queued", "x", state = AiJobState.QUEUED))
        store.save(job("paused", "p", state = AiJobState.PAUSED))
        store.save(job("cancelled", "c", state = AiJobState.CANCELLED))
        val (_, scope) = app()
        advanceUntilIdle()
        assertEquals(listOf("queued:x", "running:a", "running:b"), calls, "one at a time; PAUSED waits for Resume")
        assertEquals(AiJobState.DONE, store.load("running")!!.state)
        assertEquals(AiJobState.DONE, store.load("queued")!!.state)
        assertEquals(AiJobState.PAUSED, store.load("paused")!!.state)
        assertEquals(AiJobState.CANCELLED, store.load("cancelled")!!.state)
        scope.cancel()
    }

    /** Close the app mid-item, restart, close again, restart, cancel, restart: nothing redone, nothing skipped. */
    @Test fun repeatedRestartResumeAndCancel() = runTest {
        // Run 1: item a done, closed while b runs.
        gate = "b" to CompletableDeferred()
        val (r1, s1) = app()
        r1.enqueue(job("tts", "a", "b", "c", "d"))
        runCurrent()
        assertEquals(listOf("tts:a", "tts:b"), calls)
        s1.cancel()
        runCurrent()
        assertEquals(AiJobState.RUNNING, store.load("tts")!!.state, "the app closed: not cancelled, not failed")
        assertEquals(listOf("b", "c", "d"), store.load("tts")!!.items)

        // Run 2: resumes at b (b's output was never made), closed while c runs.
        gate = "c" to CompletableDeferred()
        val (_, s2) = app()
        runCurrent()
        assertEquals(listOf("tts:a", "tts:b", "tts:b", "tts:c"), calls)
        s2.cancel()
        runCurrent()
        assertEquals(listOf("c", "d"), store.load("tts")!!.items)

        // Run 3: resumes at c; the user cancels while c runs.
        gate = "c" to CompletableDeferred()
        val (r3, s3) = app()
        runCurrent()
        r3.cancel("tts")
        runCurrent()
        assertEquals(AiJobState.CANCELLED, store.load("tts")!!.state)
        assertEquals(listOf("tts:a", "tts:b", "tts:b", "tts:c", "tts:c"), calls)
        s3.cancel()

        // Run 4: a cancelled job stays cancelled, also when enqueued again.
        gate = null
        val (r4, s4) = app()
        r4.enqueue(job("tts", "a", "b", "c", "d"))
        advanceUntilIdle()
        assertEquals(AiJobState.CANCELLED, store.load("tts")!!.state)
        assertEquals(5, calls.size)
        assertEquals(setOf("tts:a", "tts:b"), outputs)
        s4.cancel()
    }

    /** b's output is saved but the app closes before the checkpoint: the restart checks b off without a request. */
    @Test fun partialCompletionIsReconciledFromTheProject() = runTest {
        store.save(job("tts", "b", "c", state = AiJobState.RUNNING)) // a's checkpoint saved, b's lost
        outputs += "tts:a"; outputs += "tts:b"
        val (_, scope) = app()
        advanceUntilIdle()
        assertEquals(listOf("tts:c"), calls, "only c is requested")
        assertEquals(AiJobState.DONE, store.load("tts")!!.state)
        scope.cancel()
    }

    @Test fun pausedResumesFromItsItem() = runTest {
        var busy = true
        val p = AiItemProcessor { _, item ->
            calls += item
            if (busy && item == "b") throw AiItemPaused("busy · Resume")
        }
        val (runner, scope) = app(p)
        runner.enqueue(job("tts", "a", "b", "c"))
        advanceUntilIdle()
        assertEquals(AiJobState.PAUSED, store.load("tts")!!.state)
        assertEquals(listOf("b", "c"), store.load("tts")!!.items)
        busy = false
        runner.enqueue(job("tts")) // Resume
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "b", "c"), calls)
        assertEquals(AiJobState.DONE, store.load("tts")!!.state)
        scope.cancel()
    }
}
