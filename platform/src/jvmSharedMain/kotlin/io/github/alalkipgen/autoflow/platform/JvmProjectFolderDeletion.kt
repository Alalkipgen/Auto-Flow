package io.github.alalkipgen.autoflow.platform

import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.io.ProjectWrites
import io.github.alalkipgen.autoflow.core.recap.RecapMigration
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion.*
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion.Target as DeleteTarget
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID

/** D-76: the only whole-project deletion boundary. Never follows links or deletes backup archives. */
class JvmProjectFolderDeletion(private val fs: JvmFs = JvmFs()) : ProjectFolderDeletion {
    private class Denied(val reason: Refusal) : Exception()
    private data class Scan(val target: DeleteTarget, val paths: List<Path>, val backups: List<Path>)
    private fun deny(reason: Refusal): Nothing = throw Denied(reason)

    private fun inside(root: String, folder: String): Path {
        val home = Paths.get(root).toRealPath()
        val path = Paths.get(folder).toAbsolutePath().normalize()
        if (!path.fileName.toString().endsWith(".autoflow") || Files.isSymbolicLink(path) || path.parent?.toRealPath() != home) deny(Refusal.UNSUPPORTED)
        return home.resolve(path.fileName).also {
            if (!Files.isDirectory(it, NOFOLLOW_LINKS) || it.toRealPath() != it) deny(Refusal.UNSUPPORTED)
        }
    }

    private fun checked(folder: Path): Scan {
        if (!Files.isDirectory(folder, NOFOLLOW_LINKS) || Files.isSymbolicLink(folder)) deny(Refusal.UNSUPPORTED)
        val manifest = folder.resolve(ProjectStore.PROJECT_FILE)
        if (!Files.isRegularFile(manifest, NOFOLLOW_LINKS)) deny(Refusal.CHANGED)
        val project = ProjectStore(fs).open(folder.toString()).getOrNull() ?: deny(Refusal.CHANGED)
        var bytes = 0L
        val paths = mutableListOf<Path>()
        val backups = mutableListOf<Path>()
        Files.walk(folder).use { stream -> stream.forEach { path ->
            if (Files.isSymbolicLink(path)) deny(Refusal.UNSUPPORTED)
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                if (path != folder && path.fileName.toString().endsWith(".autoflow", ignoreCase = true)) deny(Refusal.UNSUPPORTED)
            } else {
                if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) deny(Refusal.UNSUPPORTED)
                val name = path.fileName.toString()
                if (path != manifest && name.equals(ProjectStore.PROJECT_FILE, ignoreCase = true)) deny(Refusal.UNSUPPORTED)
                if (name.endsWith(".zip", ignoreCase = true) || name == RecapMigration.BACKUP_FILE || name == RecapMigration.V2_COPY_FILE) {
                    backups.add(path)
                    return@forEach
                }
                bytes = Math.addExact(bytes, Files.size(path))
            }
            paths.add(path)
        } }
        return Scan(DeleteTarget(folder.toString(), project.id, project.name, bytes, project.updatedAt,
            backups.map { folder.relativize(it).toString() }.sorted()), paths, backups)
    }

    override fun inspect(root: String, folder: String): Result = safely { Result.Ready(checked(inside(root, folder)).target) }

    private fun keepBackups(parked: Path, backups: List<Path>): String? {
        if (backups.isEmpty()) return null
        val moves = mutableListOf<Pair<Path, Path>>()
        try {
            val home = parked.parent.resolve("retained-backups")
            try { Files.createDirectory(home) } catch (_: FileAlreadyExistsException) { /* validated below */ }
            if (!Files.isDirectory(home, NOFOLLOW_LINKS) || home.toRealPath() != home) deny(Refusal.KEEP_BACKUPS_FAILED)
            val keep = Files.createTempDirectory(home, "project-")
            for (backup in backups) {
                val dest = keep.resolve(parked.relativize(backup))
                Files.createDirectories(dest.parent)
                Files.move(backup, dest, ATOMIC_MOVE)
                moves.add(backup to dest)
            }
            return keep.toString()
        } catch (_: Exception) {
            // No project file has been deleted. If a rollback itself fails, the archive remains in retained-backups.
            for ((source, retained) in moves.asReversed()) runCatching { Files.move(retained, source, ATOMIC_MOVE) }
            deny(Refusal.KEEP_BACKUPS_FAILED)
        }
    }

    override fun delete(root: String, target: DeleteTarget, pending: () -> Boolean): Result = safely {
        val path = inside(root, target.folder)
        val end = ProjectWrites.deletion(fs.canonicalPath(path.toString())) ?: deny(Refusal.BUSY)
        try {
            fs.withPathLock(path.toString()) { _, _ ->
                if (pending()) deny(Refusal.BUSY)
                if (checked(path).target != target) deny(Refusal.CHANGED)
                val parked = path.resolveSibling(".deleting-${UUID.randomUUID()}")
                Files.move(path, parked, ATOMIC_MOVE)
                try {
                    val (actual, files, backups) = checked(parked)
                    if (actual.copy(folder = target.folder) != target) deny(Refusal.CHANGED)
                    val kept = keepBackups(parked, backups)
                    val manifest = parked.resolve(ProjectStore.PROJECT_FILE)
                    // Leave the manifest until last so a failure during file removal remains visible and retryable.
                    files.asSequence().filterNot { it == parked || it == manifest }
                        .sortedByDescending { it.nameCount }.forEach { Files.delete(it) }
                    Files.delete(manifest)
                    Files.delete(parked)
                    Result.Deleted(kept)
                } catch (e: Exception) {
                    if (!Files.exists(path, NOFOLLOW_LINKS)) Files.move(parked, path, ATOMIC_MOVE)
                    throw e
                }
            }
        } finally { end() }
    }

    private inline fun safely(block: () -> Result): Result = try { block() }
        catch (e: Denied) { Result.Refused(e.reason) }
        catch (_: Exception) { Result.Refused(Refusal.IO) }
}
