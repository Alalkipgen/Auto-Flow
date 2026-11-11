package io.github.alalkipgen.autoflow.platform

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Android SAF file picker (D-08). The activity calls [onResult] from its `ActivityResultCallback`; the caller
 * registers a launcher with [contract] = `Intent.ACTION_GET_CONTENT` and passes the URI list back here.
 *
 * Usage: register in MainActivity, inject the same instance into Koin.
 */
class AndroidFilePicker(private val context: Context) : FilePicker {
    private val pending = AtomicReference<((List<Uri>) -> Unit)?>(null)

    /** Call this from an `ActivityResultCallback<List<Uri>>` registered in the activity. */
    fun onResult(uris: List<Uri>) { pending.getAndSet(null)?.invoke(uris) }

    override suspend fun pickFolder(title: String, startDir: String?): PlatformResult<String?> =
        PlatformResult.Unsupported("Folder picker not available on Android — projects are in the app's folder")

    override suspend fun pickFiles(
        title: String,
        extensions: Set<String>,
        multiple: Boolean,
        startDir: String?,
    ): PlatformResult<List<String>> {
        // If another pick is in progress (should not happen), return Unsupported gracefully.
        if (pending.get() != null) return PlatformResult.Unsupported("Another file pick is already running")
        val uris = suspendCancellableCoroutine { cont ->
            pending.set { uris -> if (cont.isActive) cont.resume(uris) }
            cont.invokeOnCancellation { pending.set(null) }
            // The activity listens for this via launchPickIntent()
            launchPickIntent(title, extensions, multiple)
        }
        if (uris.isEmpty()) return PlatformResult.Ok(emptyList())
        // Copy each URI to a cache temp file so the rest of the app can read it as a plain path
        val results = mutableListOf<String>()
        val cache = File(context.cacheDir, "filepicker").also { it.mkdirs() }
        for (uri in uris) {
            val ext = extensionFromUri(uri, context.contentResolver).takeIf { it.isNotEmpty() } ?: "bin"
            if (extensions.isNotEmpty() && ext !in extensions.map { it.lowercase() }) continue
            val tmp = File(cache, "${System.nanoTime()}.$ext")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                results += tmp.path
            } catch (e: Exception) {
                tmp.delete()
            }
        }
        return if (results.isEmpty() && uris.isNotEmpty())
            PlatformResult.Failed("Could not read the selected file")
        else PlatformResult.Ok(results)
    }

    /** Override this in the activity: launch the system file picker with an `ActivityResultLauncher`. */
    var launchPickIntent: (title: String, extensions: Set<String>, multiple: Boolean) -> Unit = { _, _, _ ->
        pending.getAndSet(null)?.invoke(emptyList()) // fallback: cancel immediately
    }

    private fun extensionFromUri(uri: Uri, cr: ContentResolver): String {
        // Try to get extension from the display name in the content provider
        cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: ""
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext.isNotEmpty()) return ext
            }
        }
        // Fall back to MIME type
        return when (cr.getType(uri)) {
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/jpg" -> "jpg"
            else -> ""
        }
    }
}
