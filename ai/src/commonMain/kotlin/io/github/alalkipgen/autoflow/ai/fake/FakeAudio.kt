package io.github.alalkipgen.autoflow.ai.fake

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.Availability
import io.github.alalkipgen.autoflow.ai.Transcript
import io.github.alalkipgen.autoflow.ai.TranscriptSegment
import io.github.alalkipgen.autoflow.ai.TranscribeProvider
import io.github.alalkipgen.autoflow.ai.TtsCapabilities
import io.github.alalkipgen.autoflow.ai.TtsProvider
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.ai.codec.Wav
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Offline [TtsProvider]: a quiet sine tone as 16-bit mono WAV, [msPerChar] per character (0.5–30 s),
 * pitch picked from a hash of the inputs. Reports Burmese + designed voices, no replication.
 */
class FakeTtsProvider(
    private val delayMs: Long = 0,
    private val msPerChar: Int = 60,
) : TtsProvider {
    var calls: Int = 0
        private set

    override val capabilities = TtsCapabilities(
        replication = Availability.UNAVAILABLE,
        burmese = true,
        designedVoices = true,
    )

    override suspend fun speak(text: String, voice: VoiceRef, lang: String, style: String?): AiResult<ByteArray> {
        calls++
        if (delayMs > 0) delay(delayMs)
        if (text.isBlank()) return AiResult.Failed("empty_text", "Nothing to speak")
        val seed = fakeSeed(text, voice.id, lang, style.orEmpty())
        val durationMs = (text.trim().length.toLong() * msPerChar).coerceIn(500L, 30_000L)
        val hz = 180.0 + (seed ushr 1) % 160
        val n = (SAMPLE_RATE * durationMs / 1000).toInt()
        val fade = SAMPLE_RATE / 100 // 10 ms ramps, no clicks
        val samples = ShortArray(n) { i ->
            val env = minOf(1.0, i.toDouble() / fade, (n - 1 - i).toDouble() / fade)
            (sin(2 * PI * hz * i / SAMPLE_RATE) * env * AMPLITUDE).roundToInt().toShort()
        }
        return AiResult.Ok(Wav.pcm16Mono(samples, SAMPLE_RATE), Usage(model = MODEL, latencyMs = delayMs))
    }

    companion object {
        const val MODEL = "fake-tts"
        const val SAMPLE_RATE = 24_000
        private const val AMPLITUDE = 6_000.0
    }
}

/**
 * Offline [TranscribeProvider]: contiguous [segmentMs] segments covering the audio (WAV duration, else
 * [fallbackDurationMs]); text cycles through [lines], or fixture lines in Burmese for `lang = "my"`.
 */
class FakeTranscribeProvider(
    private val delayMs: Long = 0,
    private val lines: List<String> = emptyList(),
    private val segmentMs: Long = 4_000,
    private val fallbackDurationMs: Long = 30_000,
) : TranscribeProvider {
    var calls: Int = 0
        private set

    override suspend fun transcribe(audio: ByteArray, lang: String): AiResult<Transcript> {
        calls++
        if (delayMs > 0) delay(delayMs)
        if (audio.isEmpty()) return AiResult.Failed("empty_audio", "Audio is empty")
        val duration = (Wav.parse(audio)?.durationMs ?: fallbackDurationMs).coerceAtLeast(1)
        val step = segmentMs.coerceAtLeast(1)
        val pool = lines.ifEmpty { FakeFixtures.pool(lang) }
        val offset = if (lines.isNotEmpty()) 0 else {
            ((fakeSeed(lang, audio.size.toString(), audio.take(64).joinToString(",")) ushr 1) % pool.size).toInt()
        }
        val segments = (0 until ((duration + step - 1) / step).toInt()).map { i ->
            TranscriptSegment(
                startMs = i * step,
                endMs = minOf(duration, (i + 1) * step),
                text = pool[(offset + i) % pool.size],
            )
        }
        return AiResult.Ok(Transcript(lang, segments), Usage(model = MODEL, latencyMs = delayMs))
    }

    companion object {
        const val MODEL = "fake-transcribe"
    }
}
