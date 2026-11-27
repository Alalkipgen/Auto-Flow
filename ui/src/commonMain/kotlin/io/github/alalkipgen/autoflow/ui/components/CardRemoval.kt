package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

private data class RemovalIntent(val confirm: () -> Unit, val confirming: Boolean = false)

/**
 * No visible destructive control until the card's context action (long-press, right-click or the accessibility action)
 * is invoked. Capture the target before showing it. Two [GlassDialog] steps (Library follow-up README §3): (1) the item
 * — its [leading] (44 dp tall), [name] and Muted [caption]; no header when [name] is null — then one Danger menu row
 * ([action], `$key-remove-menu`) and Cancel; (2) a Failed `trash` tile with [title], [explanation] as a fact line, and
 * Cancel + Delete (`$key-remove-confirm`). Cancel (`$key-remove-cancel`), Back and a tap outside close the dialog.
 */
@Composable
internal fun CardRemoval(
    key: String,
    action: UiText,
    title: String,
    explanation: UiText,
    prepareRemoval: () -> (() -> Unit)?,
    onTap: (() -> Unit)? = null,
    accessibilityLabel: String? = null,
    name: String? = null,
    caption: String? = null,
    leading: (@Composable () -> Unit)? = null,
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
    card(Modifier.pointerInput(key, onTap == null) {
        detectTapGestures(onTap = if (onTap == null) null else { { tap?.invoke() } },
            onLongPress = { openMenu() })
    }.onSecondaryClick { openMenu() }.semantics {
        customActions = listOf(CustomAccessibilityAction(accessibilityLabel ?: delete, ::openMenu))
    })
    pending?.let { intent ->
        GlassDialog(onDismissRequest = { pending = null }) {
            key(intent.confirming) {
                val cancel = GlassDialogAction(My.breakSyllables(tr(UiText.CANCEL_KEEP)), { pending = null }, tag = "$key-remove-cancel")
                if (intent.confirming) GlassDialogSurface(AfIcons.Fill.Trash, AutoFlowColors.Failed, My.breakSyllables(title)) {
                    GlassFact(AfIcons.Regular.Info, My.breakSyllables(tr(explanation)))
                    GlassDialogButtons(cancel, GlassDialogAction(My.breakSyllables(tr(UiText.DELETE)), { pending = null; intent.confirm() },
                        ButtonKind.Danger, AfIcons.Regular.Trash, tag = "$key-remove-confirm"))
                } else GlassDialogSurface(name?.let(My::breakSyllables), subtitle = caption?.let(My::breakSyllables), leading = leading) {
                    GlassMenuRow(AfIcons.Fill.Trash, AutoFlowColors.Failed, My.breakSyllables(delete), { pending = intent.copy(confirming = true) },
                        Modifier.testTag("$key-remove-menu"), titleColour = AutoFlowColors.Failed)
                    GlassDialogButtons(cancel)
                }
            }
        }
    }
}
