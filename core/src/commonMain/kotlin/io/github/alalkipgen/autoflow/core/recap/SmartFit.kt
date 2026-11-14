package io.github.alalkipgen.autoflow.core.recap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Smart Fit: how to handle a block where `take + 0.4 s > block length` (5A-8, studio-v2.md §8).
 * The choices are shown in order; the owner picks one per block. Saved in `recap.fit`.
 * Default = EXTEND (the movie clip runs on under the voice).
 */
@Serializable
@SerialName("FitChoice")
enum class FitChoice {
    /** Movie clip runs on past the cut point (spec §6, D-33). */
    @SerialName("EXTEND") EXTEND,

    /** Add the following movie scene's clip to this block. */
    @SerialName("NEXT_RANGE") NEXT_RANGE,

    /** Speed up the voice (atempo, ≤ maxVoiceSpeed). */
    @SerialName("SPEED") SPEED,

    /** Slow down the picture (setpts, ≥ maxVideoSlow). */
    @SerialName("SLOW_VIDEO") SLOW_VIDEO,

    /** Hold the last frame of the movie clip. */
    @SerialName("FREEZE") FREEZE,
}

/**
 * Smart Fit config from `config/recap.json` (studio-v2.md §8).
 * 1.0 = disabled (no speed-up / no slow-down) until spike S8.
 */
data class SmartFitConfig(
    val maxVoiceSpeed: Float = 1.0f,
    val maxVideoSlow: Float = 1.0f,
    val silenceGapMs: Int = 350,
)
