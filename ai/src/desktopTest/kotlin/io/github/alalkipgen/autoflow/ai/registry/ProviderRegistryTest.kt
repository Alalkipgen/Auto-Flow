package io.github.alalkipgen.autoflow.ai.registry

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.Availability
import io.github.alalkipgen.autoflow.ai.ImageProvider
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.TtsProvider
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.ai.config.ImageMode
import io.github.alalkipgen.autoflow.ai.config.ModelConfig
import io.github.alalkipgen.autoflow.ai.config.TextModels
import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.manual.ManualImageProvider
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.koin.dsl.koinApplication
import org.koin.dsl.module

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderRegistryTest {
    private class Host(var fake: Boolean) : AiHost {
        val clipboard = mutableListOf<String>()
        val opened = mutableListOf<String>()
        override fun useFakeProviders() = fake
        override suspend fun apiKey() = "k"
        override fun copyToClipboard(text: String) { clipboard += text }
        override fun openUrl(url: String) { opened += url }
    }

    private val schema = JsonSchema(Json.parseToJsonElement("""{"type":"object","properties":{"themeMy":{"type":"string"}}}""").jsonObject)
    private val config = ModelConfig(text = TextModels(candidates = listOf("gemini-3.5-flash")))
    private var httpCalls = 0

    /** Mock Gemini on the test scheduler (virtual time, so the 120 s attempt timeout cannot fire early). */
    private fun TestScope.http() = lazy {
        HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler {
                    httpCalls++
                    respond("""{"candidates":[{"content":{"parts":[{"text":"{\"themeMy\":\"x\"}"}]}}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
    }

    @Test
    fun settingsFlagSwitchesBetweenFakesAndGeminiPerCall() = runTest {
        val host = Host(fake = true)
        val http = http()
        val registry = ProviderRegistry(host, config, http = http, fakeDelayMs = 0)
        assertEquals(FakeTextProvider.MODEL, assertIs<AiResult.Ok<*>>(registry.text.json("p", schema)).usage!!.model)
        assertEquals(false, http.isInitialized(), "no HTTP client in fake mode")

        host.fake = false
        assertEquals("gemini-3.5-flash", assertIs<AiResult.Ok<*>>(registry.text.json("p", schema)).usage!!.model)
        assertEquals(1, httpCalls)
        registry.close()
    }

    @Test
    fun keyTestAlwaysCallsGeminiEvenInFakeMode() = runTest {
        val registry = ProviderRegistry(Host(fake = true), config, http = http(), fakeDelayMs = 0)
        assertEquals("gemini-3.5-flash", assertIs<AiResult.Ok<Unit>>(registry.testGeminiKey()).usage!!.model)
        assertEquals(1, httpCalls)
        registry.close()
    }

    @Test
    fun realImagesAreManualAndRealSpeechIsUnavailable() = runTest {
        val host = Host(fake = false)
        val registry = ProviderRegistry(host, config, http = http(), fakeDelayMs = 0)
        val deferred = assertIs<AiResult.Deferred>(registry.image.image("Shot 1 prompt", Ratio.R9_16))
        assertEquals(ManualImageProvider.INSTRUCTION, deferred.instruction)
        assertEquals(listOf("Shot 1 prompt"), host.clipboard)
        assertEquals(listOf(config.image.manualUrl), host.opened)
        assertEquals("unavailable", assertIs<AiResult.Failed>(registry.tts.speak("x", VoiceRef("v"), "my")).code)
        assertEquals("unavailable", assertIs<AiResult.Failed>(registry.transcribe.transcribe(byteArrayOf(1), "my")).code)
        assertEquals(ImageMode.MANUAL, registry.capability(imageApiProbe = Availability.AVAILABLE).imageMode)

        host.fake = true
        assertIs<AiResult.Ok<ByteArray>>(registry.image.image("Shot 1 prompt", Ratio.R9_16))
        assertTrue(registry.tts.capabilities.burmese)
        assertEquals(ImageMode.API, registry.capability().imageMode)
    }

    @Test
    fun koinModuleResolvesStableDelegates() {
        val host = Host(fake = true)
        val app = koinApplication {
            modules(module { single<AiHost> { host }; single { config } }, aiModule)
        }
        val registry = app.koin.get<ProviderRegistry>()
        assertSame(registry.text, app.koin.get<TextProvider>())
        assertSame(registry.image, app.koin.get<ImageProvider>())
        assertSame(registry.tts, app.koin.get<TtsProvider>())
        app.close()
    }
}
