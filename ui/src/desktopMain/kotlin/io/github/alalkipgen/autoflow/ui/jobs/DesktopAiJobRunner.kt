package io.github.alalkipgen.autoflow.ui.jobs

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.core.recap.AiItemProcessor
import io.github.alalkipgen.autoflow.core.recap.RpmPacer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Desktop (in-process) [AiJobRunner] (5A-5, studio-v2.md §5).
 *
 * Runs jobs sequentially using coroutines in [scope]. Each job's items are processed one at a time
 * through [processor], paced by [RpmPacer]. Finished items are removed from the job's item list.
 * On cancel or failure the job is persisted as CANCELLED / FAILED before returning.
 *
 * [store] = `AppDirs.aiJobs` directory.
 * [processor] is injected by the DI module; a no-op default lets the runner be registered without
 * a concrete AI backend.
 */
class DesktopAiJobRunner(
    private val store: AiJobStore,
    private val scope: CoroutineScope,
    private val processor: AiItemProcessor = AiItemProcessor { _, _ -> },
) : AiJobRunner {

    private val _jobs = MutableStateFlow<List<AiJob>>(emptyList())
    override val jobs: StateFlow<List<AiJob>> = _jobs.asStateFlow()

    private val mutex = Mutex()
    /** Active coroutine job per ai-job id. */
    private val running = mutableMapOf<String, Job>()

    init {
        scope.launch { refresh() }
    }

    override suspend fun refresh() {
        _jobs.value = store.loadAll().filter { it.state != AiJobState.CANCELLED }
    }

    override suspend fun enqueue(job: AiJob): String {
        val toRun = mutex.withLock {
            val existing = store.load(job.id)
            val resolved = when {
                existing == null -> job.copy(state = AiJobState.QUEUED)
                existing.state in listOf(AiJobState.PAUSED, AiJobState.FAILED) ->
                    existing.copy(state = AiJobState.QUEUED)
                else -> return@withLock null // already running or done
            }
            store.save(resolved)
            refresh()
            resolved
        } ?: return job.id

        val coroutine = scope.launch { runJob(toRun) }
        mutex.withLock { running[job.id] = coroutine }
        return job.id
    }

    override suspend fun cancel(id: String) {
        mutex.withLock { running.remove(id) }?.cancel()
        val existing = store.load(id) ?: return
        store.save(existing.copy(state = AiJobState.CANCELLED))
        refresh()
    }

    private suspend fun runJob(job: AiJob) {
        val pacer = RpmPacer(job.rpm)
        var current = job.copy(state = AiJobState.RUNNING)
        store.save(current)
        refresh()

        for (item in job.items.toList()) {
            // Check if cancelled externally
            if (store.load(job.id)?.state == AiJobState.CANCELLED) return

            pacer.pace()
            val t0 = kotlin.time.Clock.System.now().toEpochMilliseconds()
            runCatching { processor.process(current, item) }
                .onSuccess {
                    val elapsed = kotlin.time.Clock.System.now().toEpochMilliseconds() - t0
                    current = current.copy(
                        items = current.items - item,
                        msPerItem = elapsed,
                    )
                    store.save(current)
                    refresh()
                }
                .onFailure { e ->
                    current = current.copy(state = AiJobState.FAILED, error = e.message)
                    store.save(current)
                    refresh()
                    return
                }
        }

        current = current.copy(state = AiJobState.DONE, items = emptyList())
        store.save(current)
        refresh()
        mutex.withLock { running.remove(job.id) }
    }
}
