package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import io.github.alalkipgen.autoflow.ui.theme.GlassColors

/** The desktop dialog layer draws the scrim in [GlassColors.DialogScrim]. */
internal actual fun glassDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false, scrimColor = GlassColors.DialogScrim)

@Composable
internal actual fun GlassDialogScrim() = Unit
