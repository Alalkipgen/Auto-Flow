package io.github.alalkipgen.autoflow.platform

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

internal object JvmInboxFileDeletion : InboxFileDeletion {
    private data class Stamp(val key: Any?, val created: FileTime, val modified: FileTime, val bytes: Long)
    private data class Entry(val file: Path, val directoryKey: Any, val stamp: Stamp)

    override fun prepareInboxDeletion(inbox: String, name: String): (() -> Boolean)? {
        if (name.isBlank() || name.startsWith('.') || name.endsWith(".part", ignoreCase = true) ||
            '/' in name || '\\' in name) return null
        val captured = inspect(inbox, name) ?: return null
        return { delete(inbox, name, captured) }
    }

    private fun inspect(inbox: String, name: String): Entry? {
        return try {
            val requested = Paths.get(inbox).toAbsolutePath()
            val base = requested.fileName.toString()
            if (base != "inbox" && base != ".shared-inbox") return null
            val parent = requested.parent
            if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) return null
            val folder = parent.toRealPath().resolve(base)
            val directory = Files.readAttributes(folder, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!directory.isDirectory || folder.toRealPath() != folder) return null
            val file = folder.resolve(name)
            if (file.parent != folder || file.fileName.toString() != name) return null
            val attrs = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attrs.isRegularFile || file.toRealPath() != file) null
            else Entry(file, directory.identity(), attrs.stamp())
        } catch (_: Exception) {
            null
        }
    }

    private fun delete(inbox: String, name: String, captured: Entry): Boolean = try {
        if (inspect(inbox, name) != captured) false else {
            Files.newDirectoryStream(captured.file.parent).use { stream ->
                if (stream is SecureDirectoryStream<Path>) {
                    // Use the open directory, not a fresh path traversal, when the filesystem supports it.
                    val dir = stream.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()
                    val leaf = captured.file.fileName
                    val attrs = stream.getFileAttributeView(leaf, BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
                        .readAttributes()
                    if (dir.identity() != captured.directoryKey || !attrs.isRegularFile || attrs.stamp() != captured.stamp)
                        return@use false
                    stream.deleteFile(leaf)
                } else {
                    // Windows does not provide a secure directory stream. Recheck before unlinking one leaf.
                    if (inspect(inbox, name) != captured) return@use false
                    Files.delete(captured.file)
                }
                true
            }
        }
    } catch (_: Exception) {
        false
    }

    private fun BasicFileAttributes.identity(): Any = fileKey() ?: creationTime()
    private fun BasicFileAttributes.stamp() = Stamp(fileKey(), creationTime(), lastModifiedTime(), size())
}