package io.github.alalkipgen.autoflow.ai.gemini

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.config.ModelConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Real API call. Skipped unless `-PliveAi=true` and `GEMINI_API_KEY` is set. Costs one free-tier request. */
class GeminiLiveTest {
    @Test
    fun burmeseStructuredOutput() {
        val key = System.getenv("GEMINI_API_KEY")
        if (System.getProperty("autoflow.liveAi") != "true" || key.isNullOrBlank()) {
            println("GeminiLiveTest skipped (run with -PliveAi=true and GEMINI_API_KEY)")
            return
        }
        val config = assertIs<AiResult.Ok<ModelConfig>>(ModelConfig.parse(File("../config/models.json").readText())).value
        val http = HttpClient(CIO) {
            engine { requestTimeout = 0 }
            install(HttpTimeout) { requestTimeoutMillis = 120_000 }
        }
        val schema = JsonSchema(
            Json.parseToJsonElement(
                """{"type":"object","properties":{"greetingMy":{"type":"string"}},"required":["greetingMy"]}""",
            ).jsonObject,
        )
        val result = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.withTimeout(10.minutes) {
                GeminiTextProvider(http, { key }, config.text.candidates)
                    .json("Write a one-line greeting in Burmese.", schema)
            }
        }
        http.close()
        println("GeminiLiveTest: $result")
        val ok = assertIs<AiResult.Ok<*>>(result)
        val greeting = (ok.value as kotlinx.serialization.json.JsonObject).getValue("greetingMy").jsonPrimitive.content
        assertTrue(greeting.any { it in '\u1000'..'\u109F' }, greeting)
    }
}
