package io.github.alalkipgen.autoflow.ai.retry

import io.github.alalkipgen.autoflow.ai.JsonSchema
import kotlinx.serialization.json.JsonElement

/**
 * One structured-JSON request shape of a model service (F-4): Gemini `generateContent` or an OpenAI-compatible chat
 * completion (Chatbox AI). The text and transcribe providers keep their prompts and answer checks and only swap the wire.
 */
interface JsonWire {
    /** What a call without a key fails with, e.g. "Add a Gemini API key in Settings". */
    val noKey: String

    /** The request body, built once per call (audio is base64-encoded once) and sent to each candidate model. */
    fun body(prompt: String, schema: JsonSchema, system: String?, temperature: Double?, media: Pair<String, ByteArray>? = null): String

    /** One request to [model]; network and HTTP failures are classified, no exception escapes. */
    suspend fun post(model: String, key: String, body: String): Attempt<JsonElement>
}
