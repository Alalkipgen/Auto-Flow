package io.github.alalkipgen.autoflow.desktop

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.platform.AwtClipboard
import io.github.alalkipgen.autoflow.platform.Clipboard
import io.github.alalkipgen.autoflow.platform.DesktopToolLocator
import io.github.alalkipgen.autoflow.platform.DesktopUrlOpener
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.SecureStorage
import io.github.alalkipgen.autoflow.platform.SwingFilePicker
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.platform.UrlOpener
import io.github.alalkipgen.autoflow.platform.desktop
import io.github.alalkipgen.autoflow.platform.desktopSecureStorage
import io.github.alalkipgen.autoflow.ui.settings.BundledConfig
import java.awt.Frame
import org.koin.dsl.module

/** Desktop platform bindings that `uiModule` and `aiModule` need. [window] is the owner for file dialogs. */
fun desktopModule(window: () -> Frame? = { null }) = module {
    single { AppDirs.desktop() }
    single<FileSystem> { JvmFs() }
    single<SecureStorage> { desktopSecureStorage(get(), get()) }
    single<Clipboard> { AwtClipboard() }
    single<UrlOpener> { DesktopUrlOpener() }
    single<FilePicker> { SwingFilePicker(owner = window) }
    single<ToolLocator> { DesktopToolLocator(get()) }
    single { BundledConfig { readResource("/config/models.json") } }
}

private fun readResource(path: String): String =
    DesktopModule::class.java.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }
        ?: error("missing resource $path")

private object DesktopModule
