package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.model.Ratio
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Picture (5B-2, docs/specs/studio-v2.md §4): blur boxes over the movie (its own burned-in subtitles, logos), what
 * fills the space around the movie when its shape differs from the video's (black bars, the movie blurred, a colour,
 * or a background image — 5B-2 image background, D-51), and whether the captions sit on the lowest blur box. Mask
 * rects are 0–1 of the **movie picture** (before looks), so they follow the movie whatever the output ratio is.
 */
@Serializable
@SerialName("RecapVisual")
data class RecapVisual(
    val masks: List<RecapMask> = emptyList(),
    val background: FrameBackground = FrameBackground.BARS,
    /** `#RRGGBB` for [FrameBackground.COLOUR]. */
    val backgroundColour: String = "#000000",
    /**
     * Relative path (from the project folder) to the background image for [FrameBackground.IMAGE]
     * (D-51 image background): copied into `artifacts/` by the UI before saving. Null = not set.
     */
    val backgroundImageFile: String? = null,
    /**
     * Relative path (from the project folder) to a watermark image overlaid on every frame in the bottom-right
     * corner (Brand Kit, 5B-3 D-52). Null = no watermark. Set by [RecapModel.applyBrandKit].
     */
    val watermarkFile: String? = null,
    /**
     * Where the watermark sits on the frame (D-56). Null = BOTTOM_RIGHT (legacy default).
     */
    val watermarkAnchor: WatermarkAnchor = WatermarkAnchor.BOTTOM_RIGHT,
    /**
     * Output video ratio for Movie Recap (D-56). Null = 9:16.
     * Stored here so it travels with the visual settings and Brand Kit.
     */
    val outputRatio: Ratio? = null,
    /** Captions are centred on the lowest mask (when there is one) instead of the usual bottom place. */
    val captionsOnMask: Boolean = true,
    /** Null leaves prior project JSON and the legacy 22% width unchanged. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val watermarkWidthPercent: Int? = null,
) {
    val watermarkPercent: Int get() = (watermarkWidthPercent ?: WatermarkSize.MEDIUM).coerceIn(WatermarkSize.MIN, WatermarkSize.MAX)

    /** Both native preview and render scale the image to this width without changing its aspect. */
    fun watermarkRect(): List<Float> = watermarkAnchor.rect(watermarkPercent / 100f, .14f * (watermarkPercent / 22f))

    /** The mask the captions sit on: the one whose centre is lowest, only when it is in the lower half. */
    val captionMask: RecapMask?
        get() = masks.filter { it.centerY >= 0.5f }.maxByOrNull { it.centerY }?.takeIf { captionsOnMask }
}

object WatermarkSize {
    const val MIN = 5
    const val SMALL = 13
    const val MEDIUM = 22
    const val LARGE = 35
    const val MAX = 55
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
    val centerX: Float get() = rect.getOrElse(0) { 0f } + rect.getOrElse(2) { 0f } / 2
}

@Serializable
@SerialName("MaskPreset")
enum class MaskPreset(val label: String, val rect: List<Float>) {
    /** The movie's own subtitles. */
    @SerialName("BOTTOM") BOTTOM("Bottom strip (subtitles)", listOf(0f, 0.8f, 1f, 0.17f)),
    @SerialName("TOP") TOP("Top strip", listOf(0f, 0.03f, 1f, 0.12f)),
    @SerialName("LOGO_TL") LOGO_TL("Logo top-left", listOf(0.02f, 0.03f, 0.22f, 0.13f)),
    @SerialName("LOGO_TR") LOGO_TR("Logo top-right", listOf(0.76f, 0.03f, 0.22f, 0.13f)),
    /**
     * A box the user places anywhere (5B-2 "custom box"): starts in the upper middle (centre above half, so captions
     * stay in their usual place until it is moved into the lower half), then drag / pinch / the buttons.
     */
    @SerialName("CUSTOM") CUSTOM("Custom box", listOf(0.3f, 0.3f, 0.4f, 0.2f)),
    ;

    /** Full-width strips move only up / down; the other boxes also move sideways and change width. */
    val isStrip: Boolean get() = this == BOTTOM || this == TOP
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
    /** A still image file, see [RecapVisual.backgroundImageFile] (D-51). */
    @SerialName("IMAGE") IMAGE("Background image"),
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

    /** Puts the box of [preset] at [rect] (a drag / pinch on the live preview, 5A-8), kept inside the picture. */
    fun setRect(v: RecapVisual, preset: MaskPreset, rect: List<Float>): RecapVisual =
        if (rect.size != 4 || rect.any { !it.isFinite() }) v else edit(v, preset) { rect }

    /** Moves the box of [preset] by [dy] (0–1 of the picture), kept inside the picture. */
    fun move(v: RecapVisual, preset: MaskPreset, dy: Float): RecapVisual = edit(v, preset) { r -> listOf(r[0], r[1] + dy, r[2], r[3]) }

    /** Moves the box of [preset] sideways by [dx] (0–1 of the picture), kept inside the picture. */
    fun moveX(v: RecapVisual, preset: MaskPreset, dx: Float): RecapVisual = edit(v, preset) { r -> listOf(r[0] + dx, r[1], r[2], r[3]) }

    /** Makes the box of [preset] wider (+) or narrower (−) by [dw] around its centre, kept inside the picture. */
    fun resizeW(v: RecapVisual, preset: MaskPreset, dw: Float): RecapVisual =
        edit(v, preset) { r -> listOf(r[0] - dw / 2, r[1], r[2] + dw, r[3]) }

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

    /** [outputY] across: where a point at [movieX] (0–1 of the movie picture) lands (0–1 of the output width). */
    fun outputX(movieX: Float, movieW: Int, movieH: Int, outW: Int, outH: Int, zoom: Boolean, fill: Boolean): Float =
        outputY(movieX, movieH, movieW, outH, outW, zoom, fill)

    /** The inverse of [outputY]: a height in the output frame (0–1) in 0–1 of the movie picture (outside 0–1 = on the bars). */
    fun movieY(outputY: Float, movieW: Int, movieH: Int, outW: Int, outH: Int, zoom: Boolean, fill: Boolean): Float {
        val y = if (movieW <= 0 || movieH <= 0) outputY else {
            val mw = movieW.toFloat()
            val mh = movieH.toFloat()
            val s = if (fill) maxOf(outW / mw, outH / mh) else minOf(outW / mw, outH / mh)
            val ph = mh * s
            (outputY * outH - (outH - ph) / 2) / ph
        }
        return if (zoom) y * 0.9f + 0.05f else y
    }

    /** The inverse of [outputX]. */
    fun movieX(outputX: Float, movieW: Int, movieH: Int, outW: Int, outH: Int, zoom: Boolean, fill: Boolean): Float =
        movieY(outputX, movieH, movieW, outH, outW, zoom, fill)

    /** Output pixels per whole movie-picture height (the picture is fitted / filled, and 1 / 0.9 larger with Zoom). */
    fun pictureHeightPx(movieW: Int, movieH: Int, outW: Int, outH: Int, zoom: Boolean, fill: Boolean): Float {
        if (movieW <= 0 || movieH <= 0) return outH.toFloat()
        val mw = movieW.toFloat()
        val mh = movieH.toFloat()
        val s = if (fill) maxOf(outW / mw, outH / mh) else minOf(outW / mw, outH / mh)
        return mh * s / (if (zoom) 0.9f else 1f)
    }
}

/** How the burned-in captions look (5B-8): size, colour, outline or a dark box behind them. */
@Serializable
@SerialName("CaptionStyle")
data class CaptionStyle(
    val size: CaptionSize = CaptionSize.M,
    /** `#RRGGBB` preset colour; overridden by [customColour] when set. */
    val colour: String = "#FFFFFF",
    /** A see-through dark box behind the text instead of an outline. */
    val box: Boolean = false,
    /**
     * Free `#RRGGBB` hex override (D-56). When non-null takes precedence over [colour].
     * Allows any colour beyond the presets.
     */
    val customColour: String? = null,
    /**
     * Custom size multiplier (D-56), 0.5–3.0. When non-null overrides [size]'s factor.
     */
    val customSizeFactor: Float? = null,
    /**
     * Outline / stroke width in pixels at 1080p (D-57). 0 = no outline (default).
     * Scales proportionally with the output resolution.
     */
    val strokeWidth: Int = 0,
    /**
     * Outline colour `#RRGGBB` (D-57). Used when [strokeWidth] > 0.
     */
    val strokeColour: String = "#000000",
) {
    /** The effective colour: customColour if set, else colour. */
    val effectiveColour: String get() = customColour?.takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) } ?: colour
    /** The effective size factor: customSizeFactor if set, else size.factor. */
    val effectiveSizeFactor: Double get() = customSizeFactor?.toDouble()?.coerceIn(0.5, 3.0) ?: size.factor

    companion object {
        /** Preset colours shown as chips. */
        val COLOURS = listOf(
            "White"  to "#FFFFFF",
            "Yellow" to "#FFE14D",
            "Cyan"   to "#7FE7FF",
            "Red"    to "#FF4444",
            "Orange" to "#FF9900",
            "Green"  to "#44FF88",
            "Blue"   to "#4499FF",
            "Purple" to "#CC77FF",
            "Black"  to "#111111",
        )
        /** Preset stroke colours. */
        val STROKE_COLOURS = listOf(
            "Black"  to "#000000",
            "White"  to "#FFFFFF",
            "Red"    to "#FF4444",
            "Yellow" to "#FFE14D",
        )
    }
}

@Serializable
@SerialName("CaptionSize")
enum class CaptionSize(val label: String, val factor: Double) {
    @SerialName("S") S("Small", 0.85),
    @SerialName("M") M("Medium", 1.0),
    @SerialName("L") L("Large", 1.2),
}

/**
 * Where the watermark image is anchored on the output frame (D-56).
 * [wmW] and [wmH] are the watermark's fraction of the frame width/height (e.g. 0.22, 0.14).
 */
@Serializable
@SerialName("WatermarkAnchor")
enum class WatermarkAnchor(val label: String) {
    @SerialName("top_left")     TOP_LEFT("↖ Top-left"),
    @SerialName("top_right")    TOP_RIGHT("↗ Top-right"),
    @SerialName("bottom_left")  BOTTOM_LEFT("↙ Bottom-left"),
    @SerialName("bottom_right") BOTTOM_RIGHT("↘ Bottom-right"),
    @SerialName("center")       CENTER("⊙ Center");

    /** Returns `[x, y, w, h]` in 0–1 of the output frame for a watermark of size [wmW] × [wmH]. */
    fun rect(wmW: Float = 0.22f, wmH: Float = 0.14f): List<Float> {
        val pad = 0.02f
        val x = when (this) {
            TOP_LEFT, BOTTOM_LEFT -> pad
            TOP_RIGHT, BOTTOM_RIGHT -> 1f - wmW - pad
            CENTER -> (1f - wmW) / 2f
        }
        val y = when (this) {
            TOP_LEFT, TOP_RIGHT -> pad
            BOTTOM_LEFT, BOTTOM_RIGHT -> 1f - wmH - pad
            CENTER -> (1f - wmH) / 2f
        }
        return listOf(x, y, wmW, wmH)
    }
}
