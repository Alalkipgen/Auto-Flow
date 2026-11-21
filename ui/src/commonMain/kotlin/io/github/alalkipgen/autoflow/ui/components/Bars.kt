package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.icons.afTooltip
import io.github.alalkipgen.autoflow.ui.theme.*

/** Icon-only button: 40 dp card-coloured square with an outline in a 48 dp target; [label] is its content description and desktop tooltip. */
@Composable
fun AfIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    selected: Boolean = false, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(AfRadius.ButtonSmall)
    Box(modifier.afTooltip(label).size(AfSize.Touch)
        .clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }
        .padding((AfSize.Touch - AfSize.IconButton) / 2).alpha(if (enabled) 1f else .4f).clip(shape)
        .background(if (selected) Tone.Primary.container else AutoFlowColors.Card)
        .border(1.dp, if (selected) AutoFlowColors.PrimaryFill else AutoFlowColors.Line, shape)
        .interactionOverlay(interaction, shape), contentAlignment = Alignment.Center) {
        AfIcon(icon, tint = if (selected) AutoFlowColors.PrimaryText else AutoFlowColors.Text)
    }
}

/**
 * Screen top bar: back icon button, 1-line [title] (cut with "…", full text kept for screen readers), [subtitle] with
 * an optional 8 dp [status] dot, then at most 2 trailing icon buttons ([actions]).
 */
@Composable
fun AfTopBar(title: String, modifier: Modifier = Modifier, subtitle: String? = null, status: Tone? = null,
    onBack: (() -> Unit)? = null, backModifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AfSpace.Xs)) {
        onBack?.let { AfIconButton(AfIcons.Regular.Back, tr(UiText.BACK), it, backModifier) }
        Column(Modifier.weight(1f).padding(start = if (onBack == null) AfSpace.Xs else 0.dp)) {
            AfText(title, Modifier.semantics { contentDescription = title }, type = AfType.TopBarTitle, maxLines = 1, ellipsis = true)
            if (subtitle != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                status?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(it.fill)) }
                AfText(subtitle, color = AutoFlowColors.Muted, type = AfType.Caption)
            }
        }
        actions()
    }
}

/**
 * Flow steps, [current] 1-based of [count]. Phone: equal 4 dp bars (done and current primary, to-do `Line`) with a
 * one-line label under each (current `PrimaryText`); when any label needs a second line (large fonts) the bars stand
 * alone, so the content keeps its room — callers name the current step elsewhere (the top bar subtitle). Desktop:
 * numbered steps (done = check), or the phone bars when they do not fit the width. Each step is tagged
 * `progress-segment-<i>`; the whole bar reports its progress to screen readers.
 */
@Composable
fun StepBar(labels: List<String>, current: Int, modifier: Modifier = Modifier, count: Int = labels.size) {
    require(count > 0 && current in 1..count && (labels.isEmpty() || labels.size == count))
    val texts = labels.map { My.breakSyllables(it) }
    val measurer = rememberTextMeasurer()
    val family = LocalUiFontFamily.current
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth().semantics { progressBarRangeInfo = ProgressBarRangeInfo(current.toFloat(), 0f..count.toFloat(), count - 1) }) {
        val gap = with(density) { AfSize.StepGap.roundToPx() }
        fun width(text: String, weight: FontWeight) =
            measurer.measure(text, afTextStyle(Color.Unspecified, AfType.Caption.size, weight, family), maxLines = 1)
        // Numbered steps only when every label fits on one line next to its dot, with room for the connectors.
        val dot = maxOf(with(density) { StepDot.roundToPx() }, measurer.measure("8",
            afTextStyle(Color.Unspecified, AfType.Micro.size, AfType.Micro.weight, family)).size.height)
        val numbered = !LocalCompact.current && texts.isNotEmpty() && texts.indices.sumOf { i ->
            width(texts[i], if (i == current - 1) FontWeight.SemiBold else FontWeight.Normal).size.width
        } + (dot + with(density) { AfSpace.S.roundToPx() }) * count +
            with(density) { (AfSpace.S * 2 + AfSpace.L).roundToPx() } * (count - 1) <= constraints.maxWidth
        if (numbered) NumberedSteps(texts, current) else {
            val column = (constraints.maxWidth - gap * (count - 1)) / count
            // Two-line labels would push the first content below the fold at font 2.0 (412 × 760, system font).
            val everyLabel = texts.isNotEmpty() && texts.indices.all { i ->
                !measurer.measure(texts[i], afTextStyle(Color.Unspecified, AfType.Micro.size,
                    if (i == current - 1) FontWeight.SemiBold else FontWeight.Normal, family),
                    maxLines = 1, constraints = Constraints(maxWidth = column.coerceAtLeast(1))).hasVisualOverflow
            }
            Column(verticalArrangement = Arrangement.spacedBy(AfSpace.Xs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(AfSize.StepGap)) {
                    repeat(count) { i -> Box(Modifier.weight(1f).height(AfSize.StepSegment).testTag("progress-segment-$i")
                        .clip(RoundedCornerShape(2.dp)).background(if (i < current) AutoFlowColors.PrimaryFill else AutoFlowColors.Line)) }
                }
                if (everyLabel) Row(horizontalArrangement = Arrangement.spacedBy(AfSize.StepGap)) {
                    texts.forEachIndexed { i, t -> AfText(t, Modifier.weight(1f), type = AfType.Micro, maxLines = 1,
                        weight = if (i == current - 1) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (i == current - 1) AutoFlowColors.PrimaryText else AutoFlowColors.Muted) }
                }
            }
        }
    }
}

private val StepDot = 24.dp

@Composable
private fun NumberedSteps(texts: List<String>, current: Int) = Row(verticalAlignment = Alignment.CenterVertically) {
    texts.forEachIndexed { i, t ->
        val done = i < current - 1
        val now = i == current - 1
        Row(Modifier.testTag("progress-segment-$i"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AfSpace.S)) {
            // A circle at least 24 dp that grows with the number at large font sizes.
            Box(Modifier.clip(CircleShape).background(if (done || now) AutoFlowColors.PrimaryFill else Color.Transparent)
                .border(1.dp, if (done || now) AutoFlowColors.PrimaryFill else AutoFlowColors.LineStrong, CircleShape)
                .layout { m, c ->
                    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                    val side = maxOf(p.width, p.height, StepDot.roundToPx())
                    layout(side, side) { p.place((side - p.width) / 2, (side - p.height) / 2) }
                }, contentAlignment = Alignment.Center) {
                if (done) AfIcon(AfIcons.Regular.Check, tint = Color.White, size = AfSize.IconXs)
                else AfText("${i + 1}", color = if (now) Color.White else AutoFlowColors.Muted, type = AfType.Micro)
            }
            AfText(t, color = if (now) AutoFlowColors.PrimaryText else if (done) AutoFlowColors.Text else AutoFlowColors.Muted,
                type = AfType.Caption, weight = if (now) FontWeight.SemiBold else null, maxLines = 2)
        }
        if (i < texts.lastIndex) Box(Modifier.weight(1f).padding(horizontal = AfSpace.S).height(1.dp)
            .background(if (done) AutoFlowColors.PrimaryFill else AutoFlowColors.Line))
    }
}
