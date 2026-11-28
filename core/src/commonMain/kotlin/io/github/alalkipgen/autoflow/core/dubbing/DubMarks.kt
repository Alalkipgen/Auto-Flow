package io.github.alalkipgen.autoflow.core.dubbing

import io.github.alalkipgen.autoflow.core.recap.WatermarkAnchor
import io.github.alalkipgen.autoflow.core.recap.WatermarkSize
import kotlin.math.roundToInt

/**
 * Where a dub's watermark / logo goes (D-108, specs/dubbing.md §2 Style), the same numbers for the preview and the
 * export: [widthPercent] of the video's width, the image's own aspect, [PAD] of the frame from the edges at its anchor.
 */
object DubMarks {
    /** Movie Recap's watermark opacity (its IMAGE layer, D-52). */
    const val OPACITY = 0.75f

    /** From the edges: 2 % of the frame's width (left / right) and height (top / bottom), as [WatermarkAnchor.rect]. */
    const val PAD = 0.02f

    /** Image types the watermark takes (Skia shows them on the preview, FFmpeg reads them for the export). */
    val EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    fun width(percent: Int): Int = percent.coerceIn(WatermarkSize.MIN, WatermarkSize.MAX)

    /** The image's width in pixels on a [videoWidth]-pixel picture: even (the 4:2:0 planes), at least 2. */
    fun widthPx(percent: Int, videoWidth: Int): Int = maxOf(2, (videoWidth.coerceAtLeast(2) * width(percent) / 100.0 / 2).roundToInt() * 2)

    /**
     * The image's top-left corner in 0–1 of the frame for an image [w] × [h] (0–1 of the frame) at [anchor]: the
     * export's `overlay` x / y written the same way ([overlayX], [overlayY]).
     */
    fun origin(anchor: WatermarkAnchor, w: Float, h: Float): Pair<Float, Float> {
        val x = when (anchor) {
            WatermarkAnchor.TOP_LEFT, WatermarkAnchor.BOTTOM_LEFT -> PAD
            WatermarkAnchor.TOP_RIGHT, WatermarkAnchor.BOTTOM_RIGHT -> 1f - w - PAD
            WatermarkAnchor.CENTER -> (1f - w) / 2f
        }
        val y = when (anchor) {
            WatermarkAnchor.TOP_LEFT, WatermarkAnchor.TOP_RIGHT -> PAD
            WatermarkAnchor.BOTTOM_LEFT, WatermarkAnchor.BOTTOM_RIGHT -> 1f - h - PAD
            WatermarkAnchor.CENTER -> (1f - h) / 2f
        }
        return x to y
    }

    /** `overlay`'s x for [anchor] (FFmpeg expression; `main_w` / `overlay_w` as [origin]). */
    fun overlayX(anchor: WatermarkAnchor): String = when (anchor) {
        WatermarkAnchor.TOP_LEFT, WatermarkAnchor.BOTTOM_LEFT -> "main_w*0.02"
        WatermarkAnchor.TOP_RIGHT, WatermarkAnchor.BOTTOM_RIGHT -> "main_w-overlay_w-main_w*0.02"
        WatermarkAnchor.CENTER -> "(main_w-overlay_w)/2"
    }

    fun overlayY(anchor: WatermarkAnchor): String = when (anchor) {
        WatermarkAnchor.TOP_LEFT, WatermarkAnchor.TOP_RIGHT -> "main_h*0.02"
        WatermarkAnchor.BOTTOM_LEFT, WatermarkAnchor.BOTTOM_RIGHT -> "main_h-overlay_h-main_h*0.02"
        WatermarkAnchor.CENTER -> "(main_h-overlay_h)/2"
    }

    /** A picked image's extension for the copy in the project (its own when it is one of [EXTENSIONS], else png). */
    fun ext(name: String): String = name.substringAfterLast('.', "png").lowercase().let { if (it in EXTENSIONS) it else "png" }

    /** The copy's project-relative name: a new image = a new name (so a new export, never an old video). */
    fun file(hash: String, ext: String): String = "library/watermark-${hash.take(12)}.$ext"
}
