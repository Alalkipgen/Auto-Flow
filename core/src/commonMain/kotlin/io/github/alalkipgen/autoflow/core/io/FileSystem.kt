package io.github.alalkipgen.autoflow.core.io

/**
 * Minimal file access used by the :core stores. Paths are `/`-separated strings. Implementations may throw; the
 * stores catch and map failures to `CoreError.Io`. There is deliberately no delete: artifacts are write-once and
 * the app never deletes them (AGENTS.md §1).
 */
interface FileSystem {
    fun exists(path: String): Boolean

    /** Creates [path] and any missing parents. No-op when it already exists. */
    fun createDirectories(path: String)

    /** Names (not paths) of the direct children of [path]; empty when [path] is not a directory. */
    fun list(path: String): List<String>

    fun read(path: String): ByteArray

    /** Creates or truncates [path], writes [bytes], and forces them to storage (fsync) before returning. */
    fun writeDurably(path: String, bytes: ByteArray)

    /** Renames [from] to [to] atomically, replacing [to] if it exists. */
    fun atomicMove(from: String, to: String)
}

/** Joins a directory and a child name with `/`. */
fun joinPath(dir: String, name: String): String = if (dir.endsWith('/')) dir + name else "$dir/$name"
