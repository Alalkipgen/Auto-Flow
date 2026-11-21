package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.theme.*

/*
 * Phase 9 cards and rows (docs/DESIGN.md §9.4): rows ≥ 48 dp, title 1 line (full text in the content description),
 * subtitle ≤ 2 lines, dividers only inside one card; desktop rows hover one step lighter, selected = primary
 * container + 3 dp left accent.
 */

/** Progress bar: determinate only with a real [progress] (0..1), otherwise indeterminate — never a made-up percent. */
@Composable
fun AfProgress(progress: Float?, modifier: Modifier = Modifier, tone: Tone = Tone.Running) {
    val shape = RoundedCornerShape(AfRadius.Progress)
    if (progress == null) {
        val position by rememberInfiniteTransition().animateFloat(-.28f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)))
        Canvas(modifier.fillMaxWidth().height(AfSize.Progress).clip(shape).semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
            drawRect(AutoFlowColors.Line)
            val start = position.coerceIn(0f, 1f) * size.width
            drawRect(tone.fill, Offset(start, 0f), Size((position + .28f).coerceIn(0f, 1f) * size.width - start, size.height))
        }
    } else {
        require(progress.isFinite() && progress in 0f..1f)
        Box(modifier.fillMaxWidth().height(AfSize.Progress).clip(shape).background(AutoFlowColors.Line)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) }) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(tone.fill))
        }
    }
}

/**
 * Desktop list row: ≥ 48 dp, hover one step lighter, [selected] = primary container + 3 dp left accent. [content]
 * lays out the cells (give them weights).
 */
@Composable
fun TableRow(modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth().heightIn(min = AfSize.Touch)
            .then(if (onClick == null) Modifier else Modifier.clickable(interaction, null, role = Role.Button, onClick = onClick))
            .semantics { this.selected = selected }
            .background(if (selected) Tone.Primary.container else Color.Transparent)
            .drawBehind { if (selected) drawRect(AutoFlowColors.PrimaryFill, size = Size(AfDesktop.SelectedAccent.toPx(), size.height)) }
            .interactionOverlay(interaction, RectangleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AfSpace.M),
        content = content,
    )
}

/**
 * Row: [leading] (tile / thumbnail / avatar) · [title] on 1 line + [subtitle] up to 2 lines · [trailing] (value /
 * chip / chevron / switch / actions). The title keeps its full text as the content description.
 */
@Composable
fun ListRow(title: String, modifier: Modifier = Modifier, subtitle: String? = null, selected: Boolean = false,
    onClick: (() -> Unit)? = null, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    TableRow(modifier, selected, onClick) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AfText(title, Modifier.semantics { contentDescription = title }, type = AfType.RowTitle, maxLines = 1, ellipsis = true)
            if (subtitle != null) AfText(subtitle, Modifier.semantics { contentDescription = subtitle }, color = AutoFlowColors.Muted,
                type = AfType.Caption, maxLines = 2, ellipsis = true)
        }
        trailing?.invoke()
    }
}

/** Trailing chevron for a row that opens something. */
@Composable
fun RowChevron() = AfIcon(AfIcons.Regular.Chevron, size = AfSize.IconSmall)

/** Section title over cards, with an optional trailing action (e.g. a text button). */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        AfText(title, Modifier.weight(1f), type = AfType.CardTitle)
        action?.invoke()
    }
}

/** Small group label (sidebar groups, settings groups); [Faint] passes AA only on the window background. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier, onBackground: Boolean = true) =
    AfText(text, modifier.padding(top = 4.dp), color = if (onBackground) AutoFlowColors.Faint else AutoFlowColors.Muted, type = AfType.Micro)

/** Needs-you card: tone bar on the left, icon in a tone container, actions right-aligned (outline, then primary). */
@Composable
fun AttentionCard(title: String, body: String, modifier: Modifier = Modifier, tone: Tone = Tone.Warning,
    icon: ImageVector = AfIcons.Fill.Warn, actions: @Composable RowScope.() -> Unit = {}) {
    AfCard(modifier, attention = tone) {
        Row(horizontalArrangement = Arrangement.spacedBy(AfSpace.M)) {
            Box(Modifier.size(AfSize.TileSmall).clip(RoundedCornerShape(AfRadius.TileSmall)).background(tone.container),
                contentAlignment = Alignment.Center) { AfIcon(icon, tint = tone.text, size = AfSize.IconSmall) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AfText(title, type = AfType.RowTitle)
                AfText(body, color = AutoFlowColors.Muted, type = AfType.Caption)
            }
        }
        WrapRow(Modifier.align(Alignment.End)) { Row(horizontalArrangement = Arrangement.spacedBy(AfSpace.S), content = actions) }
    }
}

/** Nothing here yet: icon, title, one line of help, and the real next action if there is one. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: StudioAction? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = AfSpace.Xl, horizontal = AfSpace.L),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AfSpace.S)) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(AutoFlowColors.Card), contentAlignment = Alignment.Center) {
            AfIcon(icon, size = AfSize.IconLarge)
        }
        AfText(title, type = AfType.RowTitle)
        AfText(body, color = AutoFlowColors.Muted, type = AfType.Caption)
        action?.let { AfButton(it.label, it.onClick, it.tag?.let { t -> Modifier.testTag(t) } ?: Modifier, ButtonKind.Primary, it.enabled) }
    }
}
