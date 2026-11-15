package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.recap.Layer
import io.github.alalkipgen.autoflow.core.recap.LayerType

/**
 * Compiles a list of [Layer]s into extra ffmpeg filter chains and ASS events that sit on top of the base video
 * strip (spec §4, D-37).  Called by [RenderCompiler.compile] after the base strip is assembled.
 *
 * The compiled output is designed to be appended to [RenderCompiler]'s `chains` list.  The final output label is
 * always `[vlayer]`, which the caller replaces `[vout]` with when any layers are present.
 *
 * Layer order = drawing order (spec: "List order = drawing order; captions are drawn last").
 * TEXT layers contribute ASS events (returned separately); they are merged with the existing ASS file.
 */
object LayerCompiler {

    data class LayerResult(
        /** Additional `-filter_complex` chains to append. The last chain maps `[vlayer]`. */
        val chains: List<String>,
        /** Additional input file paths that must be prepended to [RenderCompiler]'s input list (image files). */
        val inputPaths: List<String>,
        /** ASS events for TEXT layers; merged into the existing ASS file. */
        val assEvents: List<Overlay>,
    )

    /**
     * @param layers  Layers in drawing order.
     * @param output  The output dimensions / fps.
     * @param totalMs Total render duration.
     * @param resolveImage Maps a Layer.params.image id to an absolute file path (or null if missing).
     * @param baseInputCount Number of input streams already added by [RenderCompiler] (images get index baseInputCount + k).
     * @param textFont The font family for a TEXT layer without a [LayerParams.style] (the captions' font).
     */
    fun compile(
        layers: List<Layer>,
        output: Output,
        totalMs: Long,
        resolveImage: (String) -> String?,
        baseInputCount: Int,
        textFont: String? = null,
    ): LayerResult {
        if (layers.isEmpty()) return LayerResult(emptyList(), emptyList(), emptyList<Overlay>())

        val chains = mutableListOf<String>()
        val inputPaths = mutableListOf<String>()
        val assEvents = mutableListOf<Overlay>()
        var inputIdx = baseInputCount
        var prev = "vseq"   // label coming out of the base strip

        for ((k, layer) in layers.withIndex()) {
            val next = if (k == layers.lastIndex) "vlayer" else "vl$k"
            val from = layer.fromMs
            val to = layer.toMs ?: totalMs
            val p = layer.params
            val w = output.width
            val h = output.height

            when (layer.type) {
                LayerType.BLUR -> {
                    // rect crop → gblur → optional feather → overlay
                    val (rx, ry, rw, rh) = rectPx(layer.rect, w, h)
                    val sigma = p.sigma.coerceAtLeast(1f)
                    val enableExpr = enableExpr(from, to)
                    // Feather: give the blurred box an alpha plane and fade it in over [fade] px from every edge,
                    // so overlay blends the blur softly into the picture. geq's luma option is `lum` (there is
                    // no `luma`); p(X,Y) keeps each colour plane as it is. Alpha is 0–255: distance to the
                    // nearest edge (+1), capped at [fade], scaled to 255 — checked with a real ffmpeg
                    // (LayerFfmpegRenderTest).
                    val featherChain = if (p.feather > 0f) {
                        val fade = (p.feather * h).toInt().coerceAtLeast(1)
                            .coerceAtMost((minOf(rw, rh) / 2).coerceAtLeast(1))
                        ",format=yuva420p,geq=lum='p(X,Y)':cb='p(X,Y)':cr='p(X,Y)':" +
                            "a='min(min(min(X,W-1-X),min(Y,H-1-Y))+1,$fade)/$fade*255'"
                    } else ""
                    chains += "[$prev]split[bg$k][fg$k];" +
                        "[fg$k]crop=$rw:$rh:$rx:$ry,gblur=sigma=$sigma$featherChain[blur$k];" +
                        "[bg$k][blur$k]overlay=$rx:$ry:enable='$enableExpr'[$next]"
                }

                LayerType.IMAGE -> {
                    val path = p.image?.let(resolveImage)
                    if (path == null) {
                        // RenderCompiler refuses a graph whose image is missing (audit F08); a direct caller gets a relay.
                        chains += "[$prev]null[$next]"
                    } else {
                        val (rx, ry, rw, _) = rectPx(layer.rect, w, h)
                        val idx = inputIdx++
                        inputPaths += path
                        val enableExpr = enableExpr(from, to)
                        // Scaled to the rect's width, aspect kept (-2: an even height for the 4:2:0 planes).
                        chains += "[${idx}:v]scale=$rw:-2,format=yuva420p," +
                            "colorchannelmixer=aa=${p.opacity.coerceIn(0f, 1f)}[wmscaled$k];" +
                            "[$prev][wmscaled$k]overlay=$rx:$ry:enable='$enableExpr'[$next]"
                    }
                }

                LayerType.TEXT -> {
                    // TEXT layers generate ASS events; no filtergraph change needed. A whole-frame rect (the
                    // default) keeps the centred caption size; a smaller rect centres the text in the rect (x and y,
                    // audit F08) and sizes the lines to fill its height (e.g. the Publish Kit thumbnail title, 5B-10).
                    val whole = layer.rect.size != 4 || (layer.rect[2] >= 1f && layer.rect[3] >= 1f)
                    val lines = p.textMy.trim().lines()
                    // In a rect: as tall as its height allows and no wider than it (estimated from the text).
                    val widest = lines.maxOf { emWidth(it) }.coerceAtLeast(1f)
                    val fontSize = if (whole) (h / 18).coerceAtLeast(12)
                    else minOf(layer.rect[3] * h / (lines.size * 1.5f), layer.rect[2] * w / widest).toInt().coerceIn(12, h / 4)
                    assEvents += Overlay(
                        text = p.textMy,
                        startMs = from,
                        endMs = to,
                        font = p.style.ifBlank { textFont.orEmpty() },
                        sizePx = fontSize,
                        color = "#FFFFFF",
                        position = Pos.CENTER,
                        centerY = if (whole) null else layer.rect[1] + layer.rect[3] / 2,
                        centerX = if (whole) null else layer.rect[0] + layer.rect[2] / 2,
                        strokeWidth = if (whole) 0 else maxOf(2, fontSize / 14),
                    )
                    chains += "[$prev]null[$next]"
                }

                LayerType.BORDER -> {
                    // drawbox × 4: top, bottom, left, right
                    val px = p.px.coerceAtLeast(1)
                    val c = p.colour.removePrefix("#").padEnd(6, '0').let { "0x$it@1.0" }
                    val enableExpr = enableExpr(from, to)
                    val boxes = listOf(
                        "drawbox=0:0:iw:$px:c=$c:t=fill",
                        "drawbox=0:ih-$px:iw:$px:c=$c:t=fill",
                        "drawbox=0:0:$px:ih:c=$c:t=fill",
                        "drawbox=iw-$px:0:$px:ih:c=$c:t=fill",
                    ).joinToString(",") { "$it:enable='$enableExpr'" }
                    chains += "[$prev]$boxes[$next]"
                }

                LayerType.BACKGROUND -> {
                    // A BACKGROUND fills what the fitted picture leaves free, so RenderCompiler composes it per clip
                    // behind the picture (spec §4, audit F08 / F12). Over the finished frame it would be hidden by the
                    // bars (or cover the picture), so here it passes the picture on unchanged.
                    chains += "[$prev]null[$next]"
                }
            }
        }

        return LayerResult(chains, inputPaths, assEvents)
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * A rough width of [line] in ems (no font metrics here): marks that sit on a letter take no room, a space 0.3,
     * Burmese 0.6, wide CJK / emoji 1.0, other letters 0.62 — so a TEXT layer is sized to stay inside its rect.
     */
    internal fun emWidth(line: String): Float = line.sumOf { c ->
        when {
            c.category == CharCategory.NON_SPACING_MARK || c.category == CharCategory.ENCLOSING_MARK || c.category == CharCategory.FORMAT -> 0.0
            c.isWhitespace() -> 0.3
            c.code in 0x1000..0x109F || c.code in 0xAA60..0xAA7F || c.code in 0xA9E0..0xA9FF -> 0.6
            c.code >= 0x2E80 || c.isSurrogate() -> 1.0
            else -> 0.62
        }
    }.toFloat()

    /** Enable expression for `enable='between(t,from,to)'`. */
    private fun enableExpr(fromMs: Long, toMs: Long): String =
        "between(t,${fromMs / 1000.0},${toMs / 1000.0})"

    /** [rect] = [x,y,w,h] in 0–1; return pixel coordinates clamped to [frameW] × [frameH]. */
    private fun rectPx(rect: List<Float>, frameW: Int, frameH: Int): IntArray {
        val x = rect.getOrElse(0) { 0f }
        val y = rect.getOrElse(1) { 0f }
        val rw = rect.getOrElse(2) { 1f }
        val rh = rect.getOrElse(3) { 1f }
        val px = (x * frameW).toInt().coerceIn(0, frameW - 1)
        val py = (y * frameH).toInt().coerceIn(0, frameH - 1)
        val pw = (rw * frameW).toInt().coerceIn(1, frameW - px)
        val ph = (rh * frameH).toInt().coerceIn(1, frameH - py)
        return intArrayOf(px, py, pw, ph)
    }
}
