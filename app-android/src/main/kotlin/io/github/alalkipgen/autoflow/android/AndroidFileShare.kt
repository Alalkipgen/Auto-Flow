package io.github.alalkipgen.autoflow.android

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import java.io.File

/**
 * [FileShare] over a [FileProvider] (`<package>.files`, res/xml/file_paths.xml: the projects folder under the
 * external files dir), so other apps get a read-only content URI to the export and never a file path.
 */
class AndroidFileShare(private val context: Context) : FileShare {
    override fun open(path: String, mime: String): PlatformResult<Unit> {
        val uri = uri(path) ?: return PlatformResult.Failed("The file is not in the projects folder: $path")
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(view, "No app can play this video")
    }

    override fun share(path: String, mime: String, title: String): PlatformResult<Unit> {
        val uri = uri(path) ?: return PlatformResult.Failed("The file is not in the projects folder: $path")
        val send = Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, File(path).name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(File(path).name, uri)
        val chooser = Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(chooser, "No app can receive the video")
    }

    private fun uri(path: String): Uri? = try {
        FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", File(path))
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun start(intent: Intent, none: String): PlatformResult<Unit> = try {
        context.startActivity(intent)
        PlatformResult.Ok(Unit)
    } catch (_: ActivityNotFoundException) {
        PlatformResult.Unsupported(none)
    }

    companion object {
        const val AUTHORITY_SUFFIX = ".files"
    }
}
