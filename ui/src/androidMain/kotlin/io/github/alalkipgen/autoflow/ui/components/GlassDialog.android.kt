package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.alalkipgen.autoflow.ui.theme.GlassColors

internal actual fun glassDialogProperties(): DialogProperties = DialogProperties(usePlatformDefaultWidth = false)

/** Android dims behind its dialog window: the dim amount = the scrim's alpha (.66). */
@Composable
internal actual fun GlassDialogScrim() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { window?.setDimAmount(GlassColors.DialogScrim.alpha) }
}
