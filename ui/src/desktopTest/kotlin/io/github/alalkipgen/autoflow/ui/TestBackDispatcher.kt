package io.github.alalkipgen.autoflow.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * A stand-in for Android's back dispatcher behind `App(backHandler = …)`: each call site registers one handler while it
 * is in the composition (as `BackHandler`), and Back goes to the last registered handler that is enabled.
 */
class TestBackDispatcher {
    private class Entry(var enabled: Boolean, var onBack: () -> Unit)
    private val entries = mutableListOf<Entry>()

    val handler: @Composable (Boolean, () -> Unit) -> Unit = { enabled, onBack ->
        val entry = remember { Entry(enabled, onBack) }
        SideEffect { entry.enabled = enabled; entry.onBack = onBack }
        DisposableEffect(entry) {
            entries += entry
            onDispose { entries -= entry }
        }
    }

    /** True when a Back press would be handled in the app (false = it would leave the app). */
    val enabled: Boolean get() = entries.any { it.enabled }

    fun press() {
        checkNotNull(entries.lastOrNull { it.enabled }) { "Back would leave the app" }.onBack()
    }
}
