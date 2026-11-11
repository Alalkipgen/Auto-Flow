package io.github.alalkipgen.autoflow.platform

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import android.content.ClipboardManager as AndroidClipboardManager

// PLAN 1F "Android bindings": the first Android implementations of the :platform interfaces. The file picker (SAF
// document trees, D-08) and Keystore-backed SecureStorage (D-25) are the remaining 1F items.

/** [Clipboard] on the Android clipboard service. */
class AndroidClipboard(private val context: Context) : Clipboard {
    override fun copyText(text: String): PlatformResult<Unit> {
        val cm = context.getSystemService(AndroidClipboardManager::class.java)
            ?: return PlatformResult.Unsupported("No clipboard service on this device")
        return try {
            cm.setPrimaryClip(ClipData.newPlainText("Auto Flow", text))
            PlatformResult.Ok(Unit)
        } catch (e: RuntimeException) {
            PlatformResult.Failed("Cannot copy: ${e::class.simpleName}")
        }
    }
}

/** [UrlOpener] via `ACTION_VIEW`, so the user's browser (and its Google sign-in for Flow) opens the page. */
class AndroidUrlOpener(private val context: Context) : UrlOpener {
    override fun open(url: String): PlatformResult<Unit> {
        if (!isWebUrl(url)) return PlatformResult.Failed("Only http(s) links can be opened")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            PlatformResult.Ok(Unit)
        } catch (_: ActivityNotFoundException) {
            PlatformResult.Unsupported("No browser is installed")
        }
    }
}

/**
 * [ToolLocator] for the APK: ffmpeg is bundled as `libffmpeg.so` and extracted to [nativeLibraryDir] — the only
 * place Android 10+ lets an app execute a binary (D-23). There is no VLC on Android (preview uses Media3, D-22).
 */
class AndroidToolLocator(private val nativeLibraryDir: String) : ToolLocator {
    override val bundled: Boolean get() = true

    override fun findFfmpeg(): String? = File(nativeLibraryDir, FFMPEG).takeIf { it.isFile }?.path?.toPortablePath()

    /** The bundled ffprobe next to ffmpeg, or null. */
    fun findFfprobe(): String? = File(nativeLibraryDir, FFPROBE).takeIf { it.isFile }?.path?.toPortablePath()

    override fun findVlc(): String? = null

    override fun isVlcDir(dir: String): Boolean = false

    companion object {
        const val FFMPEG = "libffmpeg.so"
        const val FFPROBE = "libffprobe.so"
    }
}

/** Until the SAF picker lands (PLAN 1F), picking reports Unsupported instead of failing silently. */
class UnsupportedFilePicker(private val reason: String = "File picking on Android arrives with PLAN 1F (D-08)") : FilePicker {
    override suspend fun pickFolder(title: String, startDir: String?): PlatformResult<String?> = PlatformResult.Unsupported(reason)

    override suspend fun pickFiles(title: String, extensions: Set<String>, multiple: Boolean, startDir: String?): PlatformResult<List<String>> =
        PlatformResult.Unsupported(reason)
}
