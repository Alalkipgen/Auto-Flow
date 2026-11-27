package io.github.alalkipgen.autoflow.ai.chatbox

import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.retry.Attempt
import io.github.alalkipgen.autoflow.ai.retry.JsonWire
import io.github.alalkipgen.autoflow.core.recap.AiRunTiming
import io.github.alalkipgen.autoflow.core.recap.AiTimingSample
import io.github.alalkipgen.autoflow.core.recap.AiTimingStage
import io.github.alalkipgen.autoflow.core.recap.timedAiStage
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.onDownload
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.io.encoding.Base64
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI-compatible `POST {baseUrl}/chat/completions` (F-4, D-93), used for Chatbox AI (`https://ai.chatboxai.app/v1`,
 * the license key as a Bearer token). The JSON Schema goes into the prompt and `response_format` asks for a JSON
 * object; a service that refuses `response_format` gets the same request once more without it. Audio goes in as
 * `input_audio` (base64 + format from the mime type). No temperature is sent: some models (GPT-5) take only their own.
 */
class ChatCompletionsWire(
    private val http: HttpClient,
    private val baseUrl: String,
    private val service: String = CHATBOX,
) : JsonWire {
    override val noKey: String = "Add a $service license key in Settings"

    override fun body(prompt: String, schema: JsonSchema, system: String?, temperature: Double?, media: Pair<String, ByteArray>?): String =
        request(prompt, schema, system, media).toString()

    override suspend fun post(model: String, key: String, body: String): Attempt<JsonElement> {
        if (baseUrl.isBlank()) return Attempt.Fatal("not_set_up", "$service is not set up in config/models.json (chatbox.baseUrl)")
        val first = send(model, key, body)
        if (first is Reply.Answer && first.status == 400 && FORMAT_REFUSED.containsMatchIn(first.text) && body.endsWith("$JSON_MODE}")) {
            return finish(send(model, key, body.removeSuffix("$JSON_MODE}") + "}"), model)
        }
        return finish(first, model)
    }

    private suspend fun finish(reply: Reply, model: String): Attempt<JsonElement> = when (reply) {
        is Reply.Failed -> reply.attempt
        is Reply.Answer -> classify(reply.status, reply.text, reply.retryAfterMs, model)
    }

    private sealed interface Reply {
        data class Answer(val status: Int, val text: String, val retryAfterMs: Long?) : Reply
        data class Failed(val attempt: Attempt<Nothing>) : Reply
    }

    private suspend fun send(model: String, key: String, body: String): Reply {
        val full = "{\"model\":${JsonPrimitive(model)}," + body.removePrefix("{")
        val trace = coroutineContext[AiRunTiming]
        val start = TimeSource.Monotonic.markNow()
        val first = MutableStateFlow(false)
        val uploaded = MutableStateFlow(false)
        var statusSeen: Int? = null
        val requestedBytes = if (trace != null) full.encodeToByteArray().size.toLong() else null
        return try {
            val response = http.post("${baseUrl.trimEnd('/')}/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody(full)
                if (trace != null) {
                    onUpload { sent, total ->
                        if (sent > 0 && sent == (total ?: requestedBytes) && uploaded.compareAndSet(false, true)) {
                            trace.add(AiTimingSample(AiTimingStage.UPLOAD, start.elapsedNow().inWholeMilliseconds, bytes = sent))
                        }
                    }
                    onDownload { received, _ ->
                        if (received > 0 && first.compareAndSet(false, true)) {
                            trace.add(AiTimingSample(AiTimingStage.FIRST_BYTE, start.elapsedNow().inWholeMilliseconds))
                        }
                    }
                }
            }
            statusSeen = response.status.value
            val retryAfter = response.headers[HttpHeaders.RetryAfter]?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            Reply.Answer(response.status.value, response.bodyAsText(), retryAfter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            Reply.Failed(Attempt.Unavailable("timeout", "$model timed out"))
        } catch (e: SocketTimeoutException) {
            Reply.Failed(Attempt.Unavailable("timeout", "$model timed out"))
        } catch (e: ConnectTimeoutException) {
            Reply.Failed(Attempt.Fatal("network", "Cannot reach $service (connect timeout)"))
        } catch (e: Exception) {
            Reply.Failed(Attempt.Fatal("network", "Cannot reach $service: ${e.message ?: e::class.simpleName}"))
        } finally {
            trace?.add(AiTimingSample(AiTimingStage.RESPONSE, start.elapsedNow().inWholeMilliseconds, bytes = requestedBytes, httpStatus = statusSeen))
        }
    }

    internal suspend fun classify(status: Int, body: String, retryAfterMs: Long?, model: String): Attempt<JsonElement> =
        timedAiStage(AiTimingStage.PARSE) { classifyNow(status, body, retryAfterMs, model) }

    internal fun classifyNow(status: Int, body: String, retryAfterMs: Long?, model: String): Attempt<JsonElement> {
        if (status == 200) return success(body, model)
        val root = parse(body)
        val error = root?.get("error")
        val message = ((error as? JsonObject)?.str("message") ?: (error as? JsonPrimitive)?.contentOrNull ?: root?.str("message"))
            ?.takeIf { it.isNotBlank() } ?: "HTTP $status from $service"
        val text = "$service: $message"
        return when {
            status == 401 -> Attempt.Fatal("invalid_api_key", text)
            status == 402 || (status == 403 || status == 429) && NO_POINTS.containsMatchIn(message) -> Attempt.Fatal("no_points", text)
            status == 404 -> Attempt.Unavailable("model_unavailable", text)
            (status == 400 || status == 403 || status == 422) && MODEL_REFUSED.containsMatchIn(message) -> Attempt.Unavailable("model_unavailable", text)
            status == 403 -> Attempt.Fatal("permission_denied", text)
            status == 413 -> Attempt.Fatal("too_big", "$service: the request is too big")
            status == 429 -> Attempt.Limited(retryAfterMs, text)
            status == 408 || status in 500..599 -> Attempt.Transient("http_$status", text)
            status == 400 || status == 422 -> Attempt.Fatal("bad_request", text)
            else -> Attempt.Fatal("http_$status", text)
        }
    }

    private fun success(body: String, model: String): Attempt<JsonElement> {
        val root = parse(body) ?: return Attempt.Transient("invalid_response", "$service answer is not JSON")
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return Attempt.Transient("empty_response", "$service returned no answer ($model)")
        val finish = choice.str("finish_reason")
        val content = (choice["message"] as? JsonObject)?.get("content")
        val text = when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.mapNotNull { (it as? JsonObject)?.takeIf { p -> p.str("type") in setOf(null, "text", "output_text") }?.str("text") }.joinToString("")
            else -> ""
        }
        if (text.isBlank()) {
            return if (finish == "content_filter") Attempt.Fatal("blocked", "Answer blocked: content_filter")
            else Attempt.Transient("empty_response", "$service returned no text (finish_reason=$finish)")
        }
        val value = jsonIn(text) ?: return Attempt.Transient("invalid_json", "Model output is not valid JSON (finish_reason=$finish)")
        val usage = root["usage"] as? JsonObject
        return Attempt.Success(value, usage?.int("prompt_tokens"), usage?.int("completion_tokens"))
    }

    companion object {
        const val CHATBOX = "Chatbox AI"

        /** Always the last key of [request], so a refused `response_format` can be cut off the built body. */
        private const val JSON_MODE = ",\"response_format\":{\"type\":\"json_object\"}"

        internal fun request(prompt: String, schema: JsonSchema, system: String?, media: Pair<String, ByteArray>?): JsonObject = buildJsonObject {
            val text = "$prompt\n\nAnswer with only one JSON value (no markdown, no other text) that matches this JSON Schema:\n${schema.json}"
            putJsonArray("messages") {
                if (!system.isNullOrBlank()) addJsonObject { put("role", "system"); put("content", system) }
                addJsonObject {
                    put("role", "user")
                    if (media == null) {
                        put("content", text)
                    } else {
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "input_audio")
                                putJsonObject("input_audio") {
                                    put("data", Base64.encode(media.second))
                                    put("format", audioFormat(media.first))
                                }
                            }
                            addJsonObject { put("type", "text"); put("text", text) }
                        }
                    }
                }
            }
            putJsonObject("response_format") { put("type", "json_object") }
        }

        /** `input_audio.format` for a mime type: wav, mp3, m4a (`audio/mp4`), aac, ogg, flac, else the subtype. */
        internal fun audioFormat(mime: String): String = when (val m = mime.lowercase().substringBefore(';').trim()) {
            "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
            "audio/mp3", "audio/mpeg" -> "mp3"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            else -> m.substringAfter('/')
        }

        /** The JSON in a model's text: as is, inside a ```json fence, or from the first `{`/`[` to the last `}`/`]`. */
        internal fun jsonIn(text: String): JsonElement? {
            val t = text.trim()
            parseElement(t)?.let { return it }
            FENCE.find(t)?.groupValues?.get(1)?.let { inner -> parseElement(inner.trim())?.let { return it } }
            val open = t.indexOfFirst { it == '{' || it == '[' }
            val close = t.indexOfLast { it == '}' || it == ']' }
            return if (open in 0 until close) parseElement(t.substring(open, close + 1)) else null
        }

        private val FENCE = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        private val FORMAT_REFUSED = Regex("response_format|json_object|json mode", RegexOption.IGNORE_CASE)
        private val MODEL_REFUSED = Regex("model", RegexOption.IGNORE_CASE)
        private val NO_POINTS = Regex("insufficient|quota|points|credit|balance|expired|subscription", RegexOption.IGNORE_CASE)
        private val LENIENT = Json { ignoreUnknownKeys = true }

        private fun parse(text: String): JsonObject? = parseElement(text) as? JsonObject

        private fun parseElement(text: String): JsonElement? = try {
            LENIENT.parseToJsonElement(text)
        } catch (e: Exception) {
            null
        }

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    }
}
