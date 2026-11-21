package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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

private data class RemovalIntent(val confirm: () -> Unit, val confirming: Boolean = false)

/** No visible destructive control until the card's context action is invoked. Capture the target before showing it. */
@Composable
internal fun CardRemoval(
    key: String,
    action: UiText,
    title: String,
    explanation: UiText,
    prepareRemoval: () -> (() -> Unit)?,
    onTap: (() -> Unit)? = null,
    accessibilityLabel: String? = null,
    card: @Composable (Modifier) -> Unit,
) {
    var pending by remember(key) { mutableStateOf<RemovalIntent?>(null) }
    val delete = tr(action)
    val tap by rememberUpdatedState(onTap)
    val prepare by rememberUpdatedState(prepareRemoval)
    fun openMenu(): Boolean {
        prepare()?.let { pending = RemovalIntent(it) }
        return true
    }
    card(Modifier.pointerInput(key) {
        detectTapGestures(onTap = if (onTap == null) null else { { tap?.invoke() } },
            onLongPress = { openMenu() })
    }.onSecondaryClick { openMenu() }.semantics {
        customActions = listOf(CustomAccessibilityAction(accessibilityLabel ?: delete, ::openMenu))
    })
    pending?.let { intent ->
        Dialog(onDismissRequest = { pending = null }) {
            AfCard(My.breakSyllables(if (intent.confirming) title else delete),
                Modifier.widthIn(max = 440.dp).heightIn(max = 440.dp).paneScroll(rememberScrollState())) {
                if (intent.confirming) AfText(My.breakSyllables(tr(explanation)), muted = true)
                WrapRow {
                    AfButton(My.breakSyllables(if (intent.confirming) tr(UiText.DELETE) else delete), {
                        if (intent.confirming) { pending = null; intent.confirm() }
                        else pending = intent.copy(confirming = true)
                    }, Modifier.testTag(if (intent.confirming) "$key-remove-confirm" else "$key-remove-menu"),
                        kind = ButtonKind.Danger)
                    AfButton(My.breakSyllables(tr(UiText.CANCEL)), { pending = null }, Modifier.testTag("$key-remove-cancel"))
                }
            }
        }
    }
}