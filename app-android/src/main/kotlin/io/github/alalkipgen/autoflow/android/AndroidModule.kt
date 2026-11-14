package io.github.alalkipgen.autoflow.android

import android.app.Application
import io.github.alalkipgen.autoflow.BuildConfig
import io.github.alalkipgen.autoflow.android.ai.AiWorkerRegistry
import io.github.alalkipgen.autoflow.android.ai.WorkManagerAiJobRunner
import io.github.alalkipgen.autoflow.android.render.ProcessToolRunner
import io.github.alalkipgen.autoflow.android.render.WorkManagerRenderQueue
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.android.selftest.SelfTestActivity
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.android.render.Battery
import io.github.alalkipgen.autoflow.media.render.BatteryReader
import io.github.alalkipgen.autoflow.media.render.BatteryReading
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.AndroidClipboard
import io.github.alalkipgen.autoflow.platform.AndroidProjectBackup
import io.github.alalkipgen.autoflow.platform.AndroidToolLocator
import io.github.alalkipgen.autoflow.platform.AndroidUrlOpener
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.platform.Clipboard
import io.github.alalkipgen.autoflow.platform.AndroidFilePicker
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.ProjectBackup
import io.github.alalkipgen.autoflow.platform.AppExit
import io.github.alalkipgen.autoflow.platform.SecureStorage
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.platform.UrlOpener
import io.github.alalkipgen.autoflow.platform.androidSecureStorage
import io.github.alalkipgen.autoflow.ui.settings.AppInfo
import io.github.alalkipgen.autoflow.ui.settings.BundledConfig
import java.io.File
import org.koin.dsl.module

/** Android platform bindings that `uiModule` and `aiModule` need (the Android side of `desktopModule`). */
fun androidModule(app: Application) = module {
    // D-27: projects under the external files dir (no permission; the Backup card covers uninstalls).
    single { AppDirs(File(app.filesDir, "autoflow").path, File(app.getExternalFilesDir(null) ?: app.filesDir, "projects").path) }
    single<FileSystem> { JvmFs() }
    // D-25: secrets.bin sealed with an AES-256-GCM key in the Android Keystore.
    single<SecureStorage> { androidSecureStorage(get(), get()) }
    single<Clipboard> { AndroidClipboard(app) }
    single<UrlOpener> { AndroidUrlOpener(app) }
    // SAF file picker; MainActivity registers the launcher and calls onResult().
    single<FilePicker> { AndroidFilePicker(app) }
    single { get<FilePicker>() as AndroidFilePicker }
    single { io.github.alalkipgen.autoflow.platform.AndroidVoiceRecorder(app) }
    single<io.github.alalkipgen.autoflow.platform.VoiceRecorder> { get<io.github.alalkipgen.autoflow.platform.AndroidVoiceRecorder>() }
    single<ToolLocator> { AndroidToolLocator(app.applicationInfo.nativeLibraryDir) }
    // Settings → Tools version check of the bundled ffmpeg (PLAN 1D).
    single<ToolRunner> { ProcessToolRunner() }
    // PLAN 1F / D-26: renders run one at a time in a WorkManager foreground worker.
    single<RenderQueue> { WorkManagerRenderQueue(app) }
    // 5A-5 / D-51: AI jobs (TTS, transcribe, Publish Kit) run on a second unique WorkManager chain "ai".
    single<AiJobRunner> {
        WorkManagerAiJobRunner.of(app).also { runner ->
            // Register the processor so AiWorker can call it after a process recreation.
            // The processor is set once uiModule + aiModule are both started (see AutoFlowApp).
        }
    }
    single<ProjectBackup> { AndroidProjectBackup(app) }
    // Export screen (Q-09 c): open / share the mp4, battery warning before a render.
    single<FileShare> { AndroidFileShare(app) }
    single<BatteryReader> { BatteryReader { Battery.read(app).let { BatteryReading(it.percent, it.charging) } } }
    single<BundledConfig> {
        object : BundledConfig {
            override fun modelsJson(): String = app.assets.open("models.json").use { it.readBytes().decodeToString() }
            override fun featuresJson(): String? = runCatching {
                app.assets.open("features.json").use { it.readBytes().decodeToString() }
            }.getOrNull()
            // Built-in Burmese fonts (D-49): decoded from fonts/*.ttf.b64 into assets fonts/ at build time.
            override fun bundledFont(name: String): ByteArray? = runCatching {
                app.assets.open("fonts/$name").use { it.readBytes() }
            }.getOrNull()
        }
    }
    single { AppInfo("${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})") { SelfTestActivity.open(app) } }
    // D-52: exit hook so the UI can call finishAffinity() from the dialog.
    single<AppExit> { AppExit { /* set by MainActivity */ } }
}
