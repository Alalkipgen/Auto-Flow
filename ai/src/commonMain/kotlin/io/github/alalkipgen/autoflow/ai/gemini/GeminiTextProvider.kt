package io.github.alalkipgen.autoflow.ai.gemini

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.retry.Attempt
import io.github.alalkipgen.autoflow.ai.retry.ModelChain
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
import kotlinx.serialization.json.JsonElement

/**
 * Gemini structured-output text generation (`responseMimeType: application/json` + `responseJsonSchema`) over the
 * stage's ordered [candidates] (D-15), with retries, fallbacks and usage counting delegated to [chain].
 * The API key is fetched per call from secure storage and sent only as the `x-goog-api-key` header.
 */
class GeminiTextProvider(
    private val http: HttpClient,
    private val apiKey: suspend () -> String?,
    private val candidates: List<String>,
    private val chain: ModelChain = ModelChain(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val temperature: Double? = 0.7,
) : TextProvider {
    override suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> {
        val key = apiKey()?.trim().orEmpty()
        if (key.isEmpty()) return AiResult.Failed("no_api_key", "Add a Gemini API key in Settings")
        val body = GeminiWire.request(prompt, schema, system, temperature).toString()
        return chain.run(candidates) { model -> attempt(model, key, body) }
    }

    private suspend fun attempt(model: String, key: String, body: String): Attempt<JsonElement> {
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
        return GeminiWire.classify(status, text)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }
}
