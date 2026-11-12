package io.github.alalkipgen.autoflow.ai.gemini

import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.retry.Attempt
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.Json

/** Gemini `generateContent` REST shapes: request building and response/error classification for [Attempt]. */
internal object GeminiWire {
    /** [media] (mime type to bytes) goes inline before the prompt text (base64 `inlineData`). */
    fun request(prompt: String, schema: JsonSchema, system: String?, temperature: Double?, media: Pair<String, ByteArray>? = null): JsonObject = buildJsonObject {
        if (!system.isNullOrBlank()) {
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
        }
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    if (media != null) {
                        addJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", media.first)
                                put("data", Base64.encode(media.second))
                            }
                        }
                    }
                    addJsonObject { put("text", prompt) }
                }
            }
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseJsonSchema", schema.json)
            if (temperature != null) put("temperature", temperature)
        }
    }

    /**
     * Single-speaker speech request (D-34). Gemini 3.8 TTS takes `voiceConfig.voice` and a per-part
     * `speech_metadata.style`; older TTS models take `prebuiltVoiceConfig.voiceName` and no style.
     */
    fun speechRequest(model: String, text: String, voice: String, style: String?): JsonObject = buildJsonObject {
        val v38 = !model.startsWith("gemini-2.")
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    addJsonObject {
                        put("text", text)
                        if (v38 && !style.isNullOrBlank()) putJsonObject("speech_metadata") { put("style", style) }
                    }
                }
            }
        }
        putJsonObject("generationConfig") {
            putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
            putJsonObject("speechConfig") {
                putJsonObject("voiceConfig") {
                    if (v38) put("voice", voice) else putJsonObject("prebuiltVoiceConfig") { put("voiceName", voice) }
                }
            }
        }
    }

    /** One `generateContent` call; network failures are classified like HTTP errors (no exceptions escape). */
    suspend fun post(http: HttpClient, baseUrl: String, model: String, key: String, body: String, audio: Boolean = false): Attempt<JsonElement> {
        val (status, text) = try {
            val response = http.post("${baseUrl.trimEnd('/')}/models/$model:generateContent") {
                header("x-goog-api-key", key)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            response.status.value to response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            return Attempt.Unavailable("timeout", "$model timed out")
        } catch (e: SocketTimeoutException) {
            return Attempt.Unavailable("timeout", "$model timed out")
        } catch (e: ConnectTimeoutException) {
            return Attempt.Fatal("network", "Cannot reach the Gemini API (connect timeout)")
        } catch (e: Exception) {
            return Attempt.Fatal("network", "Cannot reach the Gemini API: ${e.message ?: e::class.simpleName}")
        }
        return classify(status, text, audio)
    }

    /** [audio]: the answer is the first `inlineData` part (`{mimeType, data}`), not JSON text. */
    fun classify(status: Int, body: String, audio: Boolean = false): Attempt<JsonElement> {
        if (status == 200) return if (audio) audioSuccess(body) else success(body)
        val error = parse(body)?.obj("error")
        val message = error?.str("message")?.takeIf { it.isNotBlank() } ?: "HTTP $status from Gemini"
        return when (status) {
            429 -> when {
                NO_FREE_QUOTA.containsMatchIn(message) -> Attempt.Unavailable("no_free_quota", message)
                else -> Attempt.Limited(retryDelayMs(error), message)
            }
            500, 502, 503, 504 -> Attempt.Transient("http_$status", message)
            404 -> Attempt.Unavailable("model_unavailable", message)
            400, 401, 403 -> when {
                isKeyError(error, message) -> Attempt.Fatal("invalid_api_key", message)
                status == 400 -> Attempt.Fatal("bad_request", message)
                else -> Attempt.Fatal("permission_denied", message)
            }
            else -> Attempt.Fatal("http_$status", message)
        }
    }

    private fun audioSuccess(body: String): Attempt<JsonElement> {
        val root = parse(body) ?: return Attempt.Transient("invalid_response", "Gemini answer is not JSON")
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val blocked = root.obj("promptFeedback")?.str("blockReason")
        if (candidate == null) {
            return if (blocked != null) Attempt.Fatal("blocked", "Prompt blocked: $blocked")
            else Attempt.Transient("empty_response", "Gemini returned no candidates")
        }
        val data = (candidate.obj("content")?.get("parts") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.obj("inlineData") }
            .firstOrNull { !it.str("data").isNullOrEmpty() }
        val finish = candidate.str("finishReason")
        if (data == null) {
            return if (finish in BLOCKING_FINISH) Attempt.Fatal("blocked", "Answer blocked: $finish")
            else Attempt.Transient("empty_response", "Gemini returned no audio (finishReason=$finish)")
        }
        val usage = root.obj("usageMetadata")
        return Attempt.Success(data, usage?.int("promptTokenCount"), usage?.int("candidatesTokenCount"))
    }

    private fun success(body: String): Attempt<JsonElement> {
        val root = parse(body) ?: return Attempt.Transient("invalid_response", "Gemini answer is not JSON")
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val blocked = root.obj("promptFeedback")?.str("blockReason")
        if (candidate == null) {
            return if (blocked != null) Attempt.Fatal("blocked", "Prompt blocked: $blocked")
            else Attempt.Transient("empty_response", "Gemini returned no candidates")
        }
        val text = (candidate.obj("content")?.get("parts") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { (it["thought"] as? JsonPrimitive)?.booleanOrNull != true }
            .mapNotNull { it.str("text") }
            .joinToString("")
        val finish = candidate.str("finishReason")
        if (text.isBlank()) {
            return if (finish in BLOCKING_FINISH) Attempt.Fatal("blocked", "Answer blocked: $finish")
            else Attempt.Transient("empty_response", "Gemini returned no text (finishReason=$finish)")
        }
        val value = parseElement(text) ?: return Attempt.Transient("invalid_json", "Model output is not valid JSON (finishReason=$finish)")
        val usage = root.obj("usageMetadata")
        return Attempt.Success(value, usage?.int("promptTokenCount"), usage?.int("candidatesTokenCount"))
    }

    /** `RetryInfo.retryDelay` ("37s", "1.5s") in ms; null when absent. */
    internal fun retryDelayMs(error: JsonObject?): Long? =
        (error?.get("details") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("@type")?.endsWith("google.rpc.RetryInfo") == true }
            ?.str("retryDelay")
            ?.removeSuffix("s")
            ?.toDoubleOrNull()
            ?.let { (it * 1000).roundToLong() }

    private fun isKeyError(error: JsonObject?, message: String): Boolean {
        val reasons = (error?.get("details") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("reason") }
        return reasons.any { it.startsWith("API_KEY") } || message.contains("API key", ignoreCase = true)
    }

    private val NO_FREE_QUOTA = Regex("""\blimit: 0\b""")
    private val BLOCKING_FINISH = setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII")
    private val LENIENT = Json { ignoreUnknownKeys = true }

    private fun parse(text: String): JsonObject? = parseElement(text) as? JsonObject

    private fun parseElement(text: String): JsonElement? = try {
        LENIENT.parseToJsonElement(text)
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
