package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.theme.AfDesktop
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** DESIGN.md §9.8 width classes: compact < 600 dp (phone layouts), medium 600–839 (phone layouts, rail), expanded ≥ 840. */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

fun widthClass(windowWidthDp: Float): WidthClass = when {
    windowWidthDp < AfDesktop.Medium.value -> WidthClass.COMPACT
    windowWidthDp < AfDesktop.Expanded.value -> WidthClass.MEDIUM
    else -> WidthClass.EXPANDED
}

/** Mouse-first platforms (desktop) draw scrollbars and take right-clicks; touch platforms do not. */
internal expect val PointerFirst: Boolean

/**
 * [verticalScroll] plus, on the desktop, a visible scrollbar (DESIGN.md §9.8): a 6 dp `LineStrong` thumb at the right
 * edge whose length is the visible share; dragging the thumb scrolls. Touch platforms only scroll.
 */
fun Modifier.paneScroll(state: ScrollState): Modifier = if (!PointerFirst) verticalScroll(state) else composed {
    var dragging by remember { mutableStateOf(false) }
    fun thumb(height: Float, min: Float): Pair<Float, Float> {
        val length = maxOf(min, height * height / (height + state.maxValue)).coerceAtMost(height)
        return (height - length) * state.value / state.maxValue.coerceAtLeast(1) to length
    }
    drawWithContent {
        drawContent()
        if (state.maxValue in 1 until Int.MAX_VALUE) {
            val (top, length) = thumb(size.height, ThumbMin.toPx())
            drawRoundRect(if (dragging) AutoFlowColors.Muted else AutoFlowColors.LineStrong, Offset(size.width - (ThumbWidth + ThumbInset).toPx(), top),
                Size(ThumbWidth.toPx(), length), CornerRadius(ThumbWidth.toPx() / 2))
        }
    }.pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val (top, length) = thumb(size.height.toFloat(), ThumbMin.toPx())
            // Only a press on the thumb itself drags; clicks next to it reach the content.
            if (state.maxValue !in 1 until Int.MAX_VALUE || down.position.x < size.width - (ThumbWidth + ThumbInset * 2).toPx() ||
                down.position.y !in top..top + length) return@awaitEachGesture
            down.consume(); dragging = true
            val perPixel = state.maxValue / (size.height - length).coerceAtLeast(1f)
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                state.dispatchRawDelta((change.position.y - change.previousPosition.y) * perPixel)
                change.consume()
            }
            dragging = false
        }
    }.verticalScroll(state)
}

private val ThumbWidth = 6.dp
private val ThumbInset = 2.dp
private val ThumbMin = 32.dp

/** Right-click (desktop) runs [onMenu] — the same items as the ⋮ button (DESIGN.md §9.8); touch keeps long-press menus. */
fun Modifier.onSecondaryClick(onMenu: () -> Unit): Modifier = pointerInput(onMenu) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                onMenu()
            }
        }
    }
}

/**
 * Expanded: list (420–480 dp, about a third of the width) and detail side by side with the 18 dp pane gap; compact and
 * medium: one pane — the detail when [showDetail], else the list. Tags `list-detail-list` / `list-detail-detail`.
 */
@Composable
fun ListDetailPane(windowWidthDp: Float, showDetail: Boolean, list: @Composable () -> Unit, detail: @Composable () -> Unit,
    modifier: Modifier = Modifier) {
    if (widthClass(windowWidthDp) == WidthClass.EXPANDED) Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(AfDesktop.PaneGap)) {
        val listWidth = (windowWidthDp * .34f).dp.coerceIn(AfDesktop.ListPaneMin, AfDesktop.ListPaneMax)
        Box(Modifier.width(listWidth).fillMaxHeight().testTag("list-detail-list")) { list() }
        Box(Modifier.weight(1f).fillMaxHeight().testTag("list-detail-detail")) { detail() }
    } else Box(modifier.fillMaxSize().testTag(if (showDetail) "list-detail-detail" else "list-detail-list")) {
        if (showDetail) detail() else list()
    }
}

/**
 * Editor layout (Subtitles, Dubbing — mockup D3 / D4): expanded = [main] (video, timeline, table) with a fixed
 * [sideWidth] column on the right; below 840 dp the side column follows the main one in a single column.
 */
@Composable
fun EditorPanes(windowWidthDp: Float, main: @Composable () -> Unit, side: @Composable () -> Unit, modifier: Modifier = Modifier,
    sideWidth: Dp = 460.dp) {
    if (widthClass(windowWidthDp) == WidthClass.EXPANDED) Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(AfDesktop.PaneGap)) {
        Box(Modifier.weight(1f).fillMaxHeight().testTag("editor-main")) { main() }
        // Never more than 45 % of the window, so the main pane keeps the larger share at 840–1199 dp.
        Box(Modifier.width(minOf(sideWidth, (windowWidthDp * .45f).dp)).fillMaxHeight().testTag("editor-side")) { side() }
    } else Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AfDesktop.PaneGap)) {
        Box(Modifier.fillMaxWidth().testTag("editor-main")) { main() }
        Box(Modifier.fillMaxWidth().testTag("editor-side")) { side() }
    }
}

/** Medium width (600–839 dp): the phone layout, at most 640 dp wide and centred (DESIGN.md §9.8). */
@Composable
internal fun MediumContent(windowWidthDp: Float, content: @Composable () -> Unit) =
    if (widthClass(windowWidthDp) != WidthClass.MEDIUM) content()
    else Box(Modifier.fillMaxSize().background(AutoFlowColors.Background), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = MediumMax).fillMaxWidth().fillMaxHeight().testTag("medium-content")) { content() }
    }

private val MediumMax = 640.dp
