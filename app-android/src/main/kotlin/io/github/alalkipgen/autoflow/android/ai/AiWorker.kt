package io.github.alalkipgen.autoflow.android.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.alalkipgen.autoflow.android.MainActivity
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.core.recap.AiItemProcessor
import io.github.alalkipgen.autoflow.core.recap.AiJobExecutor
import io.github.alalkipgen.autoflow.core.recap.RpmPacers
import io.github.alalkipgen.autoflow.platform.JvmFs
import java.io.File

/**
 * WorkManager worker for one AI job (5A-5, D-51).
 *
 * Runs in the one "ai" WorkManager chain (see [WorkManagerAiJobRunner]): one job at a time, so the shared per-model
 * pacer ([RpmPacers]) holds for all of them. Shows a foreground notification (dataSync type) so it keeps running
 * with the screen off. The loop is the shared [AiJobExecutor] (audit F11, the same as desktop): checkpoints after
 * every item, items already done in the job's project are checked off without a request, a CANCELLED state is never
 * overwritten, busy → PAUSED, other errors → FAILED. A job file that cannot be read is left as it is and reported
 * (never treated as a finished job); a missing one means there is nothing to do.
 *
 * [AiItemProcessor] is resolved from [AiWorkerRegistry] so it survives process recreation.
 */
class AiWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val nm = context.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val jobsDir = File(applicationContext.filesDir, "autoflow/ai-jobs").absolutePath
        val store = AiJobStore(JvmFs(), jobsDir)
        val job = when (val r = store.read(jobId)) {
            AiJobStore.Read.Missing, AiJobStore.Read.Removed -> return Result.success()
            is AiJobStore.Read.Damaged -> {
                // Kept on disk for the Jobs screen / a bug report; the chain goes on with the other jobs.
                android.util.Log.w("AiWorker", r.reason)
                return Result.success()
            }
            is AiJobStore.Read.Ok -> r.job
        }
        if (job.state == AiJobState.CANCELLED || job.state == AiJobState.DONE) return Result.success()

        setForeground(buildForeground(labelFor(job), "Starting…"))
        // No processor (not registered in this process) → the executor FAILS the job with a reason; never DONE.
        AiJobExecutor(store, AiWorkerRegistry.processor, onChange = {
            AiWorkerRegistry.onJobsChanged?.invoke()
            updateNotification(labelFor(it), "${it.items.size} items left")
        }).run(jobId)
        return Result.success()
    }

    private fun labelFor(job: AiJob): String = when (job.kind) {
        AiJobKind.TTS_ALL -> "Speaking (${job.items.size} left)"
        AiJobKind.TRANSCRIBE -> "Transcribing"
        AiJobKind.QUICK_RECAP -> "Quick Recap"
        AiJobKind.PUBLISH_KIT -> "Publish Kit"
        AiJobKind.SCRIPT -> "Script"
        AiJobKind.MEDIA_TRANSCRIBE -> "Transcribing the video"
        AiJobKind.SUB_TRANSLATE -> "Translating subtitles"
        AiJobKind.TEXT_TTS -> "Speaking the text"
    }

    private fun buildForeground(title: String, text: String): ForegroundInfo {
        ensureChannel()
        val tap = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(ID_NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else
            ForegroundInfo(ID_NOTIFICATION, n)
    }

    private fun updateNotification(title: String, text: String) {
        ensureChannel()
        val n = Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .build()
        nm?.notify(ID_NOTIFICATION, n)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AI Jobs", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val CHAIN_NAME = "ai"
        const val CHANNEL_ID = "af_ai"
        const val ID_NOTIFICATION = 2001
    }
}

/**
 * App-level singleton holding the [AiItemProcessor]; set by [AndroidModule] at startup.
 * WorkManager may recreate the Worker in a fresh process, so the processor must be re-registered
 * by [AutoFlowApp.onCreate] before any Worker can run.
 */
object AiWorkerRegistry {
    @Volatile
    internal var onJobsChanged: (() -> Unit)? = null

    @Volatile
    var processor: AiItemProcessor? = null
}
