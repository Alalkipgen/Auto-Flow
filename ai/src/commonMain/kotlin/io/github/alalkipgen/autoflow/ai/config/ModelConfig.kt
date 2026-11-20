package io.github.alalkipgen.autoflow.ai.config

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.Availability
import io.github.alalkipgen.autoflow.core.model.Ratio
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `config/models.json` (specs/ai-providers.md, D-10). Model ids, quotas and credit costs are data, never code.
 * Values are hints: the loader is lenient, and callers must behave correctly when they are wrong or missing.
 */
@Serializable
@SerialName("ModelConfig")
data class ModelConfig(
    val text: TextModels = TextModels(),
    val image: ImageModels = ImageModels(),
    val tts: TtsModels = TtsModels(),
    val flow: FlowHints = FlowHints(),
    /** F-4 (D-93): the second AI service for text and transcription, paid with a Chatbox AI license key. */
    val chatbox: ChatboxConfig = ChatboxConfig(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Parses the file text. Unknown ratios and placeholder ids such as `"..."` are dropped, not fatal. */
        fun parse(text: String): AiResult<ModelConfig> = try {
            val config = json.decodeFromString(serializer(), text)
            AiResult.Ok(
                config.copy(
                    text = config.text.copy(candidates = clean(config.text.candidates)),
                    image = config.image.copy(candidates = clean(config.image.candidates)),
                    tts = config.tts,  // TtsCandidate ids are validated by the JSON deserializer
                    chatbox = config.chatbox.copy(text = clean(config.chatbox.text), transcribe = clean(config.chatbox.transcribe)),
                ),
            )
        } catch (e: IllegalArgumentException) { // includes SerializationException
            AiResult.Failed("invalid_config", "config/models.json: ${e.message}")
        }

        private fun clean(ids: List<String>) = ids.map { it.trim() }.filter { id -> id.any { it.isLetterOrDigit() } }.distinct()
    }
}

/**
 * Chatbox AI (F-4, D-93): an OpenAI-compatible API at [baseUrl] whose key is the Chatbox AI license key (a paid plan;
 * every call uses its points). [text] and [transcribe] are model chains like `text.candidates`; Transcribe sends audio,
 * so its models must take audio input (empty = [text]).
 */
@Serializable
@SerialName("ChatboxConfig")
data class ChatboxConfig(
    val baseUrl: String = "",
    val text: List<String> = emptyList(),
    val transcribe: List<String> = emptyList(),
    /** Where the owner finds the license key. */
    val keyUrl: String = "",
) {
    val available: Boolean get() = baseUrl.isNotBlank() && text.isNotEmpty()
    val transcribeModels: List<String> get() = transcribe.ifEmpty { text }
}

/** Stage model chain: tried in order at every stage call (D-15). The first entry is the default. */
@Serializable
@SerialName("TextModels")
data class TextModels(val candidates: List<String> = emptyList())

/** [provider]: `"manual"` (D-14, default) or `"gemini"` (capability-gated by a quota probe). */
@Serializable
@SerialName("ImageModels")
data class ImageModels(
    val provider: String = MANUAL,
    val candidates: List<String> = emptyList(),
    val ratios: List<String> = listOf("16:9", "9:16", "1:1", "4:5"),
    /** Opened by the manual path after the prompt is copied. */
    val manualUrl: String = "https://aistudio.google.com/",
) {
    /** Ratios the image path accepts; unknown labels are ignored. */
    val supportedRatios: List<Ratio> get() = ratios.mapNotNull { label -> Ratio.entries.firstOrNull { it.label == label } }

    companion object {
        const val MANUAL = "manual"
        const val GEMINI = "gemini"
    }
}

/**
 * One TTS model candidate from `config/models.json` `tts.candidates`.
 * [langs] = BCP-47 language codes this model can speak (voice lock language filter).
 */
@Serializable
@SerialName("TtsCandidate")
data class TtsCandidate(
    val id: String,
    val langs: List<String> = emptyList(),
) {
    /** True when this candidate can speak [lang] (BCP-47, e.g. "my", "en"). Empty [langs] = no language declared. */
    fun speaksLanguage(lang: String): Boolean = langs.any { it.equals(lang, ignoreCase = true) }
}

@Serializable
@SerialName("TtsModels")
data class TtsModels(
    /** Candidates with per-language declarations; replaces the old bare-string list (voice lock, D-35). */
    val candidates: List<TtsCandidate> = emptyList(),
    val replication: String = "unknown",
    /** Edge "Read aloud" voices (V-1b, D-86); empty = not offered. */
    val edge: EdgeTtsConfig = EdgeTtsConfig(),
) {
    /** Model ids that can speak [lang]; preserves declaration order. */
    fun chainFor(lang: String): List<String> = candidates.filter { it.speaksLanguage(lang) }.map { it.id }

    /** All model ids, regardless of language (used when language is unknown). */
    val allIds: List<String> get() = candidates.map { it.id }

    val replicationAvailability: Availability
        get() = when (replication.lowercase()) {
            "yes", "true", "available" -> Availability.AVAILABLE
            "no", "false", "unavailable" -> Availability.UNAVAILABLE
            else -> Availability.UNKNOWN
        }
}

/**
 * `tts.edge` (D-86): the unofficial Microsoft Edge "Read aloud" service — no API key. Every endpoint value is data:
 * Microsoft changes them, and then only this file changes. [model] is the voice-lock model id for these voices.
 */
@Serializable
@SerialName("EdgeTtsConfig")
data class EdgeTtsConfig(
    val model: String = "",
    val endpoint: String = "",
    val trustedClientToken: String = "",
    val secMsGecVersion: String = "",
    val origin: String = "",
    val userAgent: String = "",
    val outputFormat: String = "",
    /** Longest text (UTF-8 bytes, after XML escaping) one request may carry. */
    val maxTextBytes: Int = 4000,
    val voices: List<EdgeVoice> = emptyList(),
    /**
     * Requests per minute a voice job sends to Edge (F-3). The free service has no key and no published limit, so the
     * app keeps a polite pace of its own instead of Gemini's 3 rpm; a refusal (429) pauses the job (Resume goes on).
     */
    val rpm: Int = 20,
) {
    val available: Boolean get() = model.isNotBlank() && endpoint.isNotBlank() && trustedClientToken.isNotBlank() && voices.isNotEmpty()

    fun voicesFor(lang: String): List<EdgeVoice> = voices.filter { it.lang.equals(lang, ignoreCase = true) }
}

/** One Edge voice: [id] is Microsoft's short name, [name] what the chip shows. */
@Serializable
@SerialName("EdgeVoice")
data class EdgeVoice(val id: String, val name: String, val gender: String = "", val lang: String = "")

/** Flow is manual (D-04); these are display hints only. */
@Serializable
@SerialName("FlowHints")
data class FlowHints(
    val creditsHint: Map<String, Int> = emptyMap(),
    val url: String = "https://labs.google/fx/tools/flow",
)
