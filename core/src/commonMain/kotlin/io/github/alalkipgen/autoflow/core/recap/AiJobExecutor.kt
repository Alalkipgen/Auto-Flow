package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.io.ProjectWrites
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext

/**
 * Runs one persisted [AiJob] to its end (audit F11, spec §5) — the one loop behind both the desktop runner and the
 * Android `AiWorker`:
 * - no [processor] → the job FAILS with [NO_PROCESSOR]; an unprocessed job is never reported DONE;
 * - each item: an item already done in the project ([AiItemProcessor.alreadyDone]) is checked off without a request;
 *   otherwise [pacer] (shared per model, [RpmPacers]) and [AiItemProcessor.process]; then a checkpoint (the item
 *   leaves `items`). A job killed between an item's output and its checkpoint finds that output on the next run and
 *   does not make it again (a request already sent to the provider may have been sent twice: no exactly-once
 *   promise for remote calls);
 * - [AiItemPaused] → PAUSED (spec §3, PR #137), anything else → FAILED, both keeping the remaining items;
 * - a coroutine cancel is rethrown and leaves the state as it is (CANCELLED when the user cancelled; RUNNING when the
 *   app closed — a restart resumes it);
 * - CANCELLED on disk always wins: no checkpoint or end state overwrites it ([AiJobStore.update]).
 */
class AiJobExecutor(
    private val store: AiJobStore,
    private val processor: AiItemProcessor?,
    private val pacer: (AiJob) -> RpmPacer = { RpmPacers.forJob(it) },
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    /** After every saved change (checkpoints, end states): e.g. refresh a list or a notification. */
    private val onChange: (AiJob) -> Unit = {},
) {
    sealed interface Outcome {
        data object Done : Outcome
        data class Paused(val message: String?) : Outcome
        data class Failed(val message: String?) : Outcome
        data object Cancelled : Outcome
        data object Missing : Outcome
        data class Damaged(val reason: String) : Outcome
    }

    suspend fun run(id: String): Outcome {
        val start = when (val r = store.read(id)) {
            AiJobStore.Read.Missing -> return Outcome.Missing
            is AiJobStore.Read.Damaged -> return Outcome.Damaged(r.reason)
            is AiJobStore.Read.Ok -> r.job
        }
        when (start.state) {
            AiJobState.CANCELLED -> return Outcome.Cancelled
            AiJobState.DONE -> return Outcome.Done
            else -> Unit
        }
        val release = ProjectWrites.awaitWriter(store.projectKey(start.project))
        val trace = AiRunTiming(start.timings, id)
        AiRunTiming.dequeueMs(id)?.let { trace.add(AiTimingSample(AiTimingStage.QUEUE, it)) }
        trace.stage(null)
        return try {
            if (store.projectStillMatches(start)) execute(id, trace) else end(id, AiJobState.CANCELLED, null, trace)
        } finally { trace.clear(); release() }
    }

    private suspend fun execute(id: String, trace: AiRunTiming): Outcome {
        if (processor == null) return end(id, AiJobState.FAILED, NO_PROCESSOR, trace)
        var job = save(id, trace) { it.copy(state = AiJobState.RUNNING, error = null) } ?: return Outcome.Missing
        if (job.state == AiJobState.CANCELLED) return Outcome.Cancelled

        for (item in job.items.toList()) {
            job = store.load(id) ?: return Outcome.Missing
            if (job.state == AiJobState.CANCELLED) return Outcome.Cancelled
            if (item !in job.items) continue
            var measured: Long? = null
            try {
                if (!processor.alreadyDone(job, item)) {
                    withContext(trace) { timedAiStage(AiTimingStage.PACER) { pacer(job).pace() } }
                    val t0 = now()
                    withContext(trace) { processor.process(job, item) }
                    measured = (now() - t0).coerceAtLeast(0)
                }
            } catch (e: CancellationException) {
                save(id, trace) { it }
                throw e
            } catch (e: AiItemPaused) {
                return end(id, AiJobState.PAUSED, e.message, trace)
            } catch (e: Throwable) {
                return end(id, AiJobState.FAILED, e.message ?: e::class.simpleName, trace)
            }
            val saved = job.savedClips != null && job.kind == AiJobKind.TTS_ALL && processor.hasSavedClip(job, item)
            job = save(id, trace) { it.copy(items = it.items - item, msPerItem = measured ?: it.msPerItem,
                savedClips = it.savedClips?.let { n -> n + if (saved && item in it.items) 1 else 0 }) } ?: return Outcome.Missing
            if (job.state == AiJobState.CANCELLED) return Outcome.Cancelled
        }
        return end(id, AiJobState.DONE, null, trace)
    }

    /** Ends the job in [state] unless it was cancelled meanwhile; DONE also clears the items. */
    private fun end(id: String, state: AiJobState, error: String?, trace: AiRunTiming): Outcome {
        val saved = save(id, trace) {
            when (state) {
                AiJobState.DONE -> it.copy(state = state, items = emptyList(), error = null)
                else -> it.copy(state = state, error = error)
            }
        } ?: return Outcome.Missing
        return when {
            saved.state == AiJobState.CANCELLED -> Outcome.Cancelled
            state == AiJobState.DONE -> Outcome.Done
            state == AiJobState.PAUSED -> Outcome.Paused(error)
            else -> Outcome.Failed(error)
        }
    }

    /** [change] unless the job on disk is CANCELLED (then it is kept, with the items it still lists). */
    private fun save(id: String, trace: AiRunTiming, change: (AiJob) -> AiJob): AiJob? =
        store.update(id) {
            val next = if (it.state == AiJobState.CANCELLED) it.copy(items = change(it).items) else change(it)
            val samples = trace.samples
            if (samples == it.timings) next else next.copy(timings = samples, timingsUpdatedAt = now())
        }?.also(onChange)

    companion object {
        const val NO_PROCESSOR = "this app has nothing that can run this job"
    }
}

/**
 * One [RpmPacer] per job kind and limit for the whole process (spec §5: "at most rpm requests per rolling minute per
 * model"; a TTS job always uses the project's locked model), shared by every job and runner, so jobs one after
 * another never go over it. Not thread-safe, like [RpmPacer]: both runners run one job at a time (desktop: one
 * in-process queue; Android: the one "ai" WorkManager chain).
 */
object RpmPacers {
    private val pacers = mutableMapOf<String, RpmPacer>()

    fun forJob(job: AiJob): RpmPacer = shared("${job.kind}", job.rpm)

    fun shared(key: String, rpm: Int): RpmPacer = pacers.getOrPut("$key@$rpm") { RpmPacer(rpm) }
}
