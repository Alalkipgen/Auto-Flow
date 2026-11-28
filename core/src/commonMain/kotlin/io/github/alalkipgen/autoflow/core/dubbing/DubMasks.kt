package io.github.alalkipgen.autoflow.core.dubbing

import io.github.alalkipgen.autoflow.core.model.DubMask
import io.github.alalkipgen.autoflow.core.model.DubMaskKind
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.recap.MaskStrength
import kotlin.math.round

/**
 * The blur boxes of a dub (D-107, specs/dubbing.md §2 Style): over the original video's own subtitles (the bottom or
 * top strip), its logo (a corner) or anything else (custom boxes, as many as needed); each blurred or filled with a
 * colour. Rects are `[x, y, w, h]` in 0–1 of the video picture — the dub keeps the video's frame, so the same numbers
 * place the box in the preview and in the export.
 */
object DubMasks {
    /** Smallest box side (3 % of the picture) and one nudge (2 %), as Movie Recap's boxes. */
    const val MIN_SIZE = 0.03f
    const val STEP = 0.02f

    /** Fill colours of a filled box (names as the colour chips say them). */
    val FILLS = listOf("Black" to "#000000", "Grey" to "#2A2A2A", "White" to "#FFFFFF")

    /** A filled box's colour when Colour is first chosen. */
    const val FILL = "#000000"

    /** Turns the strip / corner [kind] on (its default box) or off; [DubMaskKind.CUSTOM] always adds one more box. */
    fun toggle(masks: List<DubMask>, kind: DubMaskKind): List<DubMask> = when {
        kind == DubMaskKind.CUSTOM -> masks + DubMask(kind, clamp(kind.rect.let { r ->
            val n = masks.count { it.kind == DubMaskKind.CUSTOM }
            listOf(r[0] + STEP * 2 * (n % 5), r[1] + STEP * 2 * (n % 5), r[2], r[3])
        }))
        masks.any { it.kind == kind } -> masks.filterNot { it.kind == kind }
        else -> masks + DubMask(kind, kind.rect)
    }

    fun remove(masks: List<DubMask>, index: Int): List<DubMask> = masks.filterIndexed { i, _ -> i != index }

    /** Box [index] at [rect] (a drag / pinch on the preview), kept inside the picture; a strip stays full width. */
    fun setRect(masks: List<DubMask>, index: Int, rect: List<Float>): List<DubMask> =
        if (rect.size != 4 || rect.any { !it.isFinite() }) masks else edit(masks, index) { rect }

    fun move(masks: List<DubMask>, index: Int, dx: Float, dy: Float): List<DubMask> =
        edit(masks, index) { r -> listOf(r[0] + dx, r[1] + dy, r[2], r[3]) }

    /** Wider / narrower by [dw] and taller / shorter by [dh], around the box's centre. */
    fun resize(masks: List<DubMask>, index: Int, dw: Float, dh: Float): List<DubMask> =
        edit(masks, index) { r -> listOf(r[0] - dw / 2, r[1] - dh / 2, r[2] + dw, r[3] + dh) }

    /** Box [index] blurred (null) or filled with [fill] `#RRGGBB` (anything else = blurred). */
    fun fill(masks: List<DubMask>, index: Int, fill: String?): List<DubMask> {
        val hex = fill?.takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) }?.uppercase()
        return masks.mapIndexed { i, m -> if (i == index) m.copy(fill = hex) else m }
    }

    private fun edit(masks: List<DubMask>, index: Int, change: (List<Float>) -> List<Float>): List<DubMask> =
        masks.mapIndexed { i, m ->
            if (i != index) m else m.copy(rect = clamp(change(m.rect).let { r -> if (m.kind.isStrip) listOf(0f, r[1], 1f, r[3]) else r }))
        }

    /** Keeps `[x, y, w, h]` inside the picture with sides ≥ [MIN_SIZE], rounded to 0.001. */
    fun clamp(r: List<Float>): List<Float> {
        val w = r[2].coerceIn(MIN_SIZE, 1f)
        val h = r[3].coerceIn(MIN_SIZE, 1f)
        val x = r[0].coerceIn(0f, 1f - w)
        val y = r[1].coerceIn(0f, 1f - h)
        return listOf(x, y, w, h).map { round(it * 1000) / 1000 }
    }

    /**
     * Where the burned subtitles sit (0–1 of the frame): centred on the lowest box whose centre is in the lower half,
     * when [DubbingTool.captionsOnBox] (the original subtitles' strip becomes the Burmese subtitles' band); else null =
     * the usual bottom place.
     */
    fun captionY(tool: DubbingTool?): Float? {
        if (tool == null || !tool.captionsOnBox) return null
        return tool.masks.filter { it.centerY >= 0.5f }.maxByOrNull { it.centerY }?.centerY
    }

    /** The blur in pixels for a [height]-pixel picture: [MaskStrength.sigma] is for 1080 lines. */
    fun sigma(strength: MaskStrength, height: Int): Int = maxOf(4, (strength.sigma * height.coerceAtLeast(1) / 1080.0).toInt())
}
