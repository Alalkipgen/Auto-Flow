package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.alalkipgen.autoflow.ui.i18n.*

/** No visible destructive control until the card's context action is invoked. */
@Composable
internal fun CardRemoval(
    key: String,
    action: UiText,
    title: String,
    explanation: UiText,
    remove: () -> Unit,
    onTap: (() -> Unit)? = null,
    card: @Composable (Modifier) -> Unit,
) {
    var step by remember(key) { mutableIntStateOf(0) }
    val delete = tr(action)
    val tap by rememberUpdatedState(onTap)
    card(Modifier.pointerInput(key) {
        detectTapGestures(onTap = if (onTap == null) null else { { tap?.invoke() } },
            onLongPress = { step = 1 })
    }.pointerInput(key) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    step = 1
                }
            }
        }
    }.semantics {
        customActions = listOf(CustomAccessibilityAction(delete) { step = 1; true })
    })
    if (step != 0) Dialog(onDismissRequest = { step = 0 }) {
        AfCard(if (step == 1) My.breakSyllables(delete) else My.breakSyllables(title),
            Modifier.widthIn(max = 440.dp).heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            if (step == 2) AfText(My.breakSyllables(tr(explanation)), muted = true)
            WrapRow {
                AfButton(My.breakSyllables(if (step == 1) delete else tr(UiText.DELETE)),
                    { if (step == 1) step = 2 else { step = 0; remove() } },
                    Modifier.testTag(if (step == 1) "$key-remove-menu" else "$key-remove-confirm"),
                    kind = ButtonKind.Danger)
                AfButton(My.breakSyllables(tr(UiText.CANCEL)), { step = 0 }, Modifier.testTag("$key-remove-cancel"))
            }
        }
    }
}