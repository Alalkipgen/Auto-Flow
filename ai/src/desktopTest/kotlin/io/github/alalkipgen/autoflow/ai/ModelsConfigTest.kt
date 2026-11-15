package io.github.alalkipgen.autoflow.ai

import io.github.alalkipgen.autoflow.ai.config.ModelConfig
import io.github.alalkipgen.autoflow.ai.registry.AiHost
import io.github.alalkipgen.autoflow.ai.registry.ProviderRegistry
import io.ktor.client.engine.mock.respond
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 5A-1 (D-35): the checked-in `config/models.json` offers only Burmese-capable TTS models for Burmese.
 *
 * Burmese-capable = listed with Burmese in Google's Gemini speech-generation language table
 * (ai.google.dev/gemini-api/docs/speech-generation, checked 2026-11-15: Burmese ✔ on gemini-3.8-flash-tts only;
 * 3.8 Flash-Lite TTS has no Burmese and gemini-2.5-flash-preview-tts is not in the table). When Google adds Burmese
 * to another model, add it to [BURMESE_TTS] together with its `"my"` in models.json.
 */
class ModelsConfigTest {
    private val config = assertIs<AiResult.Ok<ModelConfig>>(ModelConfig.parse(File("../config/models.json").readText())).value

    @Test fun burmeseTtsChainHasOnlyBurmeseCapableModels() {
        val chain = config.tts.chainFor("my")
        assertTrue(chain.isNotEmpty(), "at least one Burmese TTS model")
        assertEquals(emptyList(), chain - BURMESE_TTS, "models offered for Burmese that cannot speak it")
    }

    /** Settings → "Test Gemini TTS (S5)" speaks Burmese: every model it may call must be Burmese-capable. */
    @Test fun s5TestOnlyCallsBurmeseCapableModels() = kotlinx.coroutines.test.runTest {
        val models = mutableListOf<String>()
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine) {
            engine {
                dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                addHandler { req ->
                    models += req.url.encodedPath.substringAfterLast("/models/").substringBefore(":")
                    respond(
                        """{"error":{"code":404,"message":"model not found"}}""", io.ktor.http.HttpStatusCode.NotFound,
                        io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        }
        val host = object : AiHost {
            override fun useFakeProviders() = false
            override suspend fun apiKey(): String = "k"
            override fun copyToClipboard(text: String) {}
            override fun openUrl(url: String) {}
        }
        val registry = ProviderRegistry(host, config, http = lazy { http }, fakeDelayMs = 0)
        registry.testGeminiTts()
        assertTrue(models.isNotEmpty())
        assertEquals(emptyList(), models.distinct() - BURMESE_TTS, "S5 called a model that cannot speak Burmese")
    }

    private companion object {
        val BURMESE_TTS = listOf("gemini-3.8-flash-tts")
    }
}
