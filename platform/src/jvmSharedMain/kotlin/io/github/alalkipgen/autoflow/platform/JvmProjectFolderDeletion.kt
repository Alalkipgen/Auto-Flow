package io.github.alalkipgen.autoflow.platform

import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.io.ProjectWrites
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion.*
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion.Target as DeleteTarget
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID

/** The only boundary that can delete a project folder. Never follows a link or deletes a protected file. */
class JvmProjectFolderDeletion(private val fs: JvmFs = JvmFs()) : ProjectFolderDeletion {
    private class Denied(val reason: Refusal) : Exception()
    private fun deny(reason: Refusal): Nothing = throw Denied(reason)

    private fun inside(root: String, folder: String): Path {
        val home = Paths.get(root).toRealPath()
        val path = Paths.get(folder).toAbsolutePath().normalize()
        if (!path.fileName.toString().endsWith(".autoflow") || Files.isSymbolicLink(path) || path.parent?.toRealPath() != home) deny(Refusal.UNSUPPORTED)
        return home.resolve(path.fileName).also {
            if (!Files.isDirectory(it, NOFOLLOW_LINKS) || it.toRealPath() != it) deny(Refusal.UNSUPPORTED)
        }
    }

    private fun checked(folder: Path): Pair<DeleteTarget, List<Path>> {
        if (!Files.isDirectory(folder, NOFOLLOW_LINKS) || Files.isSymbolicLink(folder)) deny(Refusal.UNSUPPORTED)
        val manifest = folder.resolve(ProjectStore.PROJECT_FILE)
        if (!Files.isRegularFile(manifest, NOFOLLOW_LINKS)) deny(Refusal.CHANGED)
        val project = ProjectStore(fs).open(folder.toString()).getOrNull() ?: deny(Refusal.CHANGED)
        var bytes = 0L
        val paths = mutableListOf<Path>()
        Files.walk(folder).use { stream -> stream.forEach { path ->
            if (Files.isSymbolicLink(path)) deny(Refusal.UNSUPPORTED)
            if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) deny(Refusal.UNSUPPORTED)
                // AGENTS.md makes artifacts immutable; a project holding any artifact or zip is Remove-only.
                if (path.startsWith(folder.resolve("artifacts")) || path.fileName.toString().endsWith(".zip", ignoreCase = true)) deny(Refusal.PROTECTED_FILES)
                bytes = Math.addExact(bytes, Files.size(path))
            }
            paths.add(path)
        } }
        return DeleteTarget(folder.toString(), project.id, project.name, bytes) to paths
    }

    override fun inspect(root: String, folder: String): Result = safely { Result.Ready(checked(inside(root, folder)).first) }

    override fun delete(root: String, target: DeleteTarget, pending: () -> Boolean): Result = safely {
        val path = inside(root, target.folder)
        val end = ProjectWrites.deletion(fs.canonicalPath(path.toString())) ?: deny(Refusal.BUSY)
        try {
            fs.withPathLock(path.toString()) { _, _ ->
                if (pending()) deny(Refusal.BUSY)
                if (checked(path).first != target) deny(Refusal.CHANGED)
                val parked = path.resolveSibling(".deleting-${UUID.randomUUID()}")
                Files.move(path, parked, ATOMIC_MOVE)
                val files = try {
                    val (actual, files) = checked(parked)
                    if (actual.copy(folder = target.folder) != target) deny(Refusal.CHANGED)
                    files
                } catch (e: Exception) {
                    if (!Files.exists(path, NOFOLLOW_LINKS)) Files.move(parked, path, ATOMIC_MOVE)
                    throw e
                }
                files.sortedByDescending { it.nameCount }.forEach { Files.delete(it) }
                Result.Deleted
            }
        } finally { end() }
    }

    private inline fun safely(block: () -> Result): Result = try { block() }
        catch (e: Denied) { Result.Refused(e.reason) }
        catch (_: Exception) { Result.Refused(Refusal.IO) }
}
