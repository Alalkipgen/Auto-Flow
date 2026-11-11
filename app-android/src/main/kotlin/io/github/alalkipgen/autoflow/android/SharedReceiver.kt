package io.github.alalkipgen.autoflow.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import io.github.alalkipgen.autoflow.ui.inbox.SharedInbox
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share-sheet import → Inbox (PLAN 1F): a video or image shared to Auto Flow (ACTION_SEND / SEND_MULTIPLE,
 * AndroidManifest) is copied from the other app's content URI into [SharedInbox.holdDir] as `.name.part`, renamed
 * when complete, then [SharedInbox.received] moves it into the open project's inbox. The copy runs in a process-wide
 * scope so leaving the screen does not cut it short.
 */
object SharedReceiver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** True when [intent] is a share this receiver took (the activity then clears it). */
    fun handle(context: Context, intent: Intent?, inbox: SharedInbox): Boolean {
        val uris = uris(intent)
        if (uris.isEmpty()) return false
        val app = context.applicationContext
        scope.launch {
            val failed = withContext(Dispatchers.IO) { uris.count { !copy(app, it, inbox.holdDir) } }
            inbox.received(failed)
        }
        return true
    }

    @Suppress("DEPRECATION") // getParcelableExtra(String, Class) is API 33+
    private fun uris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val fromExtra: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> return emptyList()
        }
        val fromClip = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
        return (fromExtra + fromClip).distinct()
    }

    private fun copy(context: Context, uri: Uri, holdDir: String): Boolean = try {
        val dir = File(holdDir).apply { mkdirs() }
        val name = SharedInbox.safeName(withExtension(context, uri, displayName(context, uri) ?: "shared-${System.currentTimeMillis()}"))
        val part = File(dir, ".$name.${System.nanoTime()}.part")
        val copied = context.contentResolver.openInputStream(uri)?.use { input -> part.outputStream().use { input.copyTo(it, BUFFER) } }
        if (copied == null) {
            part.delete()
            false
        } else {
            val taken = dir.list()?.toSet().orEmpty()
            val target = File(dir, SharedInbox.freeName(name, taken))
            part.renameTo(target).also { if (!it) part.delete() }
        }
    } catch (e: Exception) {
        false
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Inbox sorts files by extension (mp4/mov/png/jpg): add one from the MIME type when the name has none. */
    private fun withExtension(context: Context, uri: Uri, name: String): String {
        if (name.substringAfterLast('.', "").length in 2..4) return name
        val ext = context.contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return if (ext.isNullOrEmpty()) name else "$name.$ext"
    }

    private const val BUFFER = 1 shl 16
}
