package io.github.alalkipgen.autoflow.android.selftest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import io.github.alalkipgen.autoflow.android.selftest.SelfTestState.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs the Self-test as a foreground service with a progress notification and a partial wake lock, so renders keep
 * going with the screen off or the app in the background (owner's request for PR 4).
 */
class SelfTestService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var runner: SelfTestRunner? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            runner?.cancel()
            if (job?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        SelfTestState.begin()
        val foreground = startInForeground(notification("Starting…", null))
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoFlow:SelfTest")
            ?.apply { acquire(WAKE_LOCK_MS) }
        val mode = if (intent?.getBooleanExtra(EXTRA_MEDIA3_ONLY, false) == true) SelfTestRunner.Mode.MEDIA3_ONLY else SelfTestRunner.Mode.ALL
        val r = SelfTestRunner(this, startNotes = listOf(foreground)) { text, percent -> onProgress(text, percent) }
        runner = r
        job = scope.launch {
            var md: String? = null
            try {
                val report = r.run(mode)
                md = r.save(report)
                SelfTestState.step("Report", Status.OK, report.verdict().summary)
            } catch (e: Exception) {
                // Should not happen (the runner returns values), but never lose the reason on the phone.
                md = "# Auto Flow self-test: CRASHED\n\n```\n${e.stackTraceToString().lines().take(40).joinToString("\n")}\n```\n"
                SelfTestState.step("Report", Status.FAILED, "${e::class.simpleName}: ${e.message}")
            } finally {
                SelfTestState.finish(md)
                wakeLock?.takeIf { it.isHeld }?.release()
                ServiceCompat.stopForeground(this@SelfTestService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                notifyDone(md != null)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ limits mediaProcessing / dataSync services (6 h); stop cleanly instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        runner?.cancel()
    }

    override fun onDestroy() {
        runner?.cancel()
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun onProgress(text: String, percent: Int?) {
        SelfTestState.progress(text)
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotify < NOTIFY_EVERY_MS) return
        lastNotify = now
        getSystemService(NotificationManager::class.java)?.notify(ID_RUNNING, notification(text, percent))
    }

    /**
     * Starts the foreground part and returns a line for the report. The framework call is used on purpose:
     * androidx `ServiceCompat.startForeground` (core 1.18) masks the type with the Android 14 set, which turns
     * `mediaProcessing` (Android 15) into "none" — prohibited for targetSdk ≥ 34 (the v0.1.0 crash on Android 16).
     * A refused type falls back to the next one; if all are refused the run continues while the screen is open.
     */
    private fun startInForeground(n: Notification): String {
        if (Build.VERSION.SDK_INT < 29) {
            startForeground(ID_RUNNING, n)
            SelfTestState.step("Foreground service", Status.OK)
            return "Foreground service: started"
        }
        val types = buildList {
            if (Build.VERSION.SDK_INT >= 35) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING to "mediaProcessing")
            add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC to "dataSync")
        }
        val refused = mutableListOf<String>()
        for ((type, name) in types) {
            try {
                startForeground(ID_RUNNING, n, type)
                SelfTestState.step("Foreground service", Status.OK, name)
                return "Foreground service: $name" + if (refused.isEmpty()) "" else " (${refused.joinToString("; ")})"
            } catch (e: RuntimeException) {
                refused += "$name refused: ${e::class.simpleName}: ${e.message}"
            }
        }
        SelfTestState.step("Foreground service", Status.FAILED, "refused; keep this screen open")
        return "Foreground service REFUSED (${refused.joinToString("; ")}); the run continued only while the app was open"
    }

    private fun notification(text: String, percent: Int?): Notification {
        ensureChannel(this)
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, SelfTestService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Auto Flow Self-test")
            .setContentText(text)
            .setContentIntent(openActivity())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent ?: 0, percent == null)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
    }

    private fun notifyDone(ok: Boolean) {
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Auto Flow Self-test finished")
            .setContentText(if (ok) "Open to copy the report" else "Open to see what happened")
            .setContentIntent(openActivity())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(ID_DONE, n)
    }

    private fun openActivity(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, SelfTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL = "selftest"
        private const val ID_RUNNING = 6
        private const val ID_DONE = 7
        private const val ACTION_CANCEL = "io.github.alalkipgen.autoflow.selftest.CANCEL"
        private const val EXTRA_MEDIA3_ONLY = "media3Only"
        private const val WAKE_LOCK_MS = 60 * 60_000L
        private const val NOTIFY_EVERY_MS = 1_000L

        /**
         * Started from the visible Self-test screen with `startService`, not `startForegroundService`: the service may
         * still call startForeground (the app is in the foreground), and a refused type cannot end in a
         * "did not call startForeground in time" crash.
         */
        fun start(context: Context, media3Only: Boolean) {
            context.startService(Intent(context, SelfTestService::class.java).putExtra(EXTRA_MEDIA3_ONLY, media3Only))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, SelfTestService::class.java).setAction(ACTION_CANCEL))
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Self-test", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}
