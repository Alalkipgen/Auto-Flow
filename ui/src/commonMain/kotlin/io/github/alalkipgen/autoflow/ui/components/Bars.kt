package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.*
import androidx.compose.ui.text.style.TextAlign
import io.github.alalkipgen.autoflow.ui.icons.IconTile
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
    val shape = RoundedCornerShape(AfRadius.Button)
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
 * `progress-segment-<i>`; the whole bar reports its progress to screen readers. With [onSelect] (and [labels]) every
 * step is a ≥ 48 dp tab — its label the description and tooltip, the current one "selected", tagged [tags]`[i]` — so
 * the bar also moves between the steps (Dubbing); without labels on a phone the bars alone are the targets.
 * [glass] (Dubbing, Group 4 A 2): done bars the primary gradient, the current one Purple → Cyan with a glow, later
 * ones White .14, the current label bold; desktop numbered steps as 22 dp dots — done green with a check, current a
 * Cyan ring and number, later a White .22 ring — joined by 2 dp lines (green after a done step).
 */
@Composable
fun StepBar(labels: List<String>, current: Int, modifier: Modifier = Modifier, count: Int = labels.size,
    onSelect: ((Int) -> Unit)? = null, tags: List<String> = emptyList(), glass: Boolean = false) {
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
        // A step as a tab (when the bar also navigates): 48 dp tall, centred, labelled, "selected" (as AfChip) when current.
        val tab: @Composable (Int) -> Modifier = { i ->
            if (onSelect == null) Modifier else Modifier.afTooltip(labels[i]).heightIn(min = AfSize.Touch)
                .selectable(i == current - 1, remember(i) { MutableInteractionSource() }, null, role = Role.Tab) { onSelect(i) }
                .semantics { contentDescription = labels[i]; stateDescription = if (i == current - 1) "selected" else "not selected" }
                .let { m -> tags.getOrNull(i)?.let { m.testTag(it) } ?: m }
        }
        if (numbered) { if (glass) GlassNumberedSteps(texts, current, tab) else NumberedSteps(texts, current, tab) } else {
            val column = (constraints.maxWidth - gap * (count - 1)) / count
            // Two-line labels would push the first content below the fold at font 2.0 (412 × 760, system font).
            val everyLabel = texts.isNotEmpty() && texts.indices.all { i ->
                !measurer.measure(texts[i], afTextStyle(Color.Unspecified, AfType.Micro.size,
                    if (i == current - 1) FontWeight.SemiBold else FontWeight.Normal, family),
                    maxLines = 1, constraints = Constraints(maxWidth = column.coerceAtLeast(1))).hasVisualOverflow
            }
            // Each step is one column (its bar, then its label), so a tab holds both.
            Row(horizontalArrangement = Arrangement.spacedBy(AfSize.StepGap)) {
                repeat(count) { i ->
                    Column(Modifier.weight(1f).then(tab(i)), verticalArrangement = Arrangement.spacedBy(AfSpace.Xs, Alignment.CenterVertically)) {
                        if (glass) GlassStepSegment(i, current) else Box(Modifier.fillMaxWidth().height(AfSize.StepSegment).testTag("progress-segment-$i")
                            .clip(RoundedCornerShape(2.dp)).background(if (i < current) AutoFlowColors.PrimaryFill else AutoFlowColors.Line))
                        if (everyLabel) AfText(texts[i], Modifier.fillMaxWidth(), type = AfType.Micro, maxLines = 1,
                            weight = if (i == current - 1) (if (glass) FontWeight.Bold else FontWeight.SemiBold) else FontWeight.Normal,
                            color = if (i == current - 1) AutoFlowColors.PrimaryText else AutoFlowColors.Muted)
                    }
                }
            }
        }
    }
}

private val StepDot = 24.dp
private val GlassStepDot = 22.dp
private val StepShape = RoundedCornerShape(2.dp)

/** A glass phone step bar segment (step [i], 0-based, of the 1-based [current]): see [StepBar]. */
@Composable
private fun GlassStepSegment(i: Int, current: Int) {
    val now = i == current - 1
    Box(Modifier.fillMaxWidth().height(AfSize.StepSegment).testTag("progress-segment-$i")
        .then(if (now) Modifier.shadow(4.dp, StepShape, ambientColor = GlassColors.ProgressGlow, spotColor = GlassColors.ProgressGlow) else Modifier)
        .clip(StepShape).drawBehind {
            when {
                i < current - 1 -> drawRect(Brush.horizontalGradient(listOf(GlassColors.PrimaryStart, AutoFlowColors.Purple)))
                now -> drawRect(Brush.horizontalGradient(listOf(AutoFlowColors.Purple, AutoFlowColors.Cyan)))
                else -> drawRect(GlassColors.StepLine)
            }
        })
}

/** Desktop numbered steps in glass (see [StepBar]): 22 dp dots (growing with large numbers), labels beside them, 2 dp joins. */
@Composable
private fun GlassNumberedSteps(texts: List<String>, current: Int, tab: @Composable (Int) -> Modifier) = Row(verticalAlignment = Alignment.CenterVertically) {
    texts.forEachIndexed { i, t ->
        val done = i < current - 1
        val now = i == current - 1
        Box(tab(i), contentAlignment = Alignment.Center) { Row(Modifier.testTag("progress-segment-$i"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AfSpace.S)) {
            Box(Modifier.clip(CircleShape)
                .background(when { done -> AutoFlowColors.Green; now -> GlassColors.StepDotNowFill; else -> GlassColors.StepDotFill })
                .then(if (done) Modifier else Modifier.border(1.5.dp, if (now) AutoFlowColors.Cyan else GlassColors.StepDotBorder, CircleShape))
                .layout { m, c ->
                    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                    val side = maxOf(p.width, p.height, GlassStepDot.roundToPx())
                    layout(side, side) { p.place((side - p.width) / 2, (side - p.height) / 2) }
                }, contentAlignment = Alignment.Center) {
                if (done) AfIcon(AfIcons.Regular.Check, tint = Color.White, size = AfSize.IconXs)
                else AfText("${i + 1}", color = if (now) AutoFlowColors.Cyan else AutoFlowColors.Muted, type = AfType.Micro)
            }
            AfText(t, color = if (now) AutoFlowColors.PrimaryText else AutoFlowColors.Muted,
                type = AfType.Caption, weight = if (now) FontWeight.Bold else null, maxLines = 2)
        } }
        if (i < texts.lastIndex) Box(Modifier.weight(1f).padding(horizontal = AfSpace.S).height(2.dp).clip(StepShape)
            .background(if (done) AutoFlowColors.Green else GlassColors.StepLine))
    }
}

@Composable
private fun NumberedSteps(texts: List<String>, current: Int, tab: @Composable (Int) -> Modifier) = Row(verticalAlignment = Alignment.CenterVertically) {
    texts.forEachIndexed { i, t ->
        val done = i < current - 1
        val now = i == current - 1
        Box(tab(i), contentAlignment = Alignment.Center) { Row(Modifier.testTag("progress-segment-$i"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AfSpace.S)) {
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
        } }
        if (i < texts.lastIndex) Box(Modifier.weight(1f).padding(horizontal = AfSpace.S).height(1.dp)
            .background(if (done) AutoFlowColors.PrimaryFill else AutoFlowColors.Line))
    }
}

/**
 * Sticky bar actions. Phone: [primary] full width, or [secondary] + [primary] of equal width (stacked, each full
 * width, when a label does not fit half the bar), an optional [leading] control first, then the [hint] centred below.
 * Desktop: the hint on the left and the buttons on the right (each ≥ 150 dp, never full width); the hint moves above
 * the buttons when both do not fit one line. [keys] (desktop only, e.g. [KeyHints]) go above the hint.
 */
@Composable
internal fun StickyActions(primary: @Composable (Modifier) -> Unit, secondary: (@Composable (Modifier) -> Unit)?,
    hint: String?, leading: (@Composable () -> Unit)?, keys: (@Composable () -> Unit)? = null, hintIcon: ImageVector? = null,
    hintColour: Color? = null, hintTag: String? = null) {
    // [hintIcon] (15 dp, before the hint) and [hintColour] (default Muted): e.g. Dubbing's approved caption (green check-circle).
    // [hintTag] = the hint text's test tag.
    val tag = hintTag?.let { Modifier.testTag(it) } ?: Modifier
    val hintText = @Composable { text: String, centred: Boolean ->
        if (hintIcon == null) AfText(text, (if (centred) Modifier.fillMaxWidth() else Modifier).then(tag), color = hintColour ?: AutoFlowColors.Muted,
            type = AfType.Caption, textAlign = if (centred) TextAlign.Center else null)
        else Row(if (centred) Modifier.fillMaxWidth() else Modifier, horizontalArrangement = Arrangement.spacedBy(6.dp,
            if (centred) Alignment.CenterHorizontally else Alignment.Start), verticalAlignment = Alignment.CenterVertically) {
            AfIcon(hintIcon, tint = hintColour ?: AutoFlowColors.Muted, size = 15.dp)
            AfText(text, Modifier.weight(1f, fill = false).then(tag), color = hintColour ?: AutoFlowColors.Muted, type = AfType.Caption,
                textAlign = if (centred) TextAlign.Center else null)
        }
    }
    if (LocalCompact.current) Column(verticalArrangement = Arrangement.spacedBy(AfSpace.S)) {
        Layout({ leading?.invoke(); secondary?.invoke(Modifier); primary(Modifier) }) { items, c ->
            val gap = AfSpace.S.roundToPx()
            val lead = if (leading != null) items.first() else null
            val buttons = if (leading != null) items.drop(1) else items
            val leadWidth = lead?.maxIntrinsicWidth(Constraints.Infinity) ?: 0
            val share = (c.maxWidth - (if (lead != null) leadWidth + gap else 0) - gap * (buttons.size - 1)) / buttons.size
            if (buttons.all { it.maxIntrinsicWidth(Constraints.Infinity) <= share }) {
                val placed = listOfNotNull(lead?.measure(Constraints(maxWidth = leadWidth))) + buttons.map { it.measure(Constraints.fixedWidth(share)) }
                val h = placed.maxOf { it.height }
                layout(c.maxWidth, h) { var x = 0; placed.forEach { it.place(x, (h - it.height) / 2); x += it.width + gap } }
            } else {
                val placed = items.map { it.measure(Constraints.fixedWidth(c.maxWidth)) }
                layout(c.maxWidth, placed.sumOf { it.height } + gap * (placed.size - 1)) {
                    var y = 0; placed.forEach { it.place(0, y); y += it.height + gap }
                }
            }
        }
        hint?.let { hintText(it, true) }
    } else Layout({
        if (hint != null || keys != null) Column(verticalArrangement = Arrangement.spacedBy(AfSpace.Xs)) {
            keys?.invoke()
            hint?.let { hintText(it, false) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AfSpace.S, Alignment.End), verticalArrangement = Arrangement.spacedBy(AfSpace.S),
            itemVerticalAlignment = Alignment.CenterVertically) {
            leading?.invoke(); secondary?.invoke(Modifier.widthIn(min = AfDesktop.StickyButtonMin)); primary(Modifier.widthIn(min = AfDesktop.StickyButtonMin))
        }
    }) { items, c ->
        val gap = AfSpace.L.roundToPx()
        val text = if (hint != null || keys != null) items.first() else null
        val row = items.last()
        val rowWidth = row.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(c.maxWidth)
        val beside = text == null || rowWidth + gap + minOf(text.maxIntrinsicWidth(Constraints.Infinity), HintMin.roundToPx()) <= c.maxWidth
        if (beside) {
            val buttons = row.measure(Constraints(maxWidth = rowWidth))
            val label = text?.measure(Constraints(maxWidth = (c.maxWidth - rowWidth - gap).coerceAtLeast(0)))
            val h = maxOf(buttons.height, label?.height ?: 0)
            layout(c.maxWidth, h) { label?.place(0, (h - label.height) / 2); buttons.place(c.maxWidth - buttons.width, (h - buttons.height) / 2) }
        } else {
            val label = text!!.measure(Constraints(maxWidth = c.maxWidth))
            val buttons = row.measure(Constraints(maxWidth = c.maxWidth))
            val v = AfSpace.S.roundToPx()
            layout(c.maxWidth, label.height + v + buttons.height) { label.place(0, 0); buttons.place(c.maxWidth - buttons.width, label.height + v) }
        }
    }
}

private val HintMin = 200.dp

/**
 * One choice from a few (DESIGN.md §9.4): `Card` track with a `LineStrong` outline, radius 12, padding 3; the selected
 * segment `Surface3`. Each segment is a 48 dp target around its 34 dp visual; labels take ≤ 2 lines, then "…" with the
 * full text kept for screen readers. When a word does not fit its equal share, the segments stack (one per line).
 * [tags]: optional test tags, one per option.
 */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    tags: List<String>? = null) {
    val inner = RoundedCornerShape(AfRadius.Button - SegmentPad)
    Layout({
        options.forEachIndexed { i, option ->
            val interaction = remember { MutableInteractionSource() }
            val on = i == selected
            val text = My.breakSyllables(option)
            Box(Modifier.then(tags?.let { Modifier.testTag(it[i]) } ?: Modifier).widthIn(min = AfSize.Touch).heightIn(min = AfSize.Touch)
                .selectable(on, interaction, null, enabled, Role.RadioButton) { onSelect(i) }
                .padding(vertical = (AfSize.Touch - SegmentVisual) / 2).heightIn(min = SegmentVisual)
                .clip(inner).background(if (on) AutoFlowColors.Surface3 else Color.Transparent)
                .interactionOverlay(interaction, inner).padding(horizontal = AfSpace.S), contentAlignment = Alignment.Center) {
                AfText(text, Modifier.semantics { contentDescription = text }, color = if (on) AutoFlowColors.Text else AutoFlowColors.Muted,
                    type = AfType.ButtonSmall, maxLines = 2, ellipsis = true, textAlign = TextAlign.Center)
            }
        }
    }, modifier.drawBehind {
        val inset = (AfSize.Touch - SegmentVisual - SegmentPad * 2).toPx() / 2
        val r = CornerRadius(AfRadius.Button.toPx())
        drawRoundRect(AutoFlowColors.Card, Offset(0f, inset), Size(size.width, size.height - inset * 2), r)
        drawRoundRect(AutoFlowColors.LineStrong, Offset(0f, inset), Size(size.width, size.height - inset * 2), r, Stroke(1.dp.toPx()))
    }.selectableGroup().alpha(if (enabled) 1f else .4f), SegmentedPolicy)
}

private val SegmentVisual = 34.dp
private val SegmentPad = 3.dp

/** Equal shares in one row when every segment's longest word fits its share; otherwise one segment per line. The control
 * takes its natural width (widest segment × count) unless the modifier makes it fill. */
private object SegmentedPolicy : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val pad = SegmentPad.roundToPx()
        val widest = measurables.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) }
        val width = (widest * measurables.size + pad * 2).coerceIn(constraints.minWidth, constraints.maxWidth) // natural unless told to fill
        val share = (width - pad * 2) / measurables.size
        return if (measurables.all { it.minIntrinsicWidth(Constraints.Infinity) <= share }) {
            val h = measurables.maxOf { it.maxIntrinsicHeight(share) }
            val placed = measurables.map { it.measure(Constraints.fixed(share, h)) }
            layout(width, h) { placed.forEachIndexed { i, p -> p.place(pad + share * i, 0) } }
        } else {
            val placed = measurables.map { it.measure(Constraints.fixedWidth(width - pad * 2)) }
            layout(width, placed.sumOf { it.height }) { var y = 0; placed.forEach { it.place(pad, y); y += it.height } }
        }
    }
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables.maxOf { it.maxIntrinsicWidth(height) } * measurables.size + SegmentPad.roundToPx() * 2
    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables.maxOf { it.minIntrinsicWidth(height) } + SegmentPad.roundToPx() * 2
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        measurables.maxOf { it.maxIntrinsicHeight(width / measurables.size) }
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        measurables.maxOf { it.minIntrinsicHeight(width / measurables.size) }
}

/** Title + optional subtitle with a switch at the end (and an optional icon tile first); the whole row toggles. */
@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
    icon: ImageVector? = null, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(AfRadius.Button)
    Row(modifier.fillMaxWidth().heightIn(min = AfSize.Touch).clip(shape)
        .toggleable(checked, interaction, null, enabled, Role.Switch, onChange)
        .semantics { stateDescription = if (checked) "on" else "off" }
        .interactionOverlay(interaction, shape).alpha(if (enabled) 1f else .4f).padding(vertical = AfSpace.S),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AfSpace.M)) {
        icon?.let { IconTile(it, AutoFlowColors.Surface3, size = AfSize.TileSmall, radius = AfRadius.TileSmall, ink = AutoFlowColors.Text) }
        Column(Modifier.weight(1f)) {
            AfText(title, type = AfType.RowTitle)
            subtitle?.let { AfText(it, color = AutoFlowColors.Muted, type = AfType.Caption) }
        }
        SwitchTrack(checked)
    }
}

/** The switch drawing: 40 × 24 pill, `PrimaryFill` when on, `LineStrong` when off, white 18 dp thumb. */
@Composable
internal fun SwitchTrack(checked: Boolean) = Box(Modifier.size(40.dp, 24.dp).clip(CircleShape)
    .background(if (checked) AutoFlowColors.PrimaryFill else AutoFlowColors.LineStrong)) {
    Box(Modifier.offset(x = if (checked) 19.dp else 3.dp, y = 3.dp).size(18.dp).clip(CircleShape).background(Color.White))
}

/**
 * Keyboard shortcuts for a desktop sticky bar (DESIGN.md §9.8, D4): each key in a small outlined cap, then what it does
 * ([keys] = key name to its label). Only list keys the screen really handles.
 */
@Composable
fun KeyHints(keys: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    FlowRow(modifier.testTag("key-hints"), horizontalArrangement = Arrangement.spacedBy(AfSpace.L),
        verticalArrangement = Arrangement.spacedBy(AfSpace.Xs), itemVerticalAlignment = Alignment.CenterVertically) {
        keys.forEach { (key, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AfSpace.S)) {
                AfText(key, Modifier.border(AutoFlowShapes.Border, AutoFlowColors.LineStrong, RoundedCornerShape(AfRadius.Key))
                    .padding(horizontal = AfSpace.S).testTag("key-hint-cap"), type = AfType.Micro)
                AfText(My.breakSyllables(label), color = AutoFlowColors.Muted, type = AfType.Caption)
            }
        }
    }
}
