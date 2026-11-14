package io.github.alalkipgen.autoflow.core.recap

import kotlinx.coroutines.flow.StateFlow

/**
 * In-process AI job runner (5A-5, studio-v2.md §5).
 *
 * Platform impls (Android WorkManager chain, desktop in-process) implement [enqueue] and
 * [cancel]. The runner persists state via [AiJobStore] and exposes it as a [StateFlow].
 */
interface AiJobRunner {
    /** All known jobs, refreshed on every state change. */
    val jobs: StateFlow<List<AiJob>>

    /**
     * Enqueue a new job (or resume an existing PAUSED / FAILED one with the same [id]).
     * Returns the job id.
     */
    suspend fun enqueue(job: AiJob): String

    /** Cancel a running or queued job. */
    suspend fun cancel(id: String)

    /** Force a one-shot refresh of [jobs] from disk. */
    suspend fun refresh()
}

/** Item processor called by the runner for each item in a job. */
fun interface AiItemProcessor {
    /** Process one [item]; throw on failure. */
    suspend fun process(job: AiJob, item: String)
}
