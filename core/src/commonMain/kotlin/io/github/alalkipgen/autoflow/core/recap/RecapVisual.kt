package io.github.alalkipgen.autoflow.core.recap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Picture (5B-2, docs/specs/studio-v2.md §4): blur boxes over the movie (its own burned-in subtitles, logos), what
 * fills the space around the movie when its shape differs from the video's (black bars, the movie blurred, a colour),
 * and whether the captions sit on the lowest blur box. Mask rects are 0–1 of the **movie picture** (before looks),
 * so they follow the movie whatever the output ratio is.
 */
@Serializable
@SerialName("RecapVisual")
data class RecapVisual(
    val masks: List<RecapMask> = emptyList(),
    val background: FrameBackground = FrameBackground.BARS,
    /** `#RRGGBB` for [FrameBackground.COLOUR]. */
    val backgroundColour: String = "#000000",
    /** Captions are centred on the lowest mask (when there is one) instead of the usual bottom place. */
    val captionsOnMask: Boolean = true,
) {
    /** The mask the captions sit on: the one whose centre is lowest, only when it is in the lower half. */
    val captionMask: RecapMask?
        get() = masks.filter { it.centerY >= 0.5f }.maxByOrNull { it.centerY }?.takeIf { captionsOnMask }
}

/** A blurred box, [rect] = `[x, y, w, h]` in 0–1 of the movie picture; [preset] names where it came from. */
@Serializable
@SerialName("RecapMask")
data class RecapMask(
    val preset: MaskPreset,
    val rect: List<Float>,
    val strength: MaskStrength = MaskStrength.STRONG,
) {
    val centerY: Float get() = rect.getOrElse(1) { 0f } + rect.getOrElse(3) { 0f } / 2
}

@Serializable
@SerialName("MaskPreset")
enum class MaskPreset(val label: String, val rect: List<Float>) {
    /** The movie's own subtitles. */
    @SerialName("BOTTOM") BOTTOM("Bottom strip (subtitles)", listOf(0f, 0.8f, 1f, 0.17f)),
    @SerialName("TOP") TOP("Top strip", listOf(0f, 0.03f, 1f, 0.12f)),
    @SerialName("LOGO_TL") LOGO_TL("Logo top-left", listOf(0.02f, 0.03f, 0.22f, 0.13f)),
    @SerialName("LOGO_TR") LOGO_TR("Logo top-right", listOf(0.76f, 0.03f, 0.22f, 0.13f)),
}

@Serializable
@SerialName("MaskStrength")
enum class MaskStrength(val label: String, val sigma: Int) {
    @SerialName("LIGHT") LIGHT("Light", 12),
    @SerialName("STRONG") STRONG("Strong", 32),
}

/** The space around the movie when its shape differs from the video's (Looks → Fill frame crops instead). */
@Serializable
@SerialName("FrameBackground")
enum class FrameBackground(val label: String) {
    @SerialName("BARS") BARS("Black bars"),
    @SerialName("BLUR") BLUR("Blurred movie"),
    @SerialName("COLOUR") COLOUR("Colour"),
}

/** Changes to [RecapVisual] the Recap screen makes; pure, so they are tested without the UI. */
object RecapVisuals {
    /** Colour chips for [FrameBackground.COLOUR]. */
    val COLOURS = listOf("Black" to "#000000", "White" to "#FFFFFF", "Grey" to "#2A2A2A", "Purple" to "#2B1640")

    /** Smallest mask side (3 % of the picture). */
    const val MIN_SIZE = 0.03f
    const val STEP = 0.02f

    /** Turns [preset] on (with its default box) or off. */
    fun toggle(v: RecapVisual, preset: MaskPreset): RecapVisual =
        if (v.masks.any { it.preset == preset }) v.copy(masks = v.masks.filterNot { it.preset == preset })
        else v.copy(masks = (v.masks + RecapMask(preset, preset.rect, v.masks.firstOrNull()?.strength ?: MaskStrength.STRONG))
            .sortedBy { it.preset.ordinal })

    fun strength(v: RecapVisual, s: MaskStrength): RecapVisual = v.copy(masks = v.masks.map { it.copy(strength = s) })

    /** Moves the box of [preset] by [dy] (0–1 of the picture), kept inside the picture. */
    fun move(v: RecapVisual, preset: MaskPreset, dy: Float): RecapVisual = edit(v, preset) { r -> listOf(r[0], r[1] + dy, r[2], r[3]) }

    /** Makes the box of [preset] taller (+) or shorter (−) by [dh] around its centre, kept inside the picture. */
    fun resize(v: RecapVisual, preset: MaskPreset, dh: Float): RecapVisual =
        edit(v, preset) { r -> listOf(r[0], r[1] - dh / 2, r[2], r[3] + dh) }

    private fun edit(v: RecapVisual, preset: MaskPreset, change: (List<Float>) -> List<Float>): RecapVisual =
        v.copy(masks = v.masks.map { m -> if (m.preset == preset) m.copy(rect = clamp(change(m.rect))) else m })

    /** Keeps `[x, y, w, h]` inside the picture with sides ≥ [MIN_SIZE], rounded to 0.001. */
    fun clamp(r: List<Float>): List<Float> {
        val w = r[2].coerceIn(MIN_SIZE, 1f)
        val h = r[3].coerceIn(MIN_SIZE, 1f)
        val x = r[0].coerceIn(0f, 1f - w)
        val y = r[1].coerceIn(0f, 1f - h)
        return listOf(x, y, w, h).map { kotlin.math.round(it * 1000) / 1000 }
    }

    /**
     * Where a point at [movieY] (0–1 of the movie picture) lands in the output frame (0–1 of its height): the movie
     * ([movieW] × [movieH], unknown = the output's shape) is zoomed when [zoom] (centre 90 %), then fitted inside
     * [outW] × [outH] (bars / background) or, with [fill], scaled to cover it and cropped.
     */
    fun outputY(movieY: Float, movieW: Int, movieH: Int, outW: Int, outH: Int, zoom: Boolean, fill: Boolean): Float {
        val y = if (zoom) (movieY - 0.05f) / 0.9f else movieY
        if (movieW <= 0 || movieH <= 0) return y
        val mw = movieW.toFloat()
        val mh = movieH.toFloat()
        val s = if (fill) maxOf(outW / mw, outH / mh) else minOf(outW / mw, outH / mh)
        val ph = mh * s
        val top = (outH - ph) / 2
        return (top + y * ph) / outH
    }
}

/** How the burned-in captions look (5B-8): size, colour, outline or a dark box behind them. */
@Serializable
@SerialName("CaptionStyle")
data class CaptionStyle(
    val size: CaptionSize = CaptionSize.M,
    /** `#RRGGBB`. */
    val colour: String = "#FFFFFF",
    /** A see-through dark box behind the text instead of an outline. */
    val box: Boolean = false,
) {
    companion object {
        val COLOURS = listOf("White" to "#FFFFFF", "Yellow" to "#FFE14D", "Cyan" to "#7FE7FF")
    }
}

@Serializable
@SerialName("CaptionSize")
enum class CaptionSize(val label: String, val factor: Double) {
    @SerialName("S") S("Small", 0.85),
    @SerialName("M") M("Medium", 1.0),
    @SerialName("L") L("Large", 1.2),
}
