package io.github.alalkipgen.autoflow.android.ai

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiRunTiming
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
 * Android [AiJobRunner] backed by the one unique WorkManager "ai" chain (5A-5, D-51).
 *
 * Like [WorkManagerRenderQueue] for renders:
 * - Every outcome is a succeeded work so the chain continues.
 * - Jobs are appended to one chain (audit F11: no longer one unique work per job), so they run one at a time and
 *   the shared per-model pacer holds across jobs.
 * - Cancel marks the job CANCELLED in the store; the running Worker checks the flag before the next item and the
 *   shared executor never overwrites it.
 * - Enqueue of a QUEUED / RUNNING job appends it again (WorkManager normally resumes it after process death; this
 *   covers a lost work): a second run of a finished job does nothing, items done in the project are not redone.
 * - The chain name is "ai" (separate from the "render" chain so both can run simultaneously).
 * - Desktop uses [DesktopAiJobRunner] (in-process coroutines); this class is Android-only.
 */
class WorkManagerAiJobRunner internal constructor(
    private val store: AiJobStore,
    /** Appends one AiWorker for the job id to the one "ai" chain; a seam so unit tests need no WorkManager. */
    private val appendToChain: (jobId: String) -> Unit,
    private val scope: CoroutineScope,
) : AiJobRunner {

    constructor(
        context: Context,
        store: AiJobStore,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(store, { id -> appendWork(context, id) }, scope)

    private val _jobs = MutableStateFlow<List<AiJob>>(emptyList())
    override val jobs: StateFlow<List<AiJob>> = _jobs.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    override suspend fun refresh() = publish()

    override fun hasPendingProject(key: String, canonical: (String) -> String) = store.loadAll().any {
        it.state in setOf(AiJobState.QUEUED, AiJobState.RUNNING) && canonical(it.project) == key
    }

    @Synchronized private fun publish() {
        _jobs.value = store.loadAll().filter { it.state != AiJobState.CANCELLED }
    }

    /** Returns the id the job runs under: [job]'s, or `<id>-r` when a damaged file of that id is kept aside. */
    override suspend fun enqueue(job: AiJob): String {
        val claim = store.prepareEnqueue(job)
        refresh()
        if (claim.shouldLaunch) appendToChain(claim.id)
        return claim.id
    }

    override suspend fun recordForeground(job: AiJob) {
        require(job.kind == io.github.alalkipgen.autoflow.core.recap.AiJobKind.SCRIPT &&
            job.state !in setOf(AiJobState.QUEUED, AiJobState.RUNNING))
        store.save(job)
        refresh()
    }

    override suspend fun cancel(id: String) {
        AiRunTiming.forgetQueue(id)
        // Read-modify-write under the jobs-folder lock, so a Worker checkpoint cannot interleave with it.
        store.update(id) { if (it.state == AiJobState.DONE) it else it.copy(state = AiJobState.CANCELLED) }
        refresh()
        // The running Worker reads the CANCELLED flag before its next item and stops; the executor never overwrites it.
    }

    companion object {
        /** Build a [WorkManagerAiJobRunner] from the application context. */
        fun of(context: Context): WorkManagerAiJobRunner {
            val jobsDir = File(context.filesDir, "autoflow/ai-jobs").absolutePath
            return WorkManagerAiJobRunner(context, AiJobStore(JvmFs(), jobsDir)).also { runner ->
                AiWorkerRegistry.onJobsChanged = runner::publish
            }
        }

        private fun appendWork(context: Context, jobId: String) {
            val request = OneTimeWorkRequestBuilder<AiWorker>()
                .setInputData(Data.Builder().putString(AiWorker.KEY_JOB_ID, jobId).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(AiWorker.CHAIN_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
