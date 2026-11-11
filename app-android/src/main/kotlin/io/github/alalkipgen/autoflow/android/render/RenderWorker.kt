package io.github.alalkipgen.autoflow.android.render

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.render.FfmpegProgress
import io.github.alalkipgen.autoflow.media.render.FfmpegRunner
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderJobRunner
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.platform.AndroidToolLocator
import io.github.alalkipgen.autoflow.platform.JvmFs
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay

/**
 * Runs one [RenderJob] (PLAN 1F) as a WorkManager foreground worker (D-26): a progress notification with Cancel,
 * keeps going with the screen off or the app in the background. The unique "render" chain in
 * [WorkManagerRenderQueue] keeps renders to one at a time; [RenderExecution] holds the rules (cancel stops only this
 * render, every outcome is a succeeded work so the chain goes on). The job file is the work id in [RenderJobs].
 */
class RenderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private var lastProgress = 0L
    private var lastNotify = 0L
    private var title = ""

    override suspend fun doWork(): Result {
        var foreground: String? = null
        val outcome = try {
            RenderExecution(RenderJobs.of(applicationContext), ::render).run(
                id.toString(),
                foreground = { job ->
                    title = job.title
                    val (note, ok) = startForeground(job.title)
                    foreground = note
                    ok
                },
                onProgress = ::onProgress,
            )
        } catch (e: CancellationException) {
            throw e // stopped by WorkManager: it retries this work, the job file stays
        } catch (e: Throwable) {
            RenderExecution.Outcome(RenderState.Failed("the render crashed: ${e::class.simpleName}: ${e.message}"), null)
        }
        nm?.cancel(ID_PROGRESS)
        outcome.job?.let { notifyFinished(it.title, outcome.state) }
        return Result.success(RenderData.output(outcome.state, foreground))
    }

    private suspend fun render(job: RenderJob, onProgress: (FfmpegProgress.Progress) -> Unit): RenderState {
        val tools = AndroidToolLocator(applicationContext.applicationInfo.nativeLibraryDir)
        val ffmpeg = tools.findFfmpeg()
        val ffprobe = tools.findFfprobe()
        if (ffmpeg == null || ffprobe == null) return RenderState.Failed("the bundled ffmpeg/ffprobe is not in this app (nativeLibraryDir)")
        val runner = ProcessToolRunner()
        return RenderJobRunner(JvmFs(), FfmpegRunner(runner, ffmpeg), MediaProbe(runner, ffprobe)).run(job, onProgress)
    }

    /**
     * Asks WorkManager to run this worker in its foreground service (mediaProcessing on Android 15+, else dataSync)
     * and returns a report line and whether the service actually reached the foreground. If it did not, the
     * job fails rather than silently running without the background-execution guarantee.
     */
    private suspend fun startForeground(title: String): Pair<String, Boolean> {
        val n = progressNotification(title, "Starting…", null)
        val (info, type) = when {
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING) to "mediaProcessing"
            Build.VERSION.SDK_INT >= 29 -> ForegroundInfo(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) to "dataSync"
            else -> ForegroundInfo(ID_PROGRESS, n) to "foreground"
        }
        return try {
            setForeground(info)
            delay(FOREGROUND_CHECK_MS) // the service handles the start on the main thread
            val running = inForeground()
            "$type requested, running in the foreground: ${if (running) "yes" else "NO"}" to running
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "$type refused (${e::class.simpleName}: ${e.message})" to false
        }
    }

    /** Own services are still listed by [ActivityManager.getRunningServices]; it is the only "is it foreground" check. */
    @Suppress("DEPRECATION")
    private fun inForeground(): Boolean = runCatching {
        applicationContext.getSystemService(ActivityManager::class.java).getRunningServices(MAX_SERVICES)
            .any { it.service.className == FOREGROUND_SERVICE && it.foreground }
    }.getOrDefault(false)

    private fun onProgress(p: FfmpegProgress.Progress) {
        if (isStopped) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgress >= PROGRESS_EVERY_MS || p.done) {
            lastProgress = now
            setProgressAsync(RenderData.progress(p))
        }
        if (now - lastNotify >= NOTIFY_EVERY_MS) {
            lastNotify = now
            val percent = (p.fraction * 100).toInt()
            nm?.notify(ID_PROGRESS, progressNotification(title, "$percent%" + (p.speed?.let { " · %.2f×".format(it) } ?: ""), percent))
        }
    }

    private fun progressNotification(title: String, text: String, percent: Int?): Notification {
        ensureChannel(applicationContext)
        return Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Rendering $title")
            .setContentText(text)
            .setContentIntent(openIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent ?: 0, percent == null)
            // Not WorkManager's cancel intent: that would also cancel every render queued behind this one.
            .addAction(Notification.Action.Builder(null, "Cancel", RenderCancelReceiver.intent(applicationContext, id.toString())).build())
            .build()
    }

    private fun notifyFinished(title: String, state: RenderState) {
        val text = when (state) {
            is RenderState.Done -> "Saved ${File(state.output).name}"
            is RenderState.Failed -> "Failed: ${state.reason}"
            else -> return
        }
        val n = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(if (state is RenderState.Done) "Rendered $title" else "Render failed: $title")
            .setContentText(text)
            .setContentIntent(openIntent())
            .setAutoCancel(true)
            .build()
        nm?.notify(ID_DONE, n)
    }

    /** Opens the screen that queued the render ([RenderData.KEY_OPEN]), else the app. */
    private fun openIntent(): PendingIntent? {
        val ctx = applicationContext
        val intent = inputData.getString(RenderData.KEY_OPEN)?.let { Intent().setClassName(ctx, it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
            ?: ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: return null
        return PendingIntent.getActivity(ctx, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        private const val CHANNEL = "render"
        private const val FOREGROUND_SERVICE = "androidx.work.impl.foreground.SystemForegroundService"
        private const val ID_PROGRESS = 21
        private const val ID_DONE = 22
        private const val MAX_SERVICES = 50
        private const val PROGRESS_EVERY_MS = 500L
        private const val NOTIFY_EVERY_MS = 1_000L
        private const val FOREGROUND_CHECK_MS = 1_500L

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Renders", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}
