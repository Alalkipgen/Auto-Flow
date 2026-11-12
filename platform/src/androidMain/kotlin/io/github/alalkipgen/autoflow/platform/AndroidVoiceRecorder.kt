package io.github.alalkipgen.autoflow.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [VoiceRecorder] with `MediaRecorder` (AAC in MPEG-4, 44.1 kHz mono). The activity sets [requestPermission]
 * (an `ActivityResultContracts.RequestPermission` launcher) and calls [onPermissionResult] from its callback.
 */
class AndroidVoiceRecorder(private val context: Context) : VoiceRecorder {
    override val extension: String = "m4a"

    /** Launches the RECORD_AUDIO permission request; set by MainActivity. */
    var requestPermission: () -> Unit = { onPermissionResult(false) }

    private val pending = AtomicReference<((Boolean) -> Unit)?>(null)
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun onPermissionResult(granted: Boolean) {
        pending.getAndSet(null)?.invoke(granted)
    }

    private fun granted() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override suspend fun start(path: String): PlatformResult<Unit> {
        if (recorder != null) return PlatformResult.Failed("A recording is already running")
        if (!granted()) {
            if (pending.get() != null) return PlatformResult.Failed("Waiting for the microphone permission")
            val ok = suspendCancellableCoroutine { cont ->
                pending.set { g -> if (cont.isActive) cont.resume(g) }
                cont.invokeOnCancellation { pending.set(null) }
                requestPermission()
            }
            if (!ok || !granted()) return PlatformResult.Failed("Auto Flow needs the microphone permission to record (phone Settings → Apps → Auto Flow → Permissions).")
        }
        val out = File(path)
        out.parentFile?.mkdirs()
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(out.path)
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAt = SystemClock.elapsedRealtime()
            PlatformResult.Ok(Unit)
        } catch (e: Exception) {
            r.release()
            out.delete()
            PlatformResult.Failed("Cannot start recording: ${e.message ?: e::class.simpleName}")
        }
    }

    override suspend fun stop(): PlatformResult<Long> {
        val r = recorder ?: return PlatformResult.Failed("Not recording")
        val ms = SystemClock.elapsedRealtime() - startedAt
        recorder = null
        return try {
            r.stop()
            PlatformResult.Ok(ms)
        } catch (e: RuntimeException) { // stop() right after start(): no valid audio
            file?.delete()
            PlatformResult.Failed("The recording was too short")
        } finally {
            r.release()
            file = null
        }
    }

    override fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        r.release()
        file?.delete()
        file = null
    }
}
