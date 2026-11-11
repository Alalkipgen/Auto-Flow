package io.github.alalkipgen.autoflow.android.render

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.media.render.RenderState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The part of WorkManager the queue uses, so the queue is tested with a fake. */
interface RenderWork {
    /** Appends a [RenderWorker] with [id] to the unique "render" chain; [done] gets null once it is stored, else the error. */
    fun append(id: UUID, openActivity: String?, done: (Throwable?) -> Unit)

    /** WorkManager's view of [id]; null = it is not stored (yet, or any more). */
    fun observe(id: UUID): Flow<RenderState?>

    /** Ids of render work WorkManager may still run or retry. */
    suspend fun unfinished(): Set<String>
}

/**
 * Android [RenderQueue] (PLAN 1F, D-26). Every job is a [RenderWorker] appended to one unique WorkManager chain, so
 * renders run one at a time, in order; each worker ends as a succeeded work whatever the render did, so the next
 * one always starts. Cancel goes through [RenderJobs] (a queued job is skipped, the running one stopped) — never
 * through WorkManager, which would also cancel every render queued behind it. Enqueue failures (the job file or
 * WorkManager's database) come back from [observe] as [RenderState.Failed]. [openActivity] is the screen a tap on
 * the notification opens (null = the app).
 */
class WorkManagerRenderQueue(
    private val jobs: RenderJobs,
    private val work: RenderWork,
    private val openActivity: String? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : RenderQueue {
    constructor(context: Context, openActivity: String? = null) :
        this(RenderJobs.of(context), WorkManagerWork(context.applicationContext), openActivity)

    /** Ids handed to WorkManager that it may not have stored yet (enqueueing is asynchronous). */
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap<String, String>()

    /** Bumped when [failed] or a cancel changes what [observe] shows without WorkManager noticing. */
    private val changes = MutableStateFlow(0)

    override fun enqueue(job: RenderJob): String {
        val uuid = UUID.randomUUID()
        val id = uuid.toString()
        try {
            jobs.write(id, job)
        } catch (e: Exception) {
            fail(id, "cannot save the render job: ${e.message}")
            return id
        }
        pending += id
        work.append(uuid, openActivity) { error ->
            if (error == null) {
                prune()
            } else {
                jobs.remove(id)
                fail(id, "could not queue the render: ${error.message ?: error::class.simpleName}")
            }
        }
        return id
    }

    override fun observe(id: String): Flow<RenderState> {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return flowOf(RenderState.Failed(RenderData.UNKNOWN))
        return combine(work.observe(uuid), changes) { state, _ -> merge(id, state) }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }

    override fun cancel(id: String) {
        jobs.cancel(id)
        changes.update { it + 1 }
    }

    /** What the owner sees: a finished work is final; a cancelled job that is not rendering here is already over. */
    private fun merge(id: String, state: RenderState?): RenderState {
        failed[id]?.let { return RenderState.Failed(it) }
        if (state?.finished == true) return state
        if (jobs.isCancelled(id) && !jobs.isRunning(id)) return RenderState.Cancelled
        if (state == null) return if (id in pending) RenderState.Queued else RenderState.Failed(RenderData.UNKNOWN)
        pending -= id
        return state
    }

    private fun fail(id: String, reason: String) {
        failed[id] = reason
        pending -= id
        changes.update { it + 1 }
    }

    /** Old job files go once WorkManager no longer needs them (see [RenderJobs.prune]). */
    private fun prune() {
        scope.launch { runCatching { jobs.prune(work.unfinished()) } }
    }
}

/** [RenderWork] on WorkManager: one unique chain, APPEND_OR_REPLACE (a finished render never blocks the next). */
class WorkManagerWork(private val context: Context) : RenderWork {
    private val wm get() = WorkManager.getInstance(context)

    override fun append(id: UUID, openActivity: String?, done: (Throwable?) -> Unit) {
        try {
            val input = Data.Builder().apply { openActivity?.let { putString(RenderData.KEY_OPEN, it) } }.build()
            val request = OneTimeWorkRequestBuilder<RenderWorker>().setId(id).setInputData(input).addTag(TAG).build()
            val result = wm.enqueueUniqueWork(CHAIN, ExistingWorkPolicy.APPEND_OR_REPLACE, request).result
            result.addListener({
                val error = try {
                    result.get()
                    null
                } catch (e: ExecutionException) {
                    e.cause ?: e
                } catch (e: Exception) {
                    e
                }
                done(error)
            }, Runnable::run)
        } catch (e: Exception) {
            done(e)
        }
    }

    override fun observe(id: UUID): Flow<RenderState?> = wm.getWorkInfoByIdFlow(id).map { info -> info?.let(RenderData::state) }

    override suspend fun unfinished(): Set<String> =
        wm.getWorkInfosByTagFlow(TAG).first().filterNot { it.state.isFinished }.map { it.id.toString() }.toSet()

    companion object {
        const val CHAIN = "render"
        const val TAG = "render"
    }
}
