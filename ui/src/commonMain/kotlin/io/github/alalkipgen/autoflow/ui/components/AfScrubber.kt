package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/**
 * Foundation-only Af control: output fraction, not a second player or timeline. Vertical scrolling stays free.
 * [glass] = the glass preview look (track White .12, Cyan → Purple fill, 16 dp white knob with a Purple .35 ring);
 * [ticks] = 2 × 10 dp White .28 marks at these fractions (block starts); [onTap] = a tap's fraction goes there instead of
 * [onChange] (e.g. to snap to a tick); drags, keys and TalkBack still use [onChange].
 */
@Composable
fun AfScrubber(value: Float, label: String, onChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    glass: Boolean = false, ticks: List<Float> = emptyList(), onTap: ((Float) -> Unit)? = null) {
    val fraction = value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val change by rememberUpdatedState(onChange)
    val tap by rememberUpdatedState(onTap ?: onChange)
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // Canvas is a Spacer: a minimum-only height gives its input/semantics node zero intrinsic height.
    // This graphic has no text to clip; reserve a real 48 dp hit area independently of font scale.
    Canvas(modifier.fillMaxWidth().height(48.dp).widthIn(min = 48.dp)
        .semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            if (!enabled) disabled() else setProgress { next ->
                if (!next.isFinite()) false else { change(next.coerceIn(0f, 1f)); true }
            }
        }
        .focusRequester(focus).onFocusChanged { focused = it.isFocused }
        .onKeyEvent { event ->
            val next = when (event.key) {
                Key.DirectionLeft -> fraction - .01f
                Key.DirectionRight -> fraction + .01f
                Key.MoveHome -> 0f
                Key.MoveEnd -> 1f
                else -> null
            }
            if (!enabled || event.type != KeyEventType.KeyDown || next == null) false
            else { change(next.coerceIn(0f, 1f)); true }
        }.focusable(enabled)
        .pointerInput(enabled) {
            if (enabled) detectTapGestures { point ->
                focus.requestFocus()
                tap(((point.x - 12.dp.toPx()) / (size.width - 24.dp.toPx())).coerceIn(0f, 1f))
            }
        }.pointerInput(enabled) {
            if (enabled) detectHorizontalDragGestures(onDragStart = { focus.requestFocus() }) { pointer, _ ->
                pointer.consume()
                change(((pointer.position.x - 12.dp.toPx()) / (size.width - 24.dp.toPx())).coerceIn(0f, 1f))
            }
        }) {
        val start = Offset(12.dp.toPx(), size.height / 2)
        val end = Offset(size.width - start.x, start.y)
        val thumb = Offset(start.x + (end.x - start.x) * fraction, start.y)
        if (glass) {
            drawLine(Color.White.copy(alpha = .12f), start, end, 4.dp.toPx(), StrokeCap.Round)
            if (enabled && thumb.x > start.x) drawLine(Brush.horizontalGradient(listOf(AutoFlowColors.Cyan, AutoFlowColors.Purple), start.x, thumb.x),
                start, thumb, 4.dp.toPx(), StrokeCap.Round)
            ticks.forEach { t ->
                val x = start.x + (end.x - start.x) * t.coerceIn(0f, 1f)
                drawRoundRect(Color.White.copy(alpha = .28f), Offset(x - 1.dp.toPx(), start.y - 5.dp.toPx()), Size(2.dp.toPx(), 10.dp.toPx()),
                    CornerRadius(1.dp.toPx()))
            }
            if (enabled) drawCircle(AutoFlowColors.Purple.copy(alpha = .35f), 12.dp.toPx(), thumb)
            drawCircle(if (enabled) Color.White else AutoFlowColors.Muted, 8.dp.toPx(), thumb)
        } else {
            drawLine(AutoFlowColors.Muted, start, end, 4.dp.toPx(), StrokeCap.Round)
            if (enabled) drawLine(AutoFlowColors.Cyan, start, thumb, 4.dp.toPx(), StrokeCap.Round)
            drawCircle(if (enabled) AutoFlowColors.Purple else AutoFlowColors.Muted, 8.dp.toPx(), thumb)
        }
        if (focused) drawRoundRect(AutoFlowColors.Cyan, style = Stroke(2.dp.toPx()))
    }
}