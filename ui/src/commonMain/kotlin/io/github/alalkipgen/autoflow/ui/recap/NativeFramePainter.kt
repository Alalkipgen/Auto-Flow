package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.media.render.*
import kotlin.math.*

/** Output-frame CPU/Compose proxy. Decoder, timeline and persistence stay outside this painter.
 * Geometry/order/timing follow the native compiler; Gaussian, YUV conversion and text rasterization are approximate.
 * [output] remains the compiler's logical resolution even when the interactive surface is reduced to [maxEdge]. */
internal class NativeFramePainter(private val measurer: TextMeasurer, private val family: FontFamily) {
    fun problem(recap: RecapV2, images: Map<String, ImageBitmap>): String? {
        recap.layers.firstNotNullOfOrNull { l -> LayerEdits.problem(l)?.let { "${l.id}: $it" } }?.let { return it }
        val required = recap.layers.filter { it.type == LayerType.IMAGE || it.type == LayerType.BACKGROUND && it.params.mode == BackgroundMode.IMAGE }.mapNotNull { it.params.image } +
            listOfNotNull(recap.visual.watermarkFile, recap.visual.backgroundImageFile.takeIf { recap.visual.background == FrameBackground.IMAGE })
        return required.firstOrNull { it !in images }?.let { "Image unavailable: $it. Native output preview cannot omit it; use Exact frame after restoring the image." }
    }

    fun paint(source: ImageBitmap, recap: RecapV2, output: Output, timeMs: Long, cues: List<SrtCue>, images: Map<String, ImageBitmap>, maxEdge: Int = 640): ImageBitmap {
        require(problem(recap, images) == null) { problem(recap, images).orEmpty() }
        val factor = minOf(1f, maxEdge.toFloat() / maxOf(output.width, output.height))
        val w = (output.width * factor).roundToInt().coerceAtLeast(1)
        val h = (output.height * factor).roundToInt().coerceAtLeast(1)
        val result = ImageBitmap(w, h)
        fun draw(block: DrawScope.() -> Unit) = CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(result), Size(w.toFloat(), h.toFloat()), block)
        // Legacy masks use MOVIE-PICTURE coordinates and are blurred before Mirror / Zoom / fitting.
        var movie = source
        if (recap.visual.masks.isNotEmpty()) {
            movie = ImageBitmap(source.width, source.height)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(movie), Size(source.width.toFloat(), source.height.toFloat())) { drawImage(source) }
            recap.visual.masks.forEach { m ->
                val r = rect(m.rect, source.width, source.height)
                val sigma = m.strength.sigma * source.width.toFloat() / (recap.movie?.width ?: source.width).coerceAtLeast(1)
                val blurred = previewBlur(movie, r[0], r[1], r[2], r[3], sigma)
                CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(movie), Size(movie.width.toFloat(), movie.height.toFloat())) { drawImage(blurred, Offset(r[0].toFloat(), r[1].toFloat())) }
            }
        }
        val zoom = Look.ZOOM in recap.looks
        val mirror = Look.MIRROR in recap.looks
        val fill = Look.FILL in recap.looks
        // Cropping to an even source rectangle matches the 4:2:0 export within the documented pixel tolerance.
        val sw = (movie.width * if (zoom) .9f else 1f).toInt().let { if (zoom) (it / 2 * 2).coerceAtLeast(2) else it }
        val sh = (movie.height * if (zoom) .9f else 1f).toInt().let { if (zoom) (it / 2 * 2).coerceAtLeast(2) else it }
        val srcOffset = IntOffset((movie.width - sw) / 2, (movie.height - sh) / 2)
        fun DrawScope.picture(cover: Boolean) {
            val scale = if (cover) maxOf(size.width / sw, size.height / sh) else minOf(size.width / sw, size.height / sh)
            val dw = (sw * scale).roundToInt(); val dh = (sh * scale).roundToInt()
            val dx = ((size.width - dw) / 2).roundToInt(); val dy = ((size.height - dh) / 2).roundToInt()
            scale(if (mirror) -1f else 1f, 1f, center) {
                drawImage(movie, srcOffset, IntSize(sw, sh), IntOffset(dx, dy), IntSize(dw, dh), filterQuality = FilterQuality.Medium)
            }
        }
        fun DrawScope.cover(image: ImageBitmap) {
            val scale = maxOf(size.width / image.width, size.height / image.height)
            val dw = (image.width * scale).roundToInt(); val dh = (image.height * scale).roundToInt()
            drawImage(image, dstOffset = IntOffset(((size.width - dw) / 2).roundToInt(), ((size.height - dh) / 2).roundToInt()), dstSize = IntSize(dw, dh), filterQuality = FilterQuality.Medium)
        }
        val bg = recap.layers.lastOrNull { it.type == LayerType.BACKGROUND && active(it, timeMs) }
        val mode = bg?.params?.mode ?: when (recap.visual.background) {
            FrameBackground.BLUR -> BackgroundMode.BLUR
            FrameBackground.IMAGE -> BackgroundMode.IMAGE
            else -> BackgroundMode.COLOUR
        }
        draw {
            drawRect(colour(bg?.params?.colour ?: if (recap.visual.background == FrameBackground.COLOUR) recap.visual.backgroundColour else "#000000"))
            if (!fill) when (mode) {
                BackgroundMode.COLOUR -> Unit
                BackgroundMode.IMAGE -> {
                    cover(images.getValue(if (bg != null) bg.params.image!! else recap.visual.backgroundImageFile!!))
                    if (bg == null) drawImage(previewBlur(result, 0, 0, w, h, 4 * factor))
                }
                BackgroundMode.BLUR -> {
                    // Export blurs a quarter-resolution cover (sigma=12 there), then scales it back up.
                    val bw = (w / 4 / 2 * 2).coerceAtLeast(2); val bh = (h / 4 / 2 * 2).coerceAtLeast(2)
                    val backing = ImageBitmap(bw, bh)
                    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(backing), Size(bw.toFloat(), bh.toFloat())) { picture(true) }
                    drawImage(previewBlur(backing, 0, 0, bw, bh, 12 * factor), dstSize = IntSize(w, h))
                }
            }
            clipRect { picture(fill) }
        }
        if (Look.WARM in recap.looks) {
            // ffmpeg eq applies contrast to limited-range luma and saturation to chroma, after fitting.
            val matrix = FloatArray(20)
            val luma = floatArrayOf(.299f, .587f, .114f)
            for (i in 0..2) { for (j in 0..2) matrix[i * 5 + j] = (1.05f - 1.15f) * luma[j] + if (i == j) 1.15f else 0f; matrix[i * 5 + 4] = -112f * .05f * 255 / 219 }
            matrix[18] = 1f
            val copy = ImageBitmap(w, h)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(copy), Size(w.toFloat(), h.toFloat())) { drawImage(result) }
            draw { drawImage(copy, colorFilter = ColorFilter.colorMatrix(ColorMatrix(matrix))) }
        }
        val watermark = recap.visual.watermarkFile?.let { Layer("watermark", LayerType.IMAGE, recap.visual.watermarkAnchor.rect(), params = LayerParams(image = it, opacity = .75f)) }
        (recap.layers + listOfNotNull(watermark)).filter { active(it, timeMs) }.forEach { layer ->
            val p = layer.params
            val r = rect(layer.rect, w, h)
            when (layer.type) {
                LayerType.BACKGROUND -> Unit
                LayerType.BLUR -> {
                    val fade = if (p.feather > 0) (p.feather * h).toInt().coerceIn(1, (minOf(r[2], r[3]) / 2).coerceAtLeast(1)) else 0
                    val blurred = previewBlur(result, r[0], r[1], r[2], r[3], maxOf(1f, p.sigma) * factor, fade)
                    draw { drawImage(blurred, Offset(r[0].toFloat(), r[1].toFloat())) }
                }
                LayerType.IMAGE -> draw {
                    val image = images.getValue(p.image!!)
                    val ih = (image.height.toFloat() / image.width * r[2] / 2).roundToInt().coerceAtLeast(1) * 2
                    drawImage(image, dstOffset = IntOffset(r[0], r[1]), dstSize = IntSize(r[2], ih), alpha = p.opacity, filterQuality = FilterQuality.Medium)
                }
                LayerType.TEXT -> if (layer.toMs?.let { timeMs < it } != false) draw { scale(factor, factor, Offset.Zero) { text(LayerCompiler.textOverlay(layer, output, Long.MAX_VALUE), output) } }
                LayerType.BORDER -> draw {
                    val px = minOf(p.px * factor, minOf(w, h).toFloat())
                    val c = colour(p.colour)
                    drawRect(c, size = Size(w.toFloat(), px)); drawRect(c, Offset(0f, h - px), Size(w.toFloat(), px))
                    drawRect(c, size = Size(px, h.toFloat())); drawRect(c, Offset(w - px, 0f), Size(px, h.toFloat()))
                }
            }
        }
        // Actual captions are always the last pass, including over native TEXT and borders.
        cues.filter { recap.burnCaptions && timeMs >= it.startMs && timeMs < it.endMs }.forEach { cue ->
            draw { scale(factor, factor, Offset.Zero) { text(RecapRender.captionOverlay(recap, output, cue, ""), output) } }
        }
        return result
    }

    private fun DrawScope.text(event: Overlay, output: Output) {
        val style = TextStyle(fontFamily = family, fontSize = event.sizePx.sp, textAlign = TextAlign.Center)
        val layout = measurer.measure(event.text, style, softWrap = false, density = Density(1f))
        val explicit = event.centerX != null || event.centerY != null
        val x = event.centerX?.coerceIn(0f, 1f)?.times(output.width) ?: (output.width / 2f)
        val y = event.centerY?.coerceIn(.05f, .95f)?.times(output.height) ?: when { explicit || event.position == Pos.CENTER -> output.height / 2f; event.position == Pos.TOP -> output.height * .08f; else -> output.height * .92f }
        val at = Offset(x - layout.size.width / 2f, y - when { explicit || event.position == Pos.CENTER -> layout.size.height / 2f; event.position == Pos.TOP -> 0f; else -> layout.size.height.toFloat() })
        if (event.box) {
            val pad = maxOf(4, event.sizePx / 6).toFloat()
            drawRect(Color.Black.copy(alpha = 159 / 255f), at - Offset(pad, pad), Size(layout.size.width + 2 * pad, layout.size.height + 2 * pad))
        } else {
            val stroke = if (event.strokeWidth > 0) event.strokeWidth else maxOf(2, event.sizePx / 24)
            drawText(layout, colour(event.strokeColour), at, drawStyle = Stroke(width = stroke * 2f, join = StrokeJoin.Round))
        }
        drawText(layout, colour(event.color), at)
    }

    private fun active(l: Layer, t: Long) = t >= l.fromMs && (l.toMs?.let { t <= it } != false)
    private fun colour(hex: String): Color = hex.removePrefix("#").toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: Color.White
    private fun rect(r: List<Float>, w: Int, h: Int): IntArray {
        val x = (r.getOrElse(0) { 0f } * w).toInt().coerceIn(0, w - 1); val y = (r.getOrElse(1) { 0f } * h).toInt().coerceIn(0, h - 1)
        return intArrayOf(x, y, (r.getOrElse(2) { 1f } * w).toInt().coerceIn(1, w - x), (r.getOrElse(3) { 1f } * h).toInt().coerceIn(1, h - y))
    }
}
