package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.theme.AfRadius
import io.github.alalkipgen.autoflow.ui.theme.AfSize
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import io.github.alalkipgen.autoflow.ui.theme.afTextStyle

/*
 * Small foundation-only controls in the DESIGN.md §1 style. `:ui` has no Material dependency, so every screen builds
 * on these.
 */

private val ControlShape = RoundedCornerShape(8.dp)

/**
 * Body text; [muted] for secondary lines, [mono] for prompts, paths and JSON. A Phase 9 [type] sets size and weight
 * (DESIGN.md §9.2); [maxLines] with [ellipsis] is for one-line titles whose full text is shown elsewhere (D-100).
 */
@Composable
fun AfText(
    text: String,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    size: TextUnit = 13.sp,
    bold: Boolean = false,
    mono: Boolean = false,
    color: Color? = null,
    type: AfType? = null,
    maxLines: Int = Int.MAX_VALUE,
    ellipsis: Boolean = false,
) {
    // The bundled Burmese fonts do not contain U+21BB, and a system symbol font is
    // not guaranteed. Draw that decoration only; retain the exact label/semantics and prose font.
    val content = remember(text) {
        if ('↻' !in text) AnnotatedString(text) else buildAnnotatedString {
            text.forEach { char ->
                if (char == '↻') appendInlineContent("regenerate-symbol", "↻") else append(char)
            }
        }
    }
    val ink = color ?: if (muted) AutoFlowColors.Muted else AutoFlowColors.Text
    BasicText(
        text = content,
        modifier = modifier,
        style = afTextStyle(
            ink,
            type?.size ?: size,
            type?.weight ?: if (bold) FontWeight.Bold else FontWeight.Normal,
            if (mono) FontFamily.Monospace else LocalUiFontFamily.current,
            numbers = type?.numbers == true,
        ),
        overflow = if (ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
        maxLines = maxLines,
        inlineContent = if ('↻' !in text) emptyMap() else mapOf(
            "regenerate-symbol" to InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = this.size.width
                    val h = this.size.height
                    drawArc(ink, 45f, 315f, false, Offset(w * .17f, h * .17f),
                        Size(w * .66f, h * .66f), style = Stroke(w * .10f, cap = StrokeCap.Round))
                    drawPath(Path().apply {
                        moveTo(w * .69f, h * .39f)
                        lineTo(w * .83f, h * .58f)
                        lineTo(w * .97f, h * .39f)
                        close()
                    }, ink)
                }
            },
        ),
    )
}

enum class ButtonKind { Primary, Secondary, Flow, Danger, Text }

/**
 * DESIGN.md §9.4: Primary = filled purple (one per area), Secondary = outline, Text = no frame, Flow = cyan (Flow /
 * external), Danger = error outline (destructive). 44 dp visual ([small] 36) inside a 48 dp touch target, optional
 * 18 dp [icon]; hover / press overlay, keyboard focus ring, disabled at 40 %.
 */
@Composable
fun AfButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    small: Boolean = false,
) {
    val (fill, line, ink) = when (kind) {
        ButtonKind.Primary -> Triple(AutoFlowColors.PrimaryFill, AutoFlowColors.PrimaryFill, Color.White)
        ButtonKind.Flow -> Triple(AutoFlowColors.Cyan, AutoFlowColors.Cyan, AutoFlowColors.Background)
        ButtonKind.Secondary -> Triple(Color.Transparent, AutoFlowColors.LineStrong, AutoFlowColors.Text)
        ButtonKind.Danger -> Triple(Color.Transparent, AutoFlowColors.Failed, AutoFlowColors.Failed)
        ButtonKind.Text -> Triple(Color.Transparent, Color.Transparent, AutoFlowColors.PrimaryText)
    }
    val shape = RoundedCornerShape(if (small) AfRadius.ButtonSmall else AfRadius.Button)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.touchTarget(interaction, enabled, Role.Button, if (small) AfSize.ButtonSmall else AfSize.Button, onClick)
            .clip(shape).background(fill).border(AutoFlowShapes.Border, line, shape)
            .interactionOverlay(interaction, shape)
            .padding(horizontal = if (small) 12.dp else 15.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (icon != null) AfIcon(icon, tint = ink, size = AfSize.IconSmall)
            AfText(text, color = ink, type = if (small) AfType.ButtonSmall else AfType.Button)
        }
    }
}

/** A 48 dp touch target around a smaller [visual] control (DESIGN.md §9.2); disabled draws at 40 %. */
internal fun Modifier.touchTarget(interaction: MutableInteractionSource, enabled: Boolean, role: Role, visual: Dp,
    onClick: () -> Unit): Modifier =
    heightIn(min = AfSize.Touch).widthIn(min = AfSize.Touch)
        .clickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
        .padding(vertical = (AfSize.Touch - visual) / 2).heightIn(min = visual)
        .alpha(if (enabled) 1f else .4f)

/** Hover lightens 5 %, press 8 %, keyboard focus draws a 2 dp primary ring (DESIGN.md §9.4). */
@Composable
internal fun Modifier.interactionOverlay(interaction: InteractionSource, shape: Shape): Modifier {
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val overlay = when { pressed -> .08f; hovered -> .05f; else -> 0f }
    return drawWithContent { drawContent(); if (overlay > 0f) drawRect(Color.White.copy(alpha = overlay)) }
        .then(if (focused) Modifier.border(2.dp, AutoFlowColors.PrimaryText, shape) else Modifier)
}

/**
 * Filter / option chip (DESIGN.md §9.4): 34 dp pill in a 48 dp target, outline; selected = filled primary. A filter
 * shows its [count] at 80 %.
 */
@Composable
fun AfChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    count: Int? = null, icon: ImageVector? = null) {
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    val ink = if (selected) Color.White else AutoFlowColors.Text
    Box(
        modifier.semantics { stateDescription = if (selected) "selected" else "not selected" }
            .touchTarget(interaction, enabled, Role.RadioButton, AfSize.FilterChip, onClick)
            .clip(shape).background(if (selected) AutoFlowColors.PrimaryFill else Color.Transparent)
            .border(AutoFlowShapes.Border, if (selected) AutoFlowColors.PrimaryFill else AutoFlowColors.LineStrong, shape)
            .interactionOverlay(interaction, shape)
            .padding(horizontal = 13.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) AfIcon(icon, tint = ink, size = AfSize.IconXs)
            AfText(text, color = ink, type = AfType.Micro)
            if (count != null) AfText("$count", color = ink.copy(alpha = .8f), type = AfType.Micro)
        }
    }
}

/** On/off switch with a [label]; the whole row toggles. */
@Composable
fun AfSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .heightIn(min = 48.dp).widthIn(min = 48.dp)
            .clip(ControlShape)
            .clickable(role = Role.Switch) { onChange(!checked) }
            .semantics { stateDescription = if (checked) "on" else "off" }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(width = 34.dp, height = 18.dp)
                .clip(CircleShape)
                .background(if (checked) AutoFlowColors.Purple else AutoFlowColors.Line),
        ) {
            Box(
                Modifier
                    .offset(x = if (checked) 18.dp else 2.dp, y = 2.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
        AfText(label)
    }
}

/**
 * Text input; [placeholder] is also its content description. [secret] masks the value (API keys; the caller still
 * never echoes a saved secret). [lines] > 1 makes it multiline with that minimum height; [mono] off for prose.
 */
@Composable
fun AfTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    lines: Int = 1,
    mono: Boolean = true,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .heightIn(min = 48.dp).widthIn(min = 48.dp)
            .clip(ControlShape)
            .background(AutoFlowColors.Background)
            .border(AutoFlowShapes.Border, AutoFlowColors.Line, ControlShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) AfText(placeholder, muted = true)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
            enabled = enabled,
            singleLine = lines == 1,
            minLines = lines,
            textStyle = afTextStyle(
                if (enabled) AutoFlowColors.Text else AutoFlowColors.Muted,
                13.sp,
                FontWeight.Normal,
                if (mono) FontFamily.Monospace else LocalUiFontFamily.current,
            ),
            cursorBrush = SolidColor(AutoFlowColors.Cyan),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            // A password keyboard: no suggestions, so the phone's keyboard does not learn the API key.
            keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else KeyboardOptions.Default,
        )
    }
}

/**
 * Section heading (H2) — 16 sp bold, used for top-level feature sections inside a screen or card (D-58).
 * Example: "✍️ Captions", "💧 Watermark"
 */
@Composable
fun AfH2(text: String, modifier: Modifier = Modifier) {
    AfText(text, modifier, size = 16.sp, bold = true)
}

/**
 * Sub-section heading (H3) — 13 sp bold, used for option groups within a section (D-58).
 * Example: "Colour", "Stroke width"
 */
@Composable
fun AfH3(text: String, modifier: Modifier = Modifier) {
    AfText(text, modifier, size = 13.sp, bold = true)
}

/** Panel card with a [title] (DESIGN.md §1: radius 12, 1px border, no shadow). */
@Composable
fun AfCard(title: String, modifier: Modifier = Modifier, selected: Boolean = false, headerAction: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(AutoFlowShapes.Card)
            .background(AutoFlowColors.Panel)
            .border(AutoFlowShapes.Border, if (selected) AutoFlowColors.Purple else AutoFlowColors.Line, AutoFlowShapes.Card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText(title, Modifier.weight(1f), size = 14.sp, bold = true)
            headerAction?.invoke()
        }
        content()
    }
}
