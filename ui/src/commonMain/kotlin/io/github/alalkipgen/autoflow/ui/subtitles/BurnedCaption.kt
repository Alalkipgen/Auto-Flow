package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import io.github.alalkipgen.autoflow.media.render.AssWriter
import io.github.alalkipgen.autoflow.media.render.Overlay
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily

/**
 * F-6 (D-95): the burned caption at [positionMs] as the render will draw it — the burn's own [Overlay] (its line
 * breaks, its parts one after another, its size as a share of the [videoHeight]), scaled to this box: its colour (D-106;
 * white unless a caption style says otherwise), the see-through box (`BOX_COLOUR` ≈ 62 % black, padding size/6) or the
 * outline (size/24 black, or the style's width and colour), bottom centre [AssWriter.MARGIN_PERCENT] above the edge —
 * or centred at the overlay's `centerY` (on a blur band). The `.ass` never wraps by itself, so neither does this.
 */
@Composable
internal fun BoxScope.BurnedCaption(overlays: List<Overlay>, positionMs: Long, videoHeight: Int, tag: String) {
    val o = overlays.lastOrNull { positionMs >= it.startMs && positionMs < it.endMs } ?: return
    BoxWithConstraints(Modifier.matchParentSize(), contentAlignment = Alignment.BottomCenter) {
        val density = LocalDensity.current
        val scale = constraints.maxHeight.toFloat() / videoHeight.takeIf { it > 0 }.let { it ?: 1080 }
        val style = TextStyle(color = overlayColour(o.color), fontSize = with(density) { (o.sizePx * scale).toSp() },
            fontFamily = LocalUiFontFamily.current, textAlign = TextAlign.Center)
        val bottom = with(density) { (constraints.maxHeight * AssWriter.MARGIN_PERCENT / 100f).toDp() }
        val centre = o.centerY?.coerceIn(0.05f, 0.95f)
        // As wide as its widest line: centred unwrapped text is otherwise laid out at the full width and drawn past its box.
        val place = if (centre == null) Modifier.padding(bottom = bottom)
            else Modifier.align(Alignment.TopCenter).layout { m, c ->
                val p = m.measure(c.copy(minHeight = 0))
                layout(p.width, constraints.maxHeight) { p.place(0, (constraints.maxHeight * centre - p.height / 2f).toInt()) }
            }
        Box(place.width(IntrinsicSize.Max).testTag(tag), contentAlignment = Alignment.Center) {
            if (o.box) {
                val pad = with(density) { (maxOf(4, o.sizePx / 6) * scale).toDp() }
                BasicText(o.text, Modifier.background(Color.Black.copy(alpha = 0.62f)).padding(pad), style = style, softWrap = false)
            } else {
                val outline = (if (o.strokeWidth > 0) o.strokeWidth else maxOf(2, o.sizePx / 24)) * scale
                val edge = if (o.strokeWidth > 0) overlayColour(o.strokeColour) else Color.Black
                // The outline is drawn on both sides of the glyph edge, as libass's Outline is outside it.
                BasicText(o.text, Modifier.clearAndSetSemantics {}, softWrap = false,
                    style = style.copy(color = edge, drawStyle = Stroke(width = outline * 2, join = StrokeJoin.Round)))
                BasicText(o.text, style = style, softWrap = false)
            }
        }
    }
}

/** An overlay's `#RRGGBB` / `#AARRGGBB` as libass reads it ([AssWriter.assColor]); anything else is white. */
internal fun overlayColour(hex: String): Color {
    val h = hex.removePrefix("#")
    val v = h.takeIf { (it.length == 6 || it.length == 8) && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }?.toLong(16) ?: return Color.White
    return if (h.length == 6) Color(0xFF000000 or v) else Color(v)
}
