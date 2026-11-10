package io.github.alalkipgen.autoflow.android

import android.app.Application
import io.github.alalkipgen.autoflow.BuildConfig
import io.github.alalkipgen.autoflow.android.selftest.SelfTestActivity
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.platform.AndroidClipboard
import io.github.alalkipgen.autoflow.platform.AndroidToolLocator
import io.github.alalkipgen.autoflow.platform.AndroidUrlOpener
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.platform.Clipboard
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.FileSecureStorage
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.SecureStorage
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.platform.UnsupportedFilePicker
import io.github.alalkipgen.autoflow.platform.UrlOpener
import io.github.alalkipgen.autoflow.ui.settings.AppInfo
import io.github.alalkipgen.autoflow.ui.settings.BundledConfig
import java.io.File
import org.koin.dsl.module

/** Android platform bindings that `uiModule` and `aiModule` need (the Android side of `desktopModule`). */
fun androidModule(app: Application) = module {
    single { AppDirs(File(app.filesDir, "autoflow").path) }
    single<FileSystem> { JvmFs() }
    // D-25: the Keystore cipher is a PLAN 1F item; until then saving a key reports Unsupported (fake AI still works).
    single<SecureStorage> { FileSecureStorage(get(), get<AppDirs>().secrets, cipher = null, "Saving an API key on Android arrives with PLAN 1F (D-25)") }
    single<Clipboard> { AndroidClipboard(app) }
    single<UrlOpener> { AndroidUrlOpener(app) }
    single<FilePicker> { UnsupportedFilePicker() }
    single<ToolLocator> { AndroidToolLocator(app.applicationInfo.nativeLibraryDir) }
    single { BundledConfig { app.assets.open("models.json").use { it.readBytes().decodeToString() } } }
    single { AppInfo("${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})") { SelfTestActivity.open(app) } }
}
