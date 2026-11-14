package io.github.alalkipgen.autoflow.android.ai

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android [AiJobRunner] backed by a unique WorkManager "ai" chain (5A-5, D-51).
 *
 * Like [WorkManagerRenderQueue] for renders:
 * - Every outcome is a succeeded work so the chain continues.
 * - Cancel marks the job CANCELLED in the store; the running Worker checks the flag and stops.
 * - The chain name is "ai" (separate from the "render" chain so both can run simultaneously).
 * - Desktop uses [DesktopAiJobRunner] (in-process coroutines); this class is Android-only.
 */
class WorkManagerAiJobRunner(
    private val context: Context,
    private val store: AiJobStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AiJobRunner {

    private val _jobs = MutableStateFlow<List<AiJob>>(emptyList())
    override val jobs: StateFlow<List<AiJob>> = _jobs.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    override suspend fun refresh() {
        _jobs.value = store.loadAll().filter { it.state != AiJobState.CANCELLED }
    }

    override suspend fun enqueue(job: AiJob): String {
        val existing = store.load(job.id)
        val toRun = when {
            existing == null -> job.copy(state = AiJobState.QUEUED)
            existing.state in listOf(AiJobState.PAUSED, AiJobState.FAILED) ->
                existing.copy(state = AiJobState.QUEUED, items = job.items.ifEmpty { existing.items })
            existing.state == AiJobState.DONE || existing.state == AiJobState.RUNNING -> return job.id
            else -> job.copy(state = AiJobState.QUEUED)
        }
        store.save(toRun)
        refresh()
        appendToChain(toRun.id)
        return toRun.id
    }

    override suspend fun cancel(id: String) {
        val existing = store.load(id) ?: return
        store.save(existing.copy(state = AiJobState.CANCELLED))
        refresh()
        // The running Worker reads the CANCELLED flag on its next iteration and stops.
    }

    private fun appendToChain(jobId: String) {
        val request = OneTimeWorkRequestBuilder<AiWorker>()
            .setInputData(Data.Builder().putString(AiWorker.KEY_JOB_ID, jobId).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "${AiWorker.CHAIN_NAME}_$jobId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        /** Build a [WorkManagerAiJobRunner] from the application context. */
        fun of(context: Context): WorkManagerAiJobRunner {
            val jobsDir = File(context.filesDir, "autoflow/ai-jobs").absolutePath
            return WorkManagerAiJobRunner(context, AiJobStore(JvmFs(), jobsDir))
        }
    }
}
