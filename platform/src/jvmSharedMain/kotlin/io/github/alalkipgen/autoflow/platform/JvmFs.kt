package io.github.alalkipgen.autoflow.platform

import io.github.alalkipgen.autoflow.core.io.FileSystem
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The :core [FileSystem] port on java.nio, shared by desktop and Android (D-18). Paths may use `/` or the native
 * separator. Writes are forced to storage (`FileChannel.force`) and renames use `ATOMIC_MOVE`; there is no fallback
 * to a non-atomic move. Failures are thrown as `IOException`s, which the stores map to `CoreError.Io`.
 *
 * On Windows a rename over a file that a virus scanner or indexer has just opened can fail for a moment
 * (`AccessDeniedException`), so [atomicMove] retries that case a few times before giving up.
 */
class JvmFs internal constructor(
    private val move: (Path, Path) -> Unit,
    private val sleep: (Long) -> Unit,
    private val moveAttempts: Int,
) : FileSystem {
    constructor() : this(::nioAtomicMove, Thread::sleep, MOVE_ATTEMPTS)

    override fun exists(path: String): Boolean = Files.exists(p(path))

    override fun createDirectories(path: String) {
        Files.createDirectories(p(path))
    }

    override fun list(path: String): List<String> {
        val dir = p(path)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.newDirectoryStream(dir).use { entries -> entries.map { it.fileName.toString() } }.sorted()
    }

    override fun read(path: String): ByteArray = Files.readAllBytes(p(path))

    override fun writeDurably(path: String, bytes: ByteArray) {
        val opts = arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        FileChannel.open(p(path), *opts).use { ch ->
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) ch.write(buf)
            ch.force(true)
        }
    }

    override fun size(path: String): Long = Files.size(p(path))

    /** Streams with a 1 MiB buffer, then fsyncs (a movie of several GB never sits in memory). */
    override fun copyDurably(from: String, to: String) {
        val opts = arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        FileChannel.open(p(from), StandardOpenOption.READ).use { src ->
            FileChannel.open(p(to), *opts).use { dst ->
                val buf = ByteBuffer.allocate(1 shl 20)
                while (src.read(buf) >= 0) {
                    buf.flip()
                    while (buf.hasRemaining()) dst.write(buf)
                    buf.clear()
                }
                dst.force(true)
            }
        }
    }

    override fun atomicMove(from: String, to: String) {
        val src = p(from)
        val dst = p(to)
        var attempt = 1
        while (true) {
            try {
                move(src, dst)
                break
            } catch (e: AccessDeniedException) {
                if (attempt >= moveAttempts) throw e
                sleep(RETRY_DELAY_MS * attempt)
                attempt++
            }
        }
        syncDirectory(dst.toAbsolutePath().parent)
    }

    private fun p(path: String): Path = Paths.get(path)

    private companion object {
        const val MOVE_ATTEMPTS = 5
        const val RETRY_DELAY_MS = 20L
    }
}

private fun nioAtomicMove(src: Path, dst: Path) {
    Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

/** Makes the rename itself durable where the OS allows opening a directory (Linux, Android); a no-op on Windows. */
private fun syncDirectory(dir: Path?) {
    if (dir == null) return
    try {
        FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: Exception) {
        // Best effort: Windows cannot open a directory as a channel, and the data file itself is already forced.
    }
}
