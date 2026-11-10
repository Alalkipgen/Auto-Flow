package io.github.alalkipgen.autoflow.ai

import io.github.alalkipgen.autoflow.core.model.Ratio
import kotlin.jvm.JvmInline
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A JSON Schema object sent as structured-output schema (Gemini `responseJsonSchema`). */
@JvmInline
value class JsonSchema(val json: JsonObject)

/** Structured text generation. The returned JSON is parsed but not yet validated against domain types. */
interface TextProvider {
    suspend fun json(prompt: String, schema: JsonSchema, system: String? = null): AiResult<JsonElement>
}

/** Image generation. The default implementation is manual (D-14) and returns [AiResult.Deferred]. */
interface ImageProvider {
    suspend fun image(prompt: String, ratio: Ratio, refImages: List<ByteArray> = emptyList()): AiResult<ByteArray>
}

interface TtsProvider {
    suspend fun speak(text: String, voice: VoiceRef, lang: String, style: String? = null): AiResult<ByteArray>
    val capabilities: TtsCapabilities
}

interface TranscribeProvider {
    suspend fun transcribe(audio: ByteArray, lang: String): AiResult<Transcript>
}

/** Provider-specific voice id (a designed voice name, or a replicated voice id when available). */
@JvmInline
value class VoiceRef(val id: String)

enum class Availability { AVAILABLE, UNAVAILABLE, UNKNOWN }

data class TtsCapabilities(
    val replication: Availability = Availability.UNKNOWN,
    val burmese: Boolean = false,
    val designedVoices: Boolean = false,
)

data class Transcript(val lang: String, val segments: List<TranscriptSegment>)

data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String)
