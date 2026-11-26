package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.theme.*

/*
 * Glass parts of the Settings pages (P9-17a, Settings sample `src/gen9.js`), next to the glass controls of #286: the
 * Panel top bar, group label, settings list + row with a 32 dp tile, card header, field, pill, two-segment control,
 * mono boxes, choice (radio) and switch rows, an empty row and equal-width buttons.
 */

/**
 * Panel top bar (padding 8 × 12, gap 12; vertical White .08 → .03 with a White .08 line under it): the 40 dp glass
 * back button ([onBack], tagged by [backModifier]) and [title] (17 sp bold, a heading; it wraps at large text rather
 * than being cut). Without [onBack] the title keeps the bar's height.
 */
@Composable
fun GlassTopBar(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, backModifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth()
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(GlassColors.TopBarTop, GlassColors.TopBarBottom)))
                drawRect(GlassColors.TopBarLine, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
            }
            .padding(horizontal = GlassShapes.TopBarPadX, vertical = GlassShapes.TopBarPadY),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        onBack?.let { GlassIconButton(AfIcons.Regular.Back, tr(UiText.BACK), it, backModifier, size = GlassShapes.IconButtonHeader) }
        Box(Modifier.weight(1f).heightIn(min = GlassShapes.IconButtonHeader).padding(start = if (onBack == null) 4.dp else 0.dp),
            contentAlignment = Alignment.CenterStart) {
            AfText(My.breakSyllables(title), Modifier.semantics { heading() }, type = AfType.TopBarTitle, weight = FontWeight.Bold)
        }
    }
}

/** Group label over a settings list: caption, Muted, padding 0 × 4. */
@Composable
fun GlassGroupLabel(text: String, modifier: Modifier = Modifier) {
    AfText(My.breakSyllables(text), modifier.padding(horizontal = 4.dp).semantics { heading() }, color = AutoFlowColors.Muted,
        type = AfType.Caption)
}

/** A settings list: one glass card (padding 4 × 0, gap 0) with [rows] separated by dividers inset [dividerStart] (56). */
@Composable
fun GlassSettingsList(rows: List<@Composable () -> Unit>, modifier: Modifier = Modifier, dividerStart: Dp = GlassShapes.SettingsDividerStart) {
    GlassCard(modifier, padding = PaddingValues(vertical = GlassShapes.ListPaddingY), gap = 0.dp) {
        rows.forEachIndexed { i, row ->
            if (i > 0) GlassInsetDivider(dividerStart)
            row()
        }
    }
}

/** 1 dp White .08 divider inset [start] at the start and 12 at the end. */
@Composable
fun GlassInsetDivider(start: Dp = GlassShapes.SettingsDividerStart) {
    Box(Modifier.padding(start = start, end = GlassShapes.DividerEnd).fillMaxWidth().height(1.dp).background(GlassColors.Divider))
}

/** A full-width 1 dp White .08 hairline inside a card. */
@Composable
fun GlassHairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(GlassColors.Divider))
}

/**
 * Row tile (radius 11, [size] 32): with a [colour] the soft tile ([colour] .18 + .35 border, [icon] in [colour]; pass
 * a Fill icon); without one the neutral tile (White .08 + .12 border, [icon] in Text; pass a Regular icon).
 */
@Composable
fun GlassRowTile(icon: ImageVector, colour: Color?, modifier: Modifier = Modifier, size: Dp = GlassShapes.SettingsTile) {
    if (colour != null) SoftTile(icon, colour, modifier, size)
    else Box(modifier.size(size).clip(GlassShapes.Tile).background(GlassColors.NeutralTileFill)
        .border(1.dp, GlassColors.NeutralTileBorder, GlassShapes.Tile), contentAlignment = Alignment.Center) {
        AfIcon(icon, tint = AutoFlowColors.Text, size = size * .52f)
    }
}

/**
 * A settings row (min 56, padding 8 × 12, gap 12), the whole row one button: [leading] tile, [title] (row title, wraps)
 * over an optional [subtitle] caption, an optional [trailing] value or chip, then a Muted caret-right 18. The trailing
 * part moves under the text when the text beside it would be narrower than [AfSize.TileCardMin] × the font scale.
 */
@Composable
fun GlassSettingsRow(title: String, onClick: () -> Unit, leading: @Composable () -> Unit, modifier: Modifier = Modifier,
    subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    BesideOrUnder(
        lead = leading,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AfText(My.breakSyllables(title), type = AfType.RowTitle)
                subtitle?.let { AfText(My.breakSyllables(it), color = AutoFlowColors.Muted, type = AfType.Caption) }
            }
        },
        trailing = trailing,
        end = { AfIcon(AfIcons.Regular.Chevron, tint = AutoFlowColors.Muted, size = 18.dp) },
        modifier = modifier.fillMaxWidth().heightIn(min = GlassShapes.SettingsRowMin)
            .clickable(interaction, null, role = Role.Button, onClick = onClick).interactionOverlay(interaction, RectangleShape)
            .padding(horizontal = GlassShapes.SettingsRowPadX, vertical = GlassShapes.SettingsRowPadY),
        gap = GlassShapes.ListRowGap,
    )
}

/** Trailing value on a settings row: 13 sp Muted. */
@Composable
fun GlassRowValue(text: String) = AfText(My.breakSyllables(text), color = AutoFlowColors.Muted)

/**
 * Card header (min 34, gap 10): [tile], [title] (14 sp bold, wraps) and an optional [trailing] chip or pill, which
 * moves under the title when the title beside it would be narrower than [AfSize.TileCardMin] × the font scale.
 */
@Composable
fun GlassCardHeader(title: String, tile: @Composable () -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    BesideOrUnder(
        lead = tile,
        text = { AfText(My.breakSyllables(title), type = GlassSectionType, weight = FontWeight.Bold) },
        trailing = trailing, end = null,
        modifier = modifier.fillMaxWidth().heightIn(min = GlassShapes.CardHeaderMin), gap = 10.dp,
    )
}

/**
 * [lead] · [text] (the free width) · [trailing] · [end], centred vertically; [trailing] goes under [text] when the text
 * beside it would be narrower than [AfSize.TileCardMin] × the font scale. An empty [lead] takes no room (no gap).
 */
@Composable
internal fun BesideOrUnder(lead: @Composable () -> Unit, text: @Composable () -> Unit, trailing: (@Composable () -> Unit)?,
    end: (@Composable () -> Unit)?, modifier: Modifier, gap: Dp) {
    val minText = AfSize.TileCardMin * LocalDensity.current.fontScale
    Layout(contents = listOf(lead, text, trailing ?: {}, end ?: {}), modifier = modifier) { (leadM, textM, trailM, endM), constraints ->
        val g = gap.roundToPx()
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)
        val lead = leadM.map { it.measure(loose) }
        val endP = endM.map { it.measure(loose) }
        val leadW = lead.maxOfOrNull { it.width } ?: 0
        val leadGap = if (lead.isEmpty()) 0 else g
        val endW = endP.maxOfOrNull { it.width } ?: 0
        val textMax = (width - leadW - leadGap - (if (endP.isEmpty()) 0 else endW + g)).coerceAtLeast(0)
        val trailWant = trailM.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        val beside = trailM.isEmpty() || textMax - g - trailWant >= minText.roundToPx()
        val trail = trailM.map { it.measure(Constraints(maxWidth = if (beside) trailWant.coerceAtMost(textMax) else textMax)) }
        val trailW = trail.maxOfOrNull { it.width } ?: 0
        val textW = if (beside && trail.isNotEmpty()) (textMax - g - trailW).coerceAtLeast(0) else textMax
        val textP = textM.map { it.measure(Constraints(minWidth = textW, maxWidth = textW)) }
        val leadH = lead.maxOfOrNull { it.height } ?: 0
        val textH = textP.maxOfOrNull { it.height } ?: 0
        val trailH = trail.maxOfOrNull { it.height } ?: 0
        val endH = endP.maxOfOrNull { it.height } ?: 0
        val under = 6.dp.roundToPx()
        val middleH = if (beside) maxOf(textH, trailH) else textH + (if (trail.isEmpty()) 0 else under + trailH)
        val height = maxOf(leadH, middleH, endH).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            val textX = leadW + leadGap
            lead.forEach { it.place(0, (height - it.height) / 2) }
            endP.forEach { it.place(width - it.width, (height - it.height) / 2) }
            if (beside) {
                textP.forEach { it.place(textX, (height - it.height) / 2) }
                trail.forEach { it.place(textX + textW + g, (height - it.height) / 2) }
            } else {
                val top = (height - middleH) / 2
                textP.forEach { it.place(textX, top) }
                trail.forEach { it.place(textX, top + textH + under) }
            }
        }
    }
}

/**
 * Glass text field (min 48, radius 12, black .25 + White .12 border, padding 14 × 8): [placeholder] (Faint, wraps) is
 * also its content description; [secret] masks the value and turns off suggestions, as [AfTextField].
 */
@Composable
fun GlassTextField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    secret: Boolean = false, mono: Boolean = true) {
    Box(
        modifier.heightIn(min = AfSize.Touch).widthIn(min = AfSize.Touch).clip(GlassShapes.Field).background(GlassColors.FieldFill)
            .border(1.dp, GlassColors.FieldBorder, GlassShapes.Field).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) AfText(placeholder, color = AutoFlowColors.Faint, type = AfType.Body)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
            singleLine = true,
            textStyle = afTextStyle(AutoFlowColors.Text, if (mono) 12.5.sp else AfType.Body.size, FontWeight.Normal,
                if (mono) FontFamily.Monospace else LocalUiFontFamily.current),
            cursorBrush = SolidColor(AutoFlowColors.Cyan),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else KeyboardOptions.Default,
        )
    }
}

/**
 * A field with its buttons: [field] beside [actions] (gap 8) while the field keeps at least 140 dp × the font scale,
 * else the field full width with the buttons wrapping under it. [field] should fill the width it is given.
 */
@Composable
fun GlassFieldRow(field: @Composable () -> Unit, actions: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val minField = 140.dp * LocalDensity.current.fontScale
    Layout(contents = listOf(field, { WrapRow(spacing = 8.dp) { actions() } }), modifier = modifier.fillMaxWidth()) { (fieldM, actM), constraints ->
        val gap = 8.dp.roundToPx()
        val width = constraints.maxWidth
        val want = actM.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        val beside = want == 0 || width - want - gap >= minField.roundToPx()
        if (beside) {
            val acts = actM.map { it.measure(Constraints(maxWidth = want)) }
            val actW = acts.maxOfOrNull { it.width } ?: 0
            val fieldW = (width - (if (actW > 0) actW + gap else 0)).coerceAtLeast(0)
            val fields = fieldM.map { it.measure(Constraints(minWidth = fieldW, maxWidth = fieldW)) }
            val height = maxOf(fields.maxOfOrNull { it.height } ?: 0, acts.maxOfOrNull { it.height } ?: 0)
            layout(width, height) {
                fields.forEach { it.place(0, (height - it.height) / 2) }
                acts.forEach { it.place(fieldW + gap, (height - it.height) / 2) }
            }
        } else {
            val fields = fieldM.map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
            val acts = actM.map { it.measure(Constraints(maxWidth = width)) }
            val fieldH = fields.maxOfOrNull { it.height } ?: 0
            val height = fieldH + gap + (acts.maxOfOrNull { it.height } ?: 0)
            layout(width, height) {
                fields.forEach { it.place(0, 0) }
                acts.forEach { it.place(0, fieldH + gap) }
            }
        }
    }
}

/** Small mono pill (radius 8, padding 4 × 10, black .25 + White .10 border, mono 12.5 Text); [description] for readers. */
@Composable
fun GlassPill(text: String, modifier: Modifier = Modifier, description: String? = null) {
    Box(modifier.clip(GlassShapes.Pill8).background(GlassColors.PillFill).border(1.dp, GlassColors.PillBorder, GlassShapes.Pill8)
        .padding(horizontal = 10.dp, vertical = 4.dp)) {
        AfText(text, if (description != null) Modifier.semantics { contentDescription = description } else Modifier, mono = true, size = 12.5.sp)
    }
}

/** One segment of [GlassSegmented]: its label, test tag and whether it can be picked. */
/** One segment; [tag] = its testTag (none when the caller has no tag), [icon] = before the label instead of the selected check. */
class GlassSegment(val label: String, val tag: String?, val enabled: Boolean = true, val icon: ImageVector? = null)

/**
 * Two-segment (or more) control: a track (radius 12, padding 3, gap 3, black .22 + White .10 border) of equal items
 * (38 dp, radius 9, in a 48 dp target); the selected one = the primary gradient, a white check 15 and white label.
 */
@Composable
fun GlassSegmented(segments: List<GlassSegment>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, hug: Boolean = false) {
    // [hug] (Subtitles' view switch, Group 5 A 5): each segment as wide as its label, the track as wide as its segments; at a
    // large font the segments wrap inside the track instead of cutting a word.
    val shape = GlassShapes.SegmentTrack
    val item = @Composable { i: Int, segment: GlassSegment, m: Modifier ->
        val on = i == selected
        val interaction = remember { MutableInteractionSource() }
        val itemShape = GlassShapes.SegmentItem
        Box(
            m.touchOverflow(vertical = (AfSize.Touch - GlassShapes.SegmentItemHeight) / 2)
                .then(segment.tag?.let { Modifier.testTag(it) } ?: Modifier)
                .selectable(on, interaction, null, enabled = segment.enabled, role = Role.RadioButton) { onSelect(i) }
                .padding(vertical = (AfSize.Touch - GlassShapes.SegmentItemHeight) / 2)
                .heightIn(min = GlassShapes.SegmentItemHeight)
                .alpha(if (segment.enabled) 1f else .45f)
                .then(if (on) Modifier.shadow(4.dp, itemShape, ambientColor = GlassColors.PrimaryGlow, spotColor = GlassColors.PrimaryGlow) else Modifier)
                .clip(itemShape)
                .drawBehind { if (on) drawRect(primaryBrush()) }
                .drawWithContent { drawContent(); if (on) drawRect(GlassColors.PrimaryHighlight, size = Size(size.width, 1.dp.toPx())) }
                .interactionOverlay(interaction, itemShape)
                .padding(horizontal = if (hug) 12.dp else 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            val lead: (@Composable () -> Unit)? = when {
                segment.icon != null -> { { AfIcon(segment.icon, tint = if (on) Color.White else AutoFlowColors.Muted, size = AfSize.IconXs) } }
                on && !hug -> { { AfIcon(AfIcons.Regular.Check, tint = Color.White, size = AfSize.IconXs) } }
                else -> null
            }
            SegmentLabel(lead) {
                AfText(My.breakSyllables(segment.label), color = if (on) Color.White else AutoFlowColors.Text, type = AfType.ButtonSmall,
                    textAlign = TextAlign.Center)
            }
        }
    }
    val track = Modifier.clip(shape).background(GlassColors.SegmentFill).border(1.dp, GlassColors.SegmentBorder, shape).padding(3.dp)
    if (hug) FlowRow(modifier.then(track), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        segments.forEachIndexed { i, segment -> item(i, segment, Modifier) }
    }
    else Row(modifier.fillMaxWidth().then(track).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        segments.forEachIndexed { i, segment -> item(i, segment, Modifier.weight(1f).fillMaxHeight()) }
    }
}

/**
 * A segment's [lead] icon and label: side by side (gap 6) while the label's widest syllable fits beside the icon, else
 * the icon centred above the label (narrow panes at font 2.0), so a word is never cut inside a syllable.
 */
@Composable
private fun SegmentLabel(lead: (@Composable () -> Unit)?, label: @Composable () -> Unit) {
    Layout(contents = listOf(lead ?: {}, label)) { (leadM, labelM), constraints ->
        val gap = 6.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val icon = leadM.firstOrNull()?.measure(loose)
        val text = labelM.first()
        val beside = icon?.let { it.width + gap } ?: 0
        val stack = icon != null && constraints.hasBoundedWidth &&
            text.minIntrinsicWidth(Constraints.Infinity) > constraints.maxWidth - beside
        if (stack) {
            val t = text.measure(loose)
            val w = maxOf(icon!!.width, t.width)
            layout(w, icon.height + gap / 2 + t.height) {
                icon.place((w - icon.width) / 2, 0)
                t.place((w - t.width) / 2, icon.height + gap / 2)
            }
        } else {
            val t = text.measure(if (constraints.hasBoundedWidth) loose.copy(maxWidth = (constraints.maxWidth - beside).coerceAtLeast(0)) else loose)
            val h = maxOf(icon?.height ?: 0, t.height)
            layout(beside + t.width, h) {
                icon?.place(0, (h - icon.height) / 2)
                t.place(beside, (h - t.height) / 2)
            }
        }
    }
}

/** One-line readout box (radius 10, padding 8 × 10, black .22 + White .08 border): mono 11.5 [colour], wraps. */
@Composable
fun GlassMonoBox(text: String, modifier: Modifier = Modifier, colour: Color = AutoFlowColors.Muted) {
    Box(modifier.fillMaxWidth().clip(GlassShapes.MonoBox).background(GlassColors.MonoFill).border(1.dp, GlassColors.MonoBorder, GlassShapes.MonoBox)
        .padding(horizontal = 10.dp, vertical = 8.dp)) {
        MonoText(text, 11.5.sp, colour)
    }
}

/**
 * The models JSON box (max 240, radius 12, padding 10 × 12, black .30 + White .08 border): mono 11.5 Muted that
 * scrolls inside the box, with a bottom fade while more text is below.
 */
@Composable
fun GlassJsonBox(text: String, modifier: Modifier = Modifier, state: ScrollState = rememberScrollState()) {
    val shape = GlassShapes.JsonBox
    Box(modifier.fillMaxWidth().heightIn(max = GlassShapes.JsonMax).clip(shape).background(GlassColors.JsonFill)
        .border(1.dp, GlassColors.MonoBorder, shape)
        .drawWithContent {
            drawContent()
            if (state.canScrollForward) drawRect(Brush.verticalGradient(listOf(Color.Transparent, GlassColors.JsonFade),
                startY = size.height * .8f, endY = size.height), Offset(0f, size.height * .8f), Size(size.width, size.height * .2f))
        }) {
        Box(Modifier.fillMaxWidth().paneScroll(state).padding(horizontal = 12.dp, vertical = 10.dp)) {
            MonoText(text, 11.5.sp, AutoFlowColors.Muted)
        }
    }
}

/** A choice row (min 56, padding 6 × 14): [label] (row title) and a 22 dp radio — on = the primary gradient + white check 14. */
@Composable
fun GlassChoiceRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().heightIn(min = GlassShapes.SettingsRowMin)
        .selectable(selected, interaction, null, role = Role.RadioButton, onClick = onClick).interactionOverlay(interaction, RectangleShape)
        .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AfText(label, Modifier.weight(1f), type = AfType.RowTitle)
        Box(Modifier.size(GlassShapes.Radio).then(if (selected) Modifier.shadow(4.dp, CircleShape, ambientColor = GlassColors.PrimaryGlow,
            spotColor = GlassColors.PrimaryGlow) else Modifier).clip(CircleShape)
            .then(if (selected) Modifier.drawBehind { drawRect(primaryBrush()) } else Modifier.border(2.dp, GlassColors.RadioOff, CircleShape)),
            contentAlignment = Alignment.Center) {
            if (selected) AfIcon(AfIcons.Regular.Check, tint = Color.White, size = 14.dp)
        }
    }
}

/**
 * A switch row (min 52, padding 6 × 12): [label] (body; [mono] for raw keys) and the 46 × 28 glass switch; the whole row toggles.
 * [icon] = a 20 dp Muted line icon before the label (Dubbing Export options); [enabled] = false dims it and stops the toggle.
 */
@Composable
fun GlassSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, mono: Boolean = false,
    icon: ImageVector? = null, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().heightIn(min = GlassShapes.OptionRowMin)
        .toggleable(checked, interaction, null, enabled = enabled, role = Role.Switch, onValueChange = onChange).interactionOverlay(interaction, RectangleShape)
        .then(if (enabled) Modifier else Modifier.alpha(.4f))
        .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        icon?.let { AfIcon(it, tint = AutoFlowColors.Muted, size = 20.dp) }
        AfText(label, Modifier.weight(1f), type = if (mono) null else AfType.Body, mono = mono)
        GlassSwitch(checked)
    }
}

/** The glass switch (46 × 28, radius 14): on = the primary gradient + glow, white knob right; off = White .14 + .12 border. */
@Composable
fun GlassSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val track = GlassShapes.SwitchTrack
    val shape = RoundedCornerShape(track.height / 2)
    Box(modifier.size(track).then(if (checked) Modifier.shadow(5.dp, shape, ambientColor = GlassColors.PrimaryGlow, spotColor = GlassColors.PrimaryGlow)
        else Modifier).clip(shape)
        .drawBehind { if (checked) drawRect(primaryBrush()) else drawRect(GlassColors.SwitchOffFill) }
        .then(if (checked) Modifier else Modifier.border(1.dp, GlassColors.SwitchOffBorder, shape))) {
        val inset = (track.height - GlassShapes.SwitchKnob) / 2
        Box(Modifier.offset(x = if (checked) track.width - GlassShapes.SwitchKnob - inset else inset, y = inset)
            .size(GlassShapes.SwitchKnob).shadow(2.dp, CircleShape, ambientColor = GlassColors.SwitchKnobShadow, spotColor = GlassColors.SwitchKnobShadow)
            .clip(CircleShape).background(if (checked) Color.White else GlassColors.SwitchKnobOff))
    }
}

/** Empty row (radius 14, White .04 + .08 border, padding 10 × 12, gap 10): a faded 28 dp soft tile and a Muted caption. */
@Composable
fun GlassEmptyRow(icon: ImageVector, colour: Color, text: String, modifier: Modifier = Modifier) {
    val shape = GlassShapes.EmptyRow
    Row(modifier.fillMaxWidth().clip(shape)
        .background(GlassColors.EmptyRowFill).border(1.dp, GlassColors.EmptyRowBorder, shape)
        .padding(horizontal = GlassShapes.EmptyRowPadX, vertical = GlassShapes.EmptyRowPadY),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GlassShapes.EmptyRowGap)) {
        SoftTile(icon, colour, Modifier.alpha(GlassColors.EmptyTileAlpha), size = GlassShapes.EmptyTile)
        AfText(My.breakSyllables(text), Modifier.weight(1f), color = AutoFlowColors.Muted, type = AfType.Caption)
    }
}

/**
 * Inner collapsible box (radius 12, White .04 + .09 border): a 48 dp header ([title] + caret down / up 18) that
 * toggles [content] below it, inside the same box.
 */
@Composable
fun GlassCollapsible(title: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    val shape = GlassShapes.InnerRow
    val interaction = remember { MutableInteractionSource() }
    Column(modifier.fillMaxWidth().clip(shape).background(GlassColors.InnerRowFill).border(1.dp, GlassColors.InnerRowBorder, shape)) {
        Row(Modifier.fillMaxWidth().heightIn(min = AfSize.Touch)
            .clickable(interaction, null, role = Role.Button, onClick = onToggle).interactionOverlay(interaction, shape)
            .semantics { contentDescription = title; stateDescription = if (expanded) "expanded" else "collapsed" }
            .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AfText(My.breakSyllables(title), Modifier.weight(1f), type = AfType.RowTitle)
            AfIcon(AfIcons.Regular.CaretDown, if (expanded) Modifier.rotate(180f) else Modifier, tint = AutoFlowColors.Muted, size = 18.dp)
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/**
 * Equal-width buttons in one row (gap 8, e.g. Save / Test / Remove); stacked full width (gap 8) when a label would
 * need a second line at its share of the width.
 */
@Composable
fun GlassEqualButtons(actions: List<GlassDialogAction>, modifier: Modifier = Modifier) {
    val gap = 8.dp
    val measurer = rememberTextMeasurer()
    val family = LocalUiFontFamily.current
    val density = LocalDensity.current
    @Composable fun Button(a: GlassDialogAction, m: Modifier) =
        GlassButton(a.text, a.onClick, m.then(if (a.tag != null) Modifier.testTag(a.tag) else Modifier), a.kind, a.enabled, a.icon)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val share = (maxWidth - gap * (actions.size - 1)) / actions.size
        fun oneLine(a: GlassDialogAction): Boolean {
            val label = measurer.measure(a.text, afTextStyle(Color.Unspecified, AfType.Button.size, AfType.Button.weight, family),
                softWrap = false, maxLines = 1, density = density).size.width
            return with(density) { label.toDp() } + 28.dp + (if (a.icon != null) 24.dp else 0.dp) <= share
        }
        if (actions.all(::oneLine)) Row(Modifier.fillMaxWidth().testTag("glass-buttons-row"), horizontalArrangement = Arrangement.spacedBy(gap)) {
            actions.forEach { Button(it, Modifier.weight(1f)) }
        } else Column(Modifier.fillMaxWidth().testTag("glass-buttons-stacked"), verticalArrangement = Arrangement.spacedBy(gap)) {
            actions.forEach { Button(it, Modifier.fillMaxWidth()) }
        }
    }
}
