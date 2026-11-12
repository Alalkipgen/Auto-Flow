package io.github.alalkipgen.autoflow.platform

import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [VoiceRecorder] with `javax.sound`: 44.1 kHz 16-bit mono WAV from the default microphone. */
class DesktopVoiceRecorder : VoiceRecorder {
    override val extension: String = "wav"

    private var line: TargetDataLine? = null
    private var writer: Thread? = null
    private var file: File? = null
    private var startedAt = 0L

    override suspend fun start(path: String): PlatformResult<Unit> = withContext(Dispatchers.IO) {
        if (line != null) return@withContext PlatformResult.Failed("A recording is already running")
        val format = AudioFormat(44_100f, 16, 1, true, false)
        val l = try {
            AudioSystem.getTargetDataLine(format).apply { open(format) }
        } catch (e: Exception) {
            return@withContext PlatformResult.Unsupported("No microphone is available: ${e.message ?: e::class.simpleName}")
        }
        val out = File(path).also { it.parentFile?.mkdirs() }
        l.start()
        line = l
        file = out
        startedAt = System.currentTimeMillis()
        writer = Thread {
            runCatching { AudioSystem.write(AudioInputStream(l), AudioFileFormat.Type.WAVE, out) }
        }.apply { isDaemon = true; start() }
        PlatformResult.Ok(Unit)
    }

    override suspend fun stop(): PlatformResult<Long> = withContext(Dispatchers.IO) {
        val l = line ?: return@withContext PlatformResult.Failed("Not recording")
        val ms = System.currentTimeMillis() - startedAt
        l.stop()
        l.close()
        writer?.join(5_000)
        line = null
        writer = null
        file = null
        PlatformResult.Ok(ms)
    }

    override fun cancel() {
        val l = line ?: return
        l.stop()
        l.close()
        writer?.join(5_000)
        file?.delete()
        line = null
        writer = null
        file = null
    }
}
