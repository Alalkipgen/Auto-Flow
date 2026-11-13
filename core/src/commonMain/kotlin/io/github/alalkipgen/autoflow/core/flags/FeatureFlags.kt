package io.github.alalkipgen.autoflow.core.flags

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `config/features.json` (docs/specs/studio-v2.md §1, D-41), overridden by the app settings `featureOverrides`
 * (Self-test → "Preview features"). Only the UI reads flags; `:core` rules never depend on them.
 */
@Serializable
@SerialName("FeatureFlags")
data class FeatureFlags(
    val schemaVersion: Int = SCHEMA_VERSION,
    val studioV2: Boolean = false,
    val quickRecap: Boolean = false,
    val labs: Labs = Labs(),
) {
    /** Every flag by its override key: `studioV2`, `quickRecap`, `labs.voiceClone`, … */
    fun asMap(): Map<String, Boolean> = linkedMapOf(
        STUDIO_V2 to studioV2,
        QUICK_RECAP to quickRecap,
        "labs.voiceClone" to labs.voiceClone,
        "labs.dubbing" to labs.dubbing,
        "labs.vocalSeparation" to labs.vocalSeparation,
        "labs.smartReframe" to labs.smartReframe,
        "labs.autoCaptionPlacement" to labs.autoCaptionPlacement,
        "labs.wordKaraoke" to labs.wordKaraoke,
        "labs.wholeMovieTranscribe" to labs.wholeMovieTranscribe,
        "labs.edgeVoices" to labs.edgeVoices,
    )

    /** These flags with [overrides] applied; unknown keys are ignored (an old settings file must not break start-up). */
    fun withOverrides(overrides: Map<String, Boolean>): FeatureFlags {
        if (overrides.isEmpty()) return this
        fun v(key: String, current: Boolean) = overrides[key] ?: current
        return copy(
            studioV2 = v(STUDIO_V2, studioV2),
            quickRecap = v(QUICK_RECAP, quickRecap),
            labs = Labs(
                voiceClone = v("labs.voiceClone", labs.voiceClone),
                dubbing = v("labs.dubbing", labs.dubbing),
                vocalSeparation = v("labs.vocalSeparation", labs.vocalSeparation),
                smartReframe = v("labs.smartReframe", labs.smartReframe),
                autoCaptionPlacement = v("labs.autoCaptionPlacement", labs.autoCaptionPlacement),
                wordKaraoke = v("labs.wordKaraoke", labs.wordKaraoke),
                wholeMovieTranscribe = v("labs.wholeMovieTranscribe", labs.wholeMovieTranscribe),
                edgeVoices = v("labs.edgeVoices", labs.edgeVoices),
            ),
        )
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val STUDIO_V2 = "studioV2"
        const val QUICK_RECAP = "quickRecap"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        /** Lenient: a missing or broken file gives every flag off, never a crash. */
        fun parse(text: String?): FeatureFlags =
            if (text.isNullOrBlank()) FeatureFlags()
            else try {
                json.decodeFromString(serializer(), text)
            } catch (e: IllegalArgumentException) { // includes SerializationException
                FeatureFlags()
            }

        /** The bundled file overridden by the settings. */
        fun load(fileText: String?, overrides: Map<String, Boolean>): FeatureFlags = parse(fileText).withOverrides(overrides)
    }
}

/** Experimental features, off until their PLAN Labs item ships. */
@Serializable
@SerialName("Labs")
data class Labs(
    val voiceClone: Boolean = false,
    val dubbing: Boolean = false,
    val vocalSeparation: Boolean = false,
    val smartReframe: Boolean = false,
    val autoCaptionPlacement: Boolean = false,
    val wordKaraoke: Boolean = false,
    val wholeMovieTranscribe: Boolean = false,
    val edgeVoices: Boolean = false,
)
