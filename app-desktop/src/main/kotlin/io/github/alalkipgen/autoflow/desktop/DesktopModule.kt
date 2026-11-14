package io.github.alalkipgen.autoflow.desktop

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.platform.AppExit
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
import io.github.alalkipgen.autoflow.ui.jobs.DesktopAiJobRunner
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
            override fun bundledFont(name: String): ByteArray? =
                DesktopModule::class.java.getResourceAsStream("/config/fonts/$name")?.use { it.readBytes() }
        }
    }
    // Export on Windows (PLAN 1C): renders in this process, one at a time; Open plays the result.
    single<RenderQueue> { InProcessRenderQueue(get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
    single<FileShare> { DesktopFileShare() }
    // AI job runner (5A-5): in-process coroutine runner for desktop.
    single { AiJobStore(get(), get<AppDirs>().aiJobs) }
    single<AiJobRunner> { DesktopAiJobRunner(get(), CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
    // D-52: exit hook; on desktop the Window's onCloseRequest handles it, so this is a no-op stub.
    single<AppExit> { AppExit { /* desktop exits via Window.onCloseRequest */ } }
}

private fun readResource(path: String): String =
    DesktopModule::class.java.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }
        ?: error("missing resource $path")

private fun readResourceOrNull(path: String): String? =
    DesktopModule::class.java.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }

private object DesktopModule
