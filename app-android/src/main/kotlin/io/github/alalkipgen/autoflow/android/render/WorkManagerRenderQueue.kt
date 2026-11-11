package io.github.alalkipgen.autoflow.android.render

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.media.render.RenderState
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Android [RenderQueue] (PLAN 1F, D-26): each job is a [RenderWorker] appended to the unique work chain "render",
 * so one renders at a time and the rest wait. The job itself is a JSON file (WorkManager input data is capped at
 * 10 KB). [openActivity] is the screen a tap on the notification opens (null = the app).
 */
class WorkManagerRenderQueue(private val context: Context, private val openActivity: String? = null) : RenderQueue {
    private val work get() = WorkManager.getInstance(context)
    private val jobsDir = File(context.filesDir, "render-jobs")

    /** Ids enqueued here that WorkManager may not have stored yet (enqueueing is asynchronous). */
    private val pending = ConcurrentHashMap.newKeySet<String>()

    override fun enqueue(job: RenderJob): String {
        val id = UUID.randomUUID()
        val file = File(jobsDir.apply { mkdirs() }, "$id.json")
        file.writeText(CoreJson.encodeToString(RenderJob.serializer(), job))
        val input = Data.Builder().putString(RenderWorker.KEY_JOB, file.path)
        openActivity?.let { input.putString(RenderWorker.KEY_OPEN, it) }
        val request = OneTimeWorkRequestBuilder<RenderWorker>().setId(id).setInputData(input.build()).addTag(TAG).build()
        // APPEND_OR_REPLACE: behind a running render; a failed or cancelled one does not block the next.
        pending += id.toString()
        work.enqueueUniqueWork(CHAIN, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        return id.toString()
    }

    override fun observe(id: String): Flow<RenderState> =
        work.getWorkInfoByIdFlow(UUID.fromString(id)).map { info ->
            if (info == null && id in pending) RenderState.Queued else toState(info).also { pending -= id }
        }.distinctUntilChanged()

    override fun cancel(id: String) {
        work.cancelWorkById(UUID.fromString(id))
    }

    companion object {
        const val CHAIN = "render"
        const val TAG = "render"

        /** WorkManager's view → the shared [RenderState]; null = WorkManager no longer knows the id (pruned after a day). */
        fun toState(info: WorkInfo?): RenderState = when (info?.state) {
            null -> RenderState.Failed("this render is no longer known (finished renders are forgotten after a day)")
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> RenderState.Queued
            WorkInfo.State.RUNNING -> RenderState.Running(
                info.progress.getDouble(RenderWorker.KEY_FRACTION, 0.0),
                info.progress.getDouble(RenderWorker.KEY_SPEED, -1.0).takeIf { it >= 0 },
            )
            WorkInfo.State.SUCCEEDED -> info.outputData.let { d ->
                if (d.getString(RenderWorker.KEY_REASON) != null) {
                    RenderState.Failed(d.getString(RenderWorker.KEY_REASON)!!, d.getStringArray(RenderWorker.KEY_TAIL)?.toList().orEmpty())
                } else RenderState.Done(
                    d.getString(RenderWorker.KEY_OUTPUT).orEmpty(),
                    d.getLong(RenderWorker.KEY_WALL_MS, 0),
                    d.getLong(RenderWorker.KEY_DURATION_MS, 0),
                    d.getLong(RenderWorker.KEY_RSS_KB, -1).takeIf { it >= 0 },
                )
            }
            WorkInfo.State.FAILED -> info.outputData.let { d ->
                if (d.getString(RenderWorker.KEY_REASON) == "cancelled") {
                    RenderState.Cancelled
                } else {
                    RenderState.Failed(d.getString(RenderWorker.KEY_REASON) ?: "the render stopped", d.getStringArray(RenderWorker.KEY_TAIL)?.toList().orEmpty())
                }
            }
            WorkInfo.State.CANCELLED -> RenderState.Cancelled
        }

        /** The worker's report line about its foreground service (see [RenderWorker]), when it has finished. */
        fun foregroundNote(info: WorkInfo?): String? = info?.outputData?.getString(RenderWorker.KEY_FOREGROUND)
    }
}
