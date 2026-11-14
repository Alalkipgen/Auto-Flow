package io.github.alalkipgen.autoflow.core.recap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Quick Recap wizard state stored in `recap.quick` (5B-11, D-47, studio-v2.md §10).
 * Feature-flagged by `quickRecap`. A normal Recap project; this field tracks wizard progress.
 */
@Serializable
@SerialName("QuickRecap")
data class QuickRecap(
    val stage: QuickRecapStage = QuickRecapStage.SCENES,
    val targetMs: Long = Recap.DEFAULT_TARGET_MS,
    val style: RecapStyle = RecapStyle.PLAIN,
)

/**
 * Stages of the Quick Recap pipeline (5B-11):
 * SCENES → scene detection done.
 * PICK   → AI picked scenes (`recapPick` text stage).
 * SCRIPT → script generated (`source: "quick"`).
 * REVIEW → waiting for user approval or edits.
 * VOICE  → TTS_ALL job running.
 * RENDER → render job running.
 * DONE   → export complete.
 */
@Serializable
@SerialName("QuickRecapStage")
enum class QuickRecapStage {
    @SerialName("SCENES") SCENES,
    @SerialName("PICK") PICK,
    @SerialName("SCRIPT") SCRIPT,
    @SerialName("REVIEW") REVIEW,
    @SerialName("VOICE") VOICE,
    @SerialName("RENDER") RENDER,
    @SerialName("DONE") DONE,
}
