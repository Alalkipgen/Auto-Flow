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

    /** Terminal history from a foreground Script invocation, which is never scheduled as worker work. */
    suspend fun recordForeground(job: AiJob) {}

    /** Force a one-shot refresh of [jobs] from disk. */
    suspend fun refresh()
}

/** Item processor called by the runner for each item in a job. */
fun interface AiItemProcessor {
    /** Process one [item]; throw on failure ([AiItemPaused] = for now, [UnsupportedAiJob] / anything else = FAILED). */
    suspend fun process(job: AiJob, item: String)

    /**
     * True when [item]'s result is already in the job's project (e.g. a current take for the block, spec §5: "finished
     * items are found from the project, not only from the file"): the runner then checks it off without a request.
     */
    suspend fun alreadyDone(job: AiJob, item: String): Boolean = false

    /** False for skipped/removed blocks; a checkpoint is not by itself proof of a saved voice clip. */
    fun hasSavedClip(job: AiJob, item: String): Boolean = false
}

/** Thrown by an [AiItemProcessor] for a job kind it does not run: the job FAILS (never a silent Done, audit F11). */
class UnsupportedAiJob(kind: AiJobKind) : Exception("$kind jobs cannot run in this app yet")

/**
 * Thrown by an [AiItemProcessor] when the item could not run *for now* (the locked model is busy: 429 / 5xx after
 * retries, spec §3). The runner keeps the remaining items and marks the job PAUSED with [message] so the user can
 * Resume; any other exception marks it FAILED.
 */
class AiItemPaused(message: String) : Exception(message)
