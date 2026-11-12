package io.github.alalkipgen.autoflow.ai.gemini

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.Availability
import io.github.alalkipgen.autoflow.ai.TtsCapabilities
import io.github.alalkipgen.autoflow.ai.TtsProvider
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.ai.codec.Wav
import io.github.alalkipgen.autoflow.ai.retry.Attempt
import io.github.alalkipgen.autoflow.ai.retry.ModelChain
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Gemini TTS (D-34, docs/specs/recap.md §5): one prebuilt voice, [candidates] tried in order (models.json
 * `tts.candidates`). The answer is always returned as a WAV file: Gemini 3.8 sends a complete WAV, older models sent
 * headerless 16-bit PCM (24 kHz mono), which is wrapped here.
 */
class GeminiTtsProvider(
    private val http: HttpClient,
    private val apiKey: suspend () -> String?,
    private val candidates: List<String>,
    override val capabilities: TtsCapabilities = TtsCapabilities(replication = Availability.UNAVAILABLE, burmese = true),
    private val chain: ModelChain = ModelChain(),
    private val baseUrl: String = GeminiTextProvider.DEFAULT_BASE_URL,
) : TtsProvider {
    override suspend fun speak(text: String, voice: VoiceRef, lang: String, style: String?): AiResult<ByteArray> {
        val key = apiKey()?.trim().orEmpty()
        if (key.isEmpty()) return AiResult.Failed("no_api_key", "Add a Gemini API key in Settings")
        if (text.isBlank()) return AiResult.Failed("empty_text", "There is no text to speak")
        return chain.run(candidates) { model ->
            val body = GeminiWire.speechRequest(model, text.trim(), voice.id, style).toString()
            when (val a: Attempt<JsonElement> = GeminiWire.post(http, baseUrl, model, key, body, audio = true)) {
                is Attempt.Success -> wav(a.value)?.let { Attempt.Success(it, a.promptTokens, a.outputTokens) }
                    ?: Attempt.Transient("invalid_audio", "$model returned audio that cannot be read")
                is Attempt.Transient -> a
                is Attempt.Limited -> a
                is Attempt.Unavailable -> a
                is Attempt.Fatal -> a
            }
        }
    }

    companion object {
        /** Gemini TTS raw PCM rate. */
        const val PCM_RATE = 24_000

        /** `{mimeType, data}` → WAV bytes; null when the data is not base64 or not audio. */
        internal fun wav(inline: JsonElement): ByteArray? {
            val o = inline as? JsonObject ?: return null
            val data = (o["data"] as? JsonPrimitive)?.contentOrNull ?: return null
            val bytes = try {
                Base64.decode(data)
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (bytes.isEmpty()) return null
            if (Wav.parse(bytes) != null) return bytes
            val mime = (o["mimeType"] as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
            if (!mime.startsWith("audio/l16") && !mime.startsWith("audio/pcm")) return null
            val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: PCM_RATE
            val samples = ShortArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
            return Wav.pcm16Mono(samples, rate)
        }
    }
}
