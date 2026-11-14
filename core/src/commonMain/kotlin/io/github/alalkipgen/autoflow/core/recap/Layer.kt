package io.github.alalkipgen.autoflow.core.recap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One composited layer on the output frame (spec §4, D-37).
 * [rect] = [x, y, w, h] in 0.0–1.0 of the output frame; BORDER and BACKGROUND ignore it.
 * [fromMs] / [toMs] = output time range (null toMs = to the end of the render).
 */
@Serializable
@SerialName("Layer")
data class Layer(
    val id: String,
    val type: LayerType,
    /** [x, y, w, h] in 0–1 of the output frame. Ignored by BORDER and BACKGROUND. */
    val rect: List<Float> = listOf(0f, 0f, 1f, 1f),
    val fromMs: Long = 0,
    val toMs: Long? = null,
    val params: LayerParams = LayerParams(),
)

@Serializable
@SerialName("LayerType")
enum class LayerType {
    @SerialName("BLUR") BLUR,
    @SerialName("IMAGE") IMAGE,
    @SerialName("TEXT") TEXT,
    @SerialName("BORDER") BORDER,
    @SerialName("BACKGROUND") BACKGROUND,
}

/**
 * Per-type parameters. Unused fields for a given [LayerType] are ignored.
 * - BLUR: [sigma], [feather]
 * - IMAGE: [image] (artifact id), [opacity]
 * - TEXT: [textMy], [style]
 * - BORDER: [px], [colour]
 * - BACKGROUND: [mode], [image], [colour]
 */
@Serializable
@SerialName("LayerParams")
data class LayerParams(
    // BLUR
    val sigma: Float = 20f,
    val feather: Float = 0f,
    // IMAGE
    val image: String? = null,
    val opacity: Float = 1f,
    // TEXT
    val textMy: String = "",
    val style: String = "",
    // BORDER
    val px: Int = 4,
    val colour: String = "#000000",
    // BACKGROUND
    val mode: BackgroundMode = BackgroundMode.BLUR,
)

@Serializable
@SerialName("BackgroundMode")
enum class BackgroundMode {
    @SerialName("BLUR") BLUR,
    @SerialName("IMAGE") IMAGE,
    @SerialName("COLOUR") COLOUR,
}
