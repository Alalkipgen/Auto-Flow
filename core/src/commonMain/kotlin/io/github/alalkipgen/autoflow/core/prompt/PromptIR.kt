package io.github.alalkipgen.autoflow.core.prompt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Model-neutral prompt for one shot (specs/prompt-compiler.md §2). English only; tokens are already expanded.
 * Stored as a `pr` artifact with inputs `spec` and `globalStyle`.
 */
@Serializable
@SerialName("PromptIR")
data class PromptIR(
    val schemaVersion: Int = SCHEMA_VERSION,
    val shot: Int,
    val subject: String,
    val action: String,
    val setting: String,
    val camera: String,
    val lighting: String,
    val mood: String,
    /** GlobalStyle visual, palette, lighting, camera (non-empty parts, in that order). */
    val style: String,
    /** Descriptions of the shot's characters that were not already inlined through a `{{TOKEN}}`. */
    val characters: List<String>,
    val negative: String,
    /** e.g. `"9:16"`. */
    val ratio: String,
    val durationS: Int,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}
