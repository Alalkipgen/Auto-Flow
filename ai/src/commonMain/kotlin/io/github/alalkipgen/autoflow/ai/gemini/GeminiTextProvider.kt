package io.github.alalkipgen.autoflow.ai.gemini

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.retry.Attempt
import io.github.alalkipgen.autoflow.ai.retry.ModelChain
import io.ktor.client.HttpClient
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

    private suspend fun attempt(model: String, key: String, body: String): Attempt<JsonElement> =
        GeminiWire.post(http, baseUrl, model, key, body)

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }
}
