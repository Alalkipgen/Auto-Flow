package io.github.alalkipgen.autoflow.ai.registry

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.Availability
import io.github.alalkipgen.autoflow.ai.ImageProvider
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.Transcript
import io.github.alalkipgen.autoflow.ai.TranscribeProvider
import io.github.alalkipgen.autoflow.ai.TtsCapabilities
import io.github.alalkipgen.autoflow.ai.TtsProvider
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.ai.config.ImageMode
import io.github.alalkipgen.autoflow.ai.config.ModelCapability
import io.github.alalkipgen.autoflow.ai.config.ModelConfig
import io.github.alalkipgen.autoflow.ai.fake.FakeImageProvider
import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.fake.FakeTranscribeProvider
import io.github.alalkipgen.autoflow.ai.fake.FakeTtsProvider
import io.github.alalkipgen.autoflow.ai.gemini.GeminiTextProvider
import io.github.alalkipgen.autoflow.ai.gemini.GeminiTranscribeProvider
import io.github.alalkipgen.autoflow.ai.manual.ManualImageProvider
import io.github.alalkipgen.autoflow.ai.retry.ModelChain
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.JsonElement

/** What :ai needs from the host app; bound in the app modules from :platform (settings, secure storage, clipboard). */
interface AiHost {
    /** The settings flag: true → offline fakes, false → Gemini text + manual images. Read on every call. */
    fun useFakeProviders(): Boolean

    /** From SecureStorage; null or blank when not set. Never logged or persisted by :ai. */
    suspend fun apiKey(): String?

    fun copyToClipboard(text: String)

    fun openUrl(url: String)
}

/**
 * Picks fake or real providers per call from [AiHost.useFakeProviders], so switching the setting takes effect
 * without restarting. Real = [GeminiTextProvider] + [ManualImageProvider] (D-14) + [GeminiTranscribeProvider]
 * (audio input on the text models, D-31); TTS has no real implementation yet and answers `Failed("unavailable")`. The HTTP client is created on first real call.
 */
class ProviderRegistry(
    private val host: AiHost,
    val config: ModelConfig,
    usage: UsageCounter? = null,
    private val http: Lazy<HttpClient> = lazy { defaultAiHttpClient() },
    fakeDelayMs: Long = 400,
) {
    val usingFakes: Boolean get() = host.useFakeProviders()

    private val fakeText = FakeTextProvider(fakeDelayMs)
    private val fakeImage = FakeImageProvider(fakeDelayMs)
    private val fakeTts = FakeTtsProvider(fakeDelayMs)
    private val fakeTranscribe = FakeTranscribeProvider(fakeDelayMs)
    private val gemini by lazy {
        GeminiTextProvider(http.value, { host.apiKey() }, config.text.candidates, ModelChain(usage = usage))
    }
    private val geminiTranscribe by lazy {
        GeminiTranscribeProvider(http.value, { host.apiKey() }, config.text.candidates, ModelChain(usage = usage))
    }
    private val manualImage = ManualImageProvider(host::copyToClipboard, host::openUrl, config.image.manualUrl)

    val text: TextProvider = object : TextProvider {
        override suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> =
            (if (usingFakes) fakeText else gemini).json(prompt, schema, system)
    }

    val image: ImageProvider = object : ImageProvider {
        override suspend fun image(prompt: String, ratio: Ratio, refImages: List<ByteArray>): AiResult<ByteArray> =
            (if (usingFakes) fakeImage else manualImage).image(prompt, ratio, refImages)
    }

    val tts: TtsProvider = object : TtsProvider {
        override val capabilities: TtsCapabilities
            get() = if (usingFakes) fakeTts.capabilities else TtsCapabilities(replication = Availability.UNAVAILABLE)

        override suspend fun speak(text: String, voice: VoiceRef, lang: String, style: String?): AiResult<ByteArray> =
            if (usingFakes) fakeTts.speak(text, voice, lang, style) else unavailable("Speech")
    }

    val transcribe: TranscribeProvider = object : TranscribeProvider {
        override suspend fun transcribe(audio: ByteArray, lang: String, mime: String, lines: List<String>): AiResult<Transcript> =
            (if (usingFakes) fakeTranscribe else geminiTranscribe).transcribe(audio, lang, mime, lines)
    }

    /** What the UI may offer right now. Fakes can do everything offline. */
    fun capability(imageApiProbe: Availability = Availability.UNKNOWN): ModelCapability =
        if (usingFakes) {
            ModelCapability(
                textModels = listOf(FakeTextProvider.MODEL),
                imageMode = ImageMode.API,
                imageRatios = Ratio.entries,
                ttsBurmese = true,
                ttsReplication = Availability.UNAVAILABLE,
            )
        } else {
            ModelCapability.from(config, imageApiProbe).copy(imageMode = ImageMode.MANUAL) // GeminiImageProvider deferred (D-14)
        }

    /**
     * Settings → "Test": one tiny real Gemini request with the saved key, whatever the Fake/Real setting. It counts
     * toward today's usage like any other call. `RateLimited` still means the key itself was accepted.
     */
    suspend fun testGeminiKey(): AiResult<Unit> = when (val r = gemini.json(KEY_TEST_PROMPT, KEY_TEST_SCHEMA)) {
        is AiResult.Ok -> AiResult.Ok(Unit, r.usage)
        is AiResult.RateLimited -> r
        is AiResult.Failed -> r
        is AiResult.Deferred -> r
    }

    fun close() {
        if (http.isInitialized()) http.value.close()
    }

    private fun unavailable(what: String) =
        AiResult.Failed("unavailable", "$what is not available with real providers in this version")
}

private const val KEY_TEST_PROMPT = "Connection test. Reply with ok = true."
private val KEY_TEST_SCHEMA = JsonSchema(
    kotlinx.serialization.json.buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("object"))
        put("properties", kotlinx.serialization.json.buildJsonObject {
            put("ok", kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("boolean")) })
        })
        put("required", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("ok"))))
    },
)

/** Engine from the classpath (CIO on desktop, OkHttp on Android). [HttpTimeout] replaces the engines' short defaults. */
fun defaultAiHttpClient(): HttpClient = HttpClient {
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 120_000
        socketTimeoutMillis = 120_000
        connectTimeoutMillis = 20_000
    }
}
