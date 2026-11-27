package io.github.alalkipgen.autoflow.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.ui.di.uiModule
import io.github.alalkipgen.autoflow.ui.shell.App
import java.awt.Frame
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // F-7 (D-96): `--render` / `--help` run on the command line, without the window.
    if (HeadlessRender.wanted(args)) {
        System.setProperty("java.awt.headless", "true")
        exitProcess(HeadlessRender.run(args.toList()))
    }
    var mainWindow: Frame? = null
    startKoin { modules(desktopModule { mainWindow }, uiModule, aiModule) }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Auto Flow",
            state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
        ) {
            mainWindow = window
            App()
        }
    }
    stopKoin() // closes the AI HTTP client
}
