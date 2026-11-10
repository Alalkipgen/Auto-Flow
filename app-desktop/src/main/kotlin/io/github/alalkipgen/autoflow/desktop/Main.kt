package io.github.alalkipgen.autoflow.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.alalkipgen.autoflow.ui.di.uiModule
import io.github.alalkipgen.autoflow.ui.shell.AppShell
import org.koin.core.context.startKoin

fun main() {
    startKoin { modules(uiModule) }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Auto Flow",
            state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
        ) {
            AppShell()
        }
    }
}
