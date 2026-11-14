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
import io.github.alalkipgen.autoflow.core.recap.RpmPacer
import io.github.alalkipgen.autoflow.platform.JvmFs
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * WorkManager worker for one AI job item batch (5A-5, D-51).
 *
 * Runs in the unique "ai" WorkManager chain (see [WorkManagerAiJobRunner]).
 * Shows a foreground notification (dataSync type) so it keeps running with the screen off.
 * Each worker processes ALL remaining items of a single [AiJob], resuming where a previous
 * process left off (items list shrinks on each success).
 *
 * [AiItemProcessor] is resolved from [AiWorkerRegistry] so it survives process recreation.
 */
class AiWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val nm = context.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val jobsDir = File(applicationContext.filesDir, "autoflow/ai-jobs").absolutePath
        val store = AiJobStore(JvmFs(), jobsDir)
        val job = store.load(jobId) ?: return Result.success()

        if (job.state == AiJobState.CANCELLED || job.state == AiJobState.DONE) return Result.success()

        setForeground(buildForeground(labelFor(job), "Starting…"))

        val processor = AiWorkerRegistry.processor ?: run {
            store.save(job.copy(state = AiJobState.FAILED, error = "AI processor not available (app restart required)"))
            return Result.success()
        }

        val pacer = RpmPacer(job.rpm)
        var current = job.copy(state = AiJobState.RUNNING)
        store.save(current)

        for (item in job.items.toList()) {
            if (store.load(jobId)?.state == AiJobState.CANCELLED) return Result.success()
            try { pacer.pace() } catch (e: CancellationException) { throw e }
            val t0 = System.currentTimeMillis()
            try {
                processor.process(current, item)
                val elapsed = System.currentTimeMillis() - t0
                current = current.copy(items = current.items - item, msPerItem = elapsed)
                store.save(current)
                updateNotification(labelFor(current), "${current.items.size} items left")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                store.save(current.copy(state = AiJobState.FAILED, error = e.message))
                return Result.success()
            }
        }

        store.save(current.copy(state = AiJobState.DONE, items = emptyList()))
        return Result.success()
    }

    private fun labelFor(job: AiJob): String = when (job.kind) {
        AiJobKind.TTS_ALL -> "Speaking (${job.items.size} left)"
        AiJobKind.TRANSCRIBE -> "Transcribing"
        AiJobKind.QUICK_RECAP -> "Quick Recap"
        AiJobKind.PUBLISH_KIT -> "Publish Kit"
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
    var processor: AiItemProcessor? = null
}
