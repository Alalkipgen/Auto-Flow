package io.github.alalkipgen.autoflow.core.recap

/**
 * Geometry helpers for the live preview (5A-8, studio-v2.md §4).
 *
 * All coordinates are in the **output frame space** (0–1 normalized), where `(0,0)` is top-left
 * and `(1,1)` is bottom-right. The preview composable scales these by the available pixel size.
 */
object PreviewGeometry {

    /**
     * Clamps a rect `[x, y, w, h]` so it fits inside the frame.
     * [minSize] is the minimum w / h (default 0.05 = 5 %).
     */
    fun clamp(rect: List<Float>, minSize: Float = 0.05f): List<Float> {
        val w = rect[2].coerceIn(minSize, 1f)
        val h = rect[3].coerceIn(minSize, 1f)
        val x = rect[0].coerceIn(0f, 1f - w)
        val y = rect[1].coerceIn(0f, 1f - h)
        return listOf(x, y, w, h)
    }

    /**
     * Translates a rect by [dx] / [dy] (normalized), clamping it to the frame.
     */
    fun drag(rect: List<Float>, dx: Float, dy: Float): List<Float> {
        return clamp(listOf(rect[0] + dx, rect[1] + dy, rect[2], rect[3]))
    }

    /**
     * Scales the rect's size by [sx] / [sy] around its top-left corner, clamping to the frame.
     */
    fun pinchResize(rect: List<Float>, sx: Float, sy: Float): List<Float> {
        return clamp(listOf(rect[0], rect[1], rect[2] * sx, rect[3] * sy))
    }

    /**
     * Converts a normalized rect `[x, y, w, h]` to pixel coordinates given [frameW] × [frameH].
     * Returns `[left, top, right, bottom]` in pixels.
     */
    fun toPixels(rect: List<Float>, frameW: Float, frameH: Float): List<Float> = listOf(
        rect[0] * frameW,
        rect[1] * frameH,
        (rect[0] + rect[2]) * frameW,
        (rect[1] + rect[3]) * frameH,
    )

    /**
     * Converts pixel coordinates `[left, top, right, bottom]` back to normalized `[x, y, w, h]`.
     */
    fun fromPixels(pixels: List<Float>, frameW: Float, frameH: Float): List<Float> = listOf(
        pixels[0] / frameW,
        pixels[1] / frameH,
        (pixels[2] - pixels[0]) / frameW,
        (pixels[3] - pixels[1]) / frameH,
    )

    /**
     * Checks whether [layer] is visible at [outputMs] output time.
     */
    fun isActive(layer: Layer, outputMs: Long): Boolean {
        if (outputMs < layer.fromMs) return false
        return layer.toMs == null || outputMs <= layer.toMs
    }

    /**
     * Returns layers visible at [outputMs], in drawing order (captions last).
     */
    fun activeLayers(layers: List<Layer>, outputMs: Long): List<Layer> =
        layers.filter { isActive(it, outputMs) }
}
