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
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.alalkipgen.autoflow.core.model.CoreJson
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
 * [WorkManagerRenderQueue] keeps renders to one at a time. Results go back as output [Data] (≤ 10 KB).
 */
class RenderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private var lastProgress = 0L
    private var lastNotify = 0L

    override suspend fun doWork(): Result {
        val jobFile = inputData.getString(KEY_JOB)?.let(::File) ?: return failure("the render job is missing", "")
        val job = try {
            CoreJson.decodeFromString(RenderJob.serializer(), jobFile.readText())
        } catch (e: Exception) {
            return failure("cannot read the render job: ${e.message}", "")
        }
        val title = job.title
        val foreground = startForeground(title)
        if (!foreground.second) {
            return failure("could not start foreground service", foreground.first)
        }
        val tools = AndroidToolLocator(applicationContext.applicationInfo.nativeLibraryDir)
        val ffmpeg = tools.findFfmpeg()
        val ffprobe = tools.findFfprobe()
        var state: RenderState = RenderState.Cancelled
        try {
            state = if (ffmpeg == null || ffprobe == null) {
                RenderState.Failed("the bundled ffmpeg/ffprobe is not in this app (nativeLibraryDir)")
            } else {
                val runner = ProcessToolRunner()
                RenderJobRunner(JvmFs(), FfmpegRunner(runner, ffmpeg), MediaProbe(runner, ffprobe)).run(job) { onProgress(title, it) }
            }
        } finally {
            // A stopped or failed render must not leave a half-written file behind (the good export stays as it was).
            if (state !is RenderState.Done) File(partPath(job)).delete()
            nm?.cancel(ID_PROGRESS)
        }
        notifyFinished(title, state)
        return when (val s = state) {
            is RenderState.Done -> Result.success(
                Data.Builder().putString(KEY_FOREGROUND, foreground.first).putString(KEY_OUTPUT, s.output).putLong(KEY_WALL_MS, s.wallMs)
                    .putLong(KEY_DURATION_MS, s.durationMs).putLong(KEY_RSS_KB, s.maxRssKb ?: -1).build(),
            )
            is RenderState.Failed -> failure(s.reason, foreground.first, s.tail)
            else -> failure("cancelled", foreground.first)
        }
    }

    // A failed render is an application-level result: completing WorkManager work lets later queued jobs proceed.
    private fun failure(reason: String, foreground: String, tail: List<String> = emptyList()): Result = Result.success(
        Data.Builder().putString(KEY_REASON, reason.take(MAX_LINE * 4)).putString(KEY_FOREGROUND, foreground.take(MAX_LINE * 2))
            .putStringArray(KEY_TAIL, tail.takeLast(MAX_TAIL).map { it.take(MAX_LINE) }.toTypedArray()).build(),
    )

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

    private fun onProgress(title: String, p: FfmpegProgress.Progress) {
        if (isStopped) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgress >= PROGRESS_EVERY_MS || p.done) {
            lastProgress = now
            setProgressAsync(Data.Builder().putDouble(KEY_FRACTION, p.fraction).putDouble(KEY_SPEED, p.speed ?: -1.0).build())
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
            .addAction(Notification.Action.Builder(null, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build())
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

    /** Opens the screen that queued the render ([KEY_OPEN]), else the app. */
    private fun openIntent(): PendingIntent? {
        val ctx = applicationContext
        val intent = inputData.getString(KEY_OPEN)?.let { Intent().setClassName(ctx, it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
            ?: ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: return null
        return PendingIntent.getActivity(ctx, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val KEY_JOB = "job"
        const val KEY_OPEN = "open"
        const val KEY_FRACTION = "fraction"
        const val KEY_SPEED = "speed"
        const val KEY_OUTPUT = "output"
        const val KEY_WALL_MS = "wallMs"
        const val KEY_DURATION_MS = "durationMs"
        const val KEY_RSS_KB = "maxRssKb"
        const val KEY_REASON = "reason"
        const val KEY_TAIL = "tail"
        const val KEY_FOREGROUND = "foreground"
        private const val CHANNEL = "render"
        private const val FOREGROUND_SERVICE = "androidx.work.impl.foreground.SystemForegroundService"
        private const val ID_PROGRESS = 21
        private const val ID_DONE = 22
        private const val MAX_TAIL = 15
        private const val MAX_LINE = 120
        private const val MAX_SERVICES = 50
        private const val PROGRESS_EVERY_MS = 500L
        private const val NOTIFY_EVERY_MS = 1_000L
        private const val FOREGROUND_CHECK_MS = 1_500L

        /** Where [RenderJobRunner] writes before the rename (`<outputName>.part.mp4`). */
        fun partPath(job: RenderJob): String {
            val out = if (job.outputName.startsWith("/")) job.outputName else File(job.workDir, job.outputName).path
            return "$out.part.mp4"
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Renders", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}
