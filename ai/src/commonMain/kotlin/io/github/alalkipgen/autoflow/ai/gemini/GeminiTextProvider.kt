package io.github.alalkipgen.autoflow.ai.gemini

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.retry.Attempt
import io.github.alalkipgen.autoflow.ai.retry.JsonWire
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
    /** F-4: Chatbox AI sends the same calls as OpenAI-compatible chat completions. */
    private val wire: JsonWire = GeminiJsonWire(http, baseUrl),
) : TextProvider {
    override suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> {
        val key = apiKey()?.trim().orEmpty()
        if (key.isEmpty()) return AiResult.Failed("no_api_key", wire.noKey)
        val body = wire.body(prompt, schema, system, temperature)
        return chain.run(candidates) { model -> attempt(model, key, body) }
    }

    private suspend fun attempt(model: String, key: String, body: String): Attempt<JsonElement> =
        wire.post(model, key, body)

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }
}
