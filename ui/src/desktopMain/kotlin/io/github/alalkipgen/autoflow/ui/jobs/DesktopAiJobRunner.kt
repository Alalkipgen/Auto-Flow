package io.github.alalkipgen.autoflow.ui.jobs

import io.github.alalkipgen.autoflow.core.recap.AiItemProcessor
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobExecutor
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiRunTiming
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.core.recap.RpmPacer
import io.github.alalkipgen.autoflow.core.recap.RpmPacers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Desktop (in-process) [AiJobRunner] (5A-5, studio-v2.md §5): the shared [AiJobExecutor] loop in [scope], one job
 * at a time in enqueue order (the provider limit is shared, [RpmPacers]).
 *
 * Audit F11:
 * - [processor] null → every job FAILS ("nothing can run this job"); nothing is ever reported DONE unprocessed.
 *   The app passes the project-bound TTS processor (`RecapJobProcessor`).
 * - Restart recovery: on start, jobs left QUEUED or RUNNING (the app was closed while they ran) run again from their
 *   remaining items; enqueue of such a job resumes it too. PAUSED / FAILED wait for Resume (= [enqueue] again);
 *   DONE / CANCELLED stay as they are. Items already done in the project are checked off without a request.
 * - [cancel] marks the job CANCELLED first, then stops its coroutine; the executor never overwrites CANCELLED.
 * - A busy locked model → PAUSED (PR #137); a coroutine cancel is rethrown, never turned into a state.
 *
 * [store] = `AppDirs.aiJobs` directory.
 */
class DesktopAiJobRunner internal constructor(
    private val store: AiJobStore,
    private val scope: CoroutineScope,
    private val processor: AiItemProcessor?,
    /** Tests: a pacer of their own (the shared one is per process). */
    private val pacer: (AiJob) -> RpmPacer,
) : AiJobRunner {
    constructor(store: AiJobStore, scope: CoroutineScope, processor: AiItemProcessor? = null) :
        this(store, scope, processor, { RpmPacers.forJob(it) })

    private val _jobs = MutableStateFlow<List<AiJob>>(emptyList())
    override val jobs: StateFlow<List<AiJob>> = _jobs.asStateFlow()

    /** Guards [running]. */
    private val mutex = Mutex()

    /** One job at a time. */
    private val serial = Mutex()

    /** Active coroutine per ai-job id. */
    private val running = mutableMapOf<String, Job>()

    init {
        scope.launch {
            refresh()
            recover()
        }
    }

    override suspend fun refresh() {
        _jobs.value = store.loadAll().filter { it.state != AiJobState.CANCELLED }
    }

    /** Starts every job a previous run of the app left QUEUED or RUNNING (not already running here). */
    suspend fun recover() {
        for (job in store.loadAll().filter { it.state == AiJobState.QUEUED || it.state == AiJobState.RUNNING }) {
            mutex.withLock { if (running[job.id]?.isActive != true) launchLocked(job.id) }
        }
    }

    /** Returns the id the job runs under: [job]'s, or `<id>-r` when a damaged file of that id is kept aside. */
    override suspend fun enqueue(job: AiJob): String {
        val id = mutex.withLock {
            if (running[job.id]?.isActive == true) return job.id
            val claim = store.prepareEnqueue(job)
            if (claim.shouldLaunch) launchLocked(claim.id)
            claim.id
        }
        refresh()
        return id
    }

    override suspend fun cancel(id: String) {
        AiRunTiming.forgetQueue(id)
        store.update(id) { if (it.state == AiJobState.DONE) it else it.copy(state = AiJobState.CANCELLED) }
        mutex.withLock { running.remove(id) }?.cancel()
        refresh()
    }

    /** Call with [mutex] held: registers the coroutine before it can run, so a job never runs twice at once. */
    private fun launchLocked(id: String) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                serial.withLock {
                    AiJobExecutor(store, processor, pacer, onChange = { _jobs.value = store.loadAll().filter { j -> j.state != AiJobState.CANCELLED } }).run(id)
                }
            } finally {
                val me = coroutineContext[Job]
                withContext(NonCancellable) {
                    mutex.withLock { if (running[id] === me) running.remove(id) }
                    refresh()
                }
            }
        }
        running[id] = job
        job.start()
    }
}
