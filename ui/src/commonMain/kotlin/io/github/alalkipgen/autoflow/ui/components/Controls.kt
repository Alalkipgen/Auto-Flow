package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes

/*
 * Small foundation-only controls in the DESIGN.md §1 style. `:ui` has no Material dependency, so every screen builds
 * on these.
 */

private val ControlShape = RoundedCornerShape(8.dp)

/** Body text; [muted] for secondary lines, [mono] for prompts, paths and JSON. */
@Composable
fun AfText(
    text: String,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    size: TextUnit = 13.sp,
    bold: Boolean = false,
    mono: Boolean = false,
    color: Color? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color ?: if (muted) AutoFlowColors.Muted else AutoFlowColors.Text,
            fontSize = size,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        ),
    )
}

enum class ButtonKind { Primary, Secondary, Flow, Danger }

/** Purple = primary action, cyan = Flow / external, amber outline = destructive (DESIGN.md §1). */
@Composable
fun AfButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    enabled: Boolean = true,
) {
    val (fill, line, ink) = when (kind) {
        ButtonKind.Primary -> Triple(AutoFlowColors.Purple, AutoFlowColors.Purple, Color.White)
        ButtonKind.Flow -> Triple(AutoFlowColors.Cyan, AutoFlowColors.Cyan, AutoFlowColors.Background)
        ButtonKind.Secondary -> Triple(AutoFlowColors.Card, AutoFlowColors.Line, AutoFlowColors.Text)
        ButtonKind.Danger -> Triple(AutoFlowColors.Card, AutoFlowColors.Amber, AutoFlowColors.Amber)
    }
    Box(
        modifier
            .clip(ControlShape)
            .background(if (enabled) fill else AutoFlowColors.Panel)
            .border(AutoFlowShapes.Border, if (enabled) line else AutoFlowColors.Line, ControlShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        AfText(text, color = if (enabled) ink else AutoFlowColors.Muted, bold = kind == ButtonKind.Primary)
    }
}

/** Single-choice chip (ratio, clip length, filters). */
@Composable
fun AfChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) AutoFlowColors.Purple.copy(alpha = 0.25f) else AutoFlowColors.Card)
            .border(AutoFlowShapes.Border, if (selected) AutoFlowColors.Purple else AutoFlowColors.Line, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { stateDescription = if (selected) "selected" else "not selected" }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        AfText(text, color = if (selected) AutoFlowColors.Text else AutoFlowColors.Muted)
    }
}

/** On/off switch with a [label]; the whole row toggles. */
@Composable
fun AfSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
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
            textStyle = TextStyle(
                color = if (enabled) AutoFlowColors.Text else AutoFlowColors.Muted,
                fontSize = 13.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            ),
            cursorBrush = SolidColor(AutoFlowColors.Cyan),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            // A password keyboard: no suggestions, so the phone's keyboard does not learn the API key.
            keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else KeyboardOptions.Default,
        )
    }
}

/** Panel card with a [title] (DESIGN.md §1: radius 12, 1px border, no shadow). */
@Composable
fun AfCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(AutoFlowShapes.Card)
            .background(AutoFlowColors.Panel)
            .border(AutoFlowShapes.Border, AutoFlowColors.Line, AutoFlowShapes.Card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AfText(title, size = 14.sp, bold = true)
        content()
    }
}
