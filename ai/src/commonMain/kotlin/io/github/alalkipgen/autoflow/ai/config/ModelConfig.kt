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
                    tts = config.tts.copy(
                        default = config.tts.default?.let { clean(listOf(it)).firstOrNull() },
                        candidates = clean(config.tts.candidates),
                    ),
                ),
            )
        } catch (e: IllegalArgumentException) { // includes SerializationException
            AiResult.Failed("invalid_config", "config/models.json: ${e.message}")
        }

        private fun clean(ids: List<String>) = ids.map { it.trim() }.filter { id -> id.any { it.isLetterOrDigit() } }.distinct()
    }
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

@Serializable
@SerialName("TtsModels")
data class TtsModels(
    /** Old single-model field; used when [candidates] is empty. */
    val default: String? = null,
    /** Tried in order (D-15 style chain, D-34). */
    val candidates: List<String> = emptyList(),
    val burmese: Boolean = false,
    val replication: String = "unknown",
) {
    val chain: List<String> get() = candidates.ifEmpty { listOfNotNull(default) }

    val replicationAvailability: Availability
        get() = when (replication.lowercase()) {
            "yes", "true", "available" -> Availability.AVAILABLE
            "no", "false", "unavailable" -> Availability.UNAVAILABLE
            else -> Availability.UNKNOWN
        }
}

/** Flow is manual (D-04); these are display hints only. */
@Serializable
@SerialName("FlowHints")
data class FlowHints(
    val creditsHint: Map<String, Int> = emptyMap(),
    val url: String = "https://labs.google/fx/tools/flow",
)
