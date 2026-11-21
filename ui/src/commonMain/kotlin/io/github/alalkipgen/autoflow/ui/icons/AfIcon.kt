package io.github.alalkipgen.autoflow.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.theme.AfRadius
import io.github.alalkipgen.autoflow.ui.theme.AfSize
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import io.github.alalkipgen.autoflow.ui.theme.afTextStyle

/**
 * A Phosphor icon (DESIGN.md §9.3) in [tint]: Regular by default, Fill for the active nav item and play / pause,
 * Duotone inside [IconTile]. Decorative unless [contentDescription] is given.
 */
@Composable
fun AfIcon(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = AutoFlowColors.Muted, size: Dp = AfSize.Icon,
    contentDescription: String? = null) {
    Image(rememberVectorPainter(icon), contentDescription, modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

/** A coloured tile with a Duotone icon (mode tiles, sidebar create shortcuts). */
@Composable
fun IconTile(icon: ImageVector, colour: Color, modifier: Modifier = Modifier, size: Dp = AfSize.Tile, radius: Dp = AfRadius.Tile,
    ink: Color = Color.White) {
    Box(modifier.size(size).clip(RoundedCornerShape(radius)).background(colour), contentAlignment = Alignment.Center) {
        AfIcon(icon, tint = ink, size = size * .56f)
    }
}

/** One tooltip at a time, drawn in the shell's own layer (no popup window), so it never covers a dialog. */
class TooltipHostState {
    internal var tip by mutableStateOf<Pair<String, Rect>?>(null)
}

val LocalTooltipHost = staticCompositionLocalOf<TooltipHostState?> { null }

/** Hosts [afTooltip]s of [content]; the bubble sits right of its anchor, or below it when there is no room. */
@Composable
fun TooltipHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = remember { TooltipHostState() }
    var origin by remember { mutableStateOf(Rect.Zero) }
    Box(modifier.onGloballyPositioned { origin = it.boundsInRoot() }) {
        CompositionLocalProvider(LocalTooltipHost provides state) { content() }
        state.tip?.let { (text, anchor) ->
            Layout({
                BasicText(text, Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(AfRadius.ButtonSmall))
                    .background(AutoFlowColors.Surface3).border(1.dp, AutoFlowColors.LineStrong, RoundedCornerShape(AfRadius.ButtonSmall))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                    style = afTextStyle(AutoFlowColors.Text, AfType.Caption.size, AfType.Caption.weight, LocalUiFontFamily.current))
            }) { measurables, constraints ->
                val bubble = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val gap = 8.dp.roundToPx()
                    val a = anchor.translate(-origin.left, -origin.top)
                    val right = a.right.toInt() + gap
                    val (x, y) = if (right + bubble.width <= constraints.maxWidth)
                        right to (a.center.y - bubble.height / 2f).toInt()
                    else (a.center.x - bubble.width / 2f).toInt() to a.bottom.toInt() + gap
                    bubble.place(x.coerceIn(0, (constraints.maxWidth - bubble.width).coerceAtLeast(0)),
                        y.coerceIn(0, (constraints.maxHeight - bubble.height).coerceAtLeast(0)))
                }
            }
        }
    }
}

/** Shows [text] while a mouse rests on this element (EN/MY label of an icon-only control); a click hides it. */
fun Modifier.afTooltip(text: String): Modifier = composed {
    val host = LocalTooltipHost.current ?: return@composed this
    val bounds = remember { arrayOf(Rect.Zero) }
    DisposableEffect(host, text) { onDispose { if (host.tip?.first == text) host.tip = null } }
    onGloballyPositioned { bounds[0] = it.boundsInRoot() }.pointerInput(host, text) {
        var pressed = false
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.none { it.type == PointerType.Mouse }) continue
                when (event.type) {
                    PointerEventType.Enter -> { pressed = false; host.tip = text to bounds[0] }
                    PointerEventType.Move -> if (!pressed && host.tip?.first != text) host.tip = text to bounds[0]
                    PointerEventType.Press -> { pressed = true; host.tip = null }
                    PointerEventType.Exit -> { pressed = false; if (host.tip?.first == text) host.tip = null }
                    else -> Unit
                }
            }
        }
    }
}
