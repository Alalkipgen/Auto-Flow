package io.github.alalkipgen.autoflow.android

import android.annotation.TargetApi
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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

    override suspend fun saveToGallery(path: String, mime: String, displayName: String): PlatformResult<String> =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) PlatformResult.Unsupported("Saving to the gallery needs Android 10 or newer")
            else copyToMovies(File(path), mime, displayName)
        }

    /** MediaStore insert as pending → copy → publish, like the backup in Download (D-27); a failed copy is removed. */
    @TargetApi(Build.VERSION_CODES.Q)
    private suspend fun copyToMovies(source: File, mime: String, displayName: String): PlatformResult<String> {
        if (!source.isFile) return PlatformResult.Failed("The video is gone: ${source.name}")
        val resolver = context.contentResolver
        val folder = "${Environment.DIRECTORY_MOVIES}/$GALLERY_FOLDER"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } catch (e: Exception) {
            null
        } ?: return PlatformResult.Failed("Cannot create the video in $folder")
        return try {
            resolver.openOutputStream(uri, "w")?.use { out -> source.inputStream().use { it.copyTo(out, BUFFER) } }
                ?: throw java.io.IOException("Cannot write to $folder")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            PlatformResult.Ok("$folder/${shownName(uri) ?: displayName}")
        } catch (e: CancellationException) {
            withContext(NonCancellable) { delete(uri) }
            throw e
        } catch (e: Exception) {
            delete(uri)
            PlatformResult.Failed("Saving to the gallery failed: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun shownName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun delete(uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
            // A pending entry that cannot be deleted is removed by the system after a week.
        }
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
        const val GALLERY_FOLDER = "AutoFlow"
        const val BUFFER = 1 shl 16
    }
}
