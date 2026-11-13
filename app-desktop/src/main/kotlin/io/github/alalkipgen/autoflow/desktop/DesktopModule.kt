package io.github.alalkipgen.autoflow.desktop

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.platform.AwtClipboard
import io.github.alalkipgen.autoflow.platform.Clipboard
import io.github.alalkipgen.autoflow.platform.DesktopToolLocator
import io.github.alalkipgen.autoflow.platform.DesktopUrlOpener
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.SecureStorage
import io.github.alalkipgen.autoflow.platform.SwingFilePicker
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.platform.UrlOpener
import io.github.alalkipgen.autoflow.platform.desktop
import io.github.alalkipgen.autoflow.platform.desktopSecureStorage
import io.github.alalkipgen.autoflow.ui.media.InProcessRenderQueue
import io.github.alalkipgen.autoflow.ui.settings.BundledConfig
import java.awt.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

/** Desktop platform bindings that `uiModule` and `aiModule` need. [window] is the owner for file dialogs. */
fun desktopModule(window: () -> Frame? = { null }) = module {
    single { AppDirs.desktop() }
    single<FileSystem> { JvmFs() }
    single<SecureStorage> { desktopSecureStorage(get(), get()) }
    single<Clipboard> { AwtClipboard() }
    single<UrlOpener> { DesktopUrlOpener() }
    single<FilePicker> { SwingFilePicker(owner = window) }
    single<io.github.alalkipgen.autoflow.platform.VoiceRecorder> { io.github.alalkipgen.autoflow.platform.DesktopVoiceRecorder() }
    single<ToolLocator> { DesktopToolLocator(get()) }
    single<ToolRunner> { DesktopToolRunner() }
    single<BundledConfig> {
        object : BundledConfig {
            override fun modelsJson(): String = readResource("/config/models.json")
            override fun featuresJson(): String? = readResourceOrNull("/config/features.json")
        }
    }
    // Export on Windows (PLAN 1C): renders in this process, one at a time; Open plays the result.
    single<RenderQueue> { InProcessRenderQueue(get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
    single<FileShare> { DesktopFileShare() }
}

private fun readResource(path: String): String =
    DesktopModule::class.java.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }
        ?: error("missing resource $path")

private fun readResourceOrNull(path: String): String? =
    DesktopModule::class.java.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }

private object DesktopModule
