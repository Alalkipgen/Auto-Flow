package io.github.alalkipgen.autoflow.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `config/tools.json` (D-84, specs/quick-tools.md §10): tunables of the Phase 7 tools. The defaults here equal the
 * shipped file, so a missing or broken file changes nothing; unknown keys are ignored (a newer file must not break start-up).
 */
@Serializable
@SerialName("ToolsConfig")
data class ToolsConfig(
    val schemaVersion: Int = SCHEMA_VERSION,
    val transcribe: Transcribe = Transcribe(),
    val translate: Translate = Translate(),
    val subtitles: Subtitles = Subtitles(),
    val tts: Tts = Tts(),
    val burn: Burn = Burn(),
    val dubbing: Dubbing = Dubbing(),
) {
    @Serializable
    data class Transcribe(
        val chunkMaxMs: Long = 600_000,
        val silenceWindowMs: Long = 30_000,
        val overlapMs: Long = 2_000,
        val languages: List<String> = listOf("auto", "en", "ko", "zh", "th", "ja", "hi", "my"),
    )

    @Serializable
    data class Translate(val batchLines: Int = 40, val contextLines: Int = 5, val style: String = "spoken")

    /** Subtitle checks and nudges; line length is measured like `CaptionChunks` (characters after joining spaces). */
    @Serializable
    data class Subtitles(val minCueMs: Long = 700, val maxCharsPerLine: Int = 42, val maxLines: Int = 2, val nudgeMs: Long = 100)

    @Serializable
    data class Tts(val maxCharsPerPart: Int = 500, val partGapMs: Long = 300, val m4aBitrate: String = "160k", val tones: JsonObject = TONES) {
        /** The TTS `style` text of a tone chip (`calm`, `lively`, `news`, `story`); null for no tone or an unknown one. */
        fun style(tone: String?): String? = tone?.let { (tones[it] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(String::isNotBlank) }

        companion object {
            val TONES = JsonObject(mapOf(
                "calm" to "Read calmly and warmly, at an easy pace.",
                "lively" to "Read in a lively, upbeat and energetic way.",
                "news" to "Read clearly and evenly, like a news presenter.",
                "story" to "Read like a storyteller, with warmth and gentle expression.",
            ).mapValues { JsonPrimitive(it.value) })
        }
    }

    @Serializable
    data class Burn(val x264Preset: String = "veryfast", val crf: Int = 20)

    @Serializable
    data class Dubbing(
        val minGapMs: Long = 100,
        val maxVoiceSpeed: Double = 1.0,
        val originalVolume: Double = 0.15,
        val maxSourceMs: Long = 600_000,
        val stemGroup: Int = 50,
    )

    companion object {
        const val SCHEMA_VERSION = 1
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** The shipped text, or the defaults when it is missing or cannot be read. */
        fun parse(text: String?): ToolsConfig =
            if (text.isNullOrBlank()) ToolsConfig()
            else try {
                json.decodeFromString(serializer(), text)
            } catch (e: IllegalArgumentException) { // includes SerializationException
                ToolsConfig()
            }
    }
}
