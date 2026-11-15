package io.github.alalkipgen.autoflow.ui

import androidx.compose.runtime.Composable
import io.github.alalkipgen.autoflow.ui.shell.App
import org.koin.compose.KoinIsolatedContext
import org.koin.core.context.GlobalContext

/**
 * Each test starts/stops its own app graph. Bind that exact graph into the composition: Koin's default
 * CompositionLocal wrapper may otherwise retain a root scope that an earlier test has already closed.
 * Production still uses the same App + uiModule; this only isolates test-application lifetimes.
 */
@Composable
fun TestApp(backHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> }) {
    val application = checkNotNull(GlobalContext.getKoinApplicationOrNull())
    KoinIsolatedContext(application) { App(backHandler) }
}
