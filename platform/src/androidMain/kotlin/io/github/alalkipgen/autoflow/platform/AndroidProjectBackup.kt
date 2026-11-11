package io.github.alalkipgen.autoflow.platform

import android.annotation.TargetApi
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * [ProjectBackup] for the phone (D-27): the zip goes to `Download/AutoFlow/` through MediaStore, so it needs no
 * storage permission, shows up in the Files app and survives uninstalling Auto Flow. Android 10+ (MediaStore
 * Downloads); older versions report Unsupported. A failed or cancelled backup deletes its half-written file.
 */
class AndroidProjectBackup(
    private val context: Context,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : ProjectBackup {
    override suspend fun backUp(projects: List<BackupProject>, onProgress: (BackupProgress) -> Unit): PlatformResult<BackupResult> =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) PlatformResult.Unsupported("Backing up needs Android 10 or newer")
            else saveToDownloads(projects, onProgress)
        }

    @TargetApi(Build.VERSION_CODES.Q)
    private suspend fun saveToDownloads(projects: List<BackupProject>, onProgress: (BackupProgress) -> Unit): PlatformResult<BackupResult> {
        val plan = ProjectZip.plan(projects)
        if (plan.entries.isEmpty()) return PlatformResult.Failed("There are no project files to back up")
        val free = freeBytes()
        val need = plan.totalBytes + SPARE_BYTES
        if (free != null && free < need) {
            return PlatformResult.Failed("Not enough free space: the backup needs about ${mb(need)}, the phone has ${mb(free)} free")
        }
        val time = now()
        val name = "AutoFlow-backup-${time.format(STAMP)}.zip"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, ZIP_MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            null
        } ?: return PlatformResult.Failed("Cannot create the backup file in Download")
        return try {
            val size = resolver.openOutputStream(uri, "w")?.buffered()?.use { out ->
                ProjectZip.write(plan, out, time.toString(), onProgress)
            } ?: throw java.io.IOException("Cannot write to Download")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val shown = displayName(uri) ?: name
            PlatformResult.Ok(BackupResult("${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$shown", size, plan.projects.size, plan.entries.size, uri.toString()))
        } catch (e: CancellationException) {
            withContext(NonCancellable) { delete(uri) }
            throw e
        } catch (e: Exception) {
            delete(uri)
            PlatformResult.Failed("Backup failed: ${e.message ?: e::class.simpleName}")
        }
    }

    override fun restore(zipPath: String, destDir: String): PlatformResult<List<String>> = try {
        val dest = java.io.File(destDir).also { it.mkdirs() }
        val restored = java.io.FileInputStream(zipPath).use { ProjectZip.restore(it, dest) }
        PlatformResult.Ok(restored)
    } catch (e: Exception) {
        PlatformResult.Failed("Restore failed: ${e.message ?: e::class.simpleName}")
    }

    override fun share(result: BackupResult): PlatformResult<Unit> {
        val uri = Uri.parse(result.ref)
        val send = Intent(Intent.ACTION_SEND)
            .setType(ZIP_MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, result.location.substringAfterLast('/'))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(result.location, uri)
        return try {
            context.startActivity(Intent.createChooser(send, "Share the backup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            PlatformResult.Ok(Unit)
        } catch (_: ActivityNotFoundException) {
            PlatformResult.Unsupported("No app can receive the backup")
        }
    }

    private fun displayName(uri: Uri): String? = try {
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

    @Suppress("DEPRECATION") // the shared storage volume; MediaStore writes land there
    private fun freeBytes(): Long? = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (e: Exception) {
        null
    }

    private fun mb(bytes: Long) = "${(bytes + 999_999) / 1_000_000} MB"

    private companion object {
        const val FOLDER = "AutoFlow"
        const val ZIP_MIME = "application/zip"
        const val SPARE_BYTES = 50L * 1_000_000
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
