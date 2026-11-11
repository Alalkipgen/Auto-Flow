package io.github.alalkipgen.autoflow.platform

/** One project folder to back up: [dir] on disk, [name] = its folder name inside the zip (`Rain.autoflow`). */
data class BackupProject(val dir: String, val name: String)

/** Bytes of project files read so far, of [totalBytes]. */
data class BackupProgress(val doneBytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes <= 0) 1f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/**
 * A finished backup: [location] for the user (`Download/AutoFlow/AutoFlow-backup-….zip`), the zip's size, what it
 * holds, and [ref], which only [ProjectBackup.share] reads (an Android content URI).
 */
data class BackupResult(val location: String, val zipBytes: Long, val projects: Int, val files: Int, val ref: String)

/**
 * Settings → "Back up all projects" (D-25, D-27): one zip with every project folder, saved where uninstalling the app
 * does not delete it, then offered to the share sheet. Android binds it; desktop has none (projects live in folders
 * the user picked). Cancelling the coroutine deletes the half-written zip.
 */
interface ProjectBackup {
    suspend fun backUp(projects: List<BackupProject>, onProgress: (BackupProgress) -> Unit): PlatformResult<BackupResult>

    /** Opens the share sheet for [result] (Drive, Gmail, Telegram, Quick Share…). */
    fun share(result: BackupResult): PlatformResult<Unit>

    /**
     * Restores a backup zip at [zipPath] into [destDir]. Returns the list of restored project folder names.
     * [zipPath] is a plain file path (already copied from the content URI by the file picker).
     */
    fun restore(zipPath: String, destDir: String): PlatformResult<List<String>>
}
