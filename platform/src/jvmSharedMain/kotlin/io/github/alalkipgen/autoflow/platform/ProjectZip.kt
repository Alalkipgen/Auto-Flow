package io.github.alalkipgen.autoflow.platform

import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The backup zip (D-27), shared by desktop tests and Android: `<name>.autoflow/…` per project plus
 * [MANIFEST] at the root. Leaves out what can be rebuilt or is half-written: `.cache/`, `*.part.*` renders and
 * `*.tmp` files. Videos, images and audio are stored without compression (they are compressed already), the rest
 * (JSON, lyrics, subtitles) deflated.
 */
object ProjectZip {
    const val MANIFEST = "autoflow-backup.json"

    data class Entry(val file: File, val path: String, val size: Long)

    data class ProjectPlan(val name: String, val files: Int, val bytes: Long)

    data class Plan(val entries: List<Entry>, val projects: List<ProjectPlan>) {
        val totalBytes: Long get() = entries.sumOf { it.size }
    }

    /** Lists the files to write; a project whose folder is gone is skipped, a repeated folder name gets ` (2)`. */
    fun plan(projects: List<BackupProject>): Plan {
        val used = mutableSetOf<String>()
        val entries = mutableListOf<Entry>()
        val summary = mutableListOf<ProjectPlan>()
        for (p in projects) {
            val root = File(p.dir)
            if (!root.isDirectory) continue
            val name = unique(p.name.trim('/').ifBlank { root.name }, used)
            val files = root.walkTopDown()
                .onEnter { it == root || (it.name != CACHE_DIR && !Files.isSymbolicLink(it.toPath())) }
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && !skipped(it.name) }
                .map { Entry(it, "$name/" + it.relativeTo(root).invariantSeparatorsPath, it.length()) }
                .sortedBy { it.path }
                .toList()
            entries += files
            summary += ProjectPlan(name, files.size, files.sumOf { it.size })
        }
        return Plan(entries, summary)
    }

    /**
     * Writes [plan] to [out] (not closed here) and returns the zip's size in bytes. [createdAt] goes into the manifest.
     * Checks for cancellation between chunks, so a cancelled backup stops within about one megabyte.
     */
    suspend fun write(plan: Plan, out: OutputStream, createdAt: String, onProgress: (BackupProgress) -> Unit = {}): Long {
        val counted = CountingStream(out)
        val zip = ZipOutputStream(counted)
        val total = plan.totalBytes
        var done = 0L
        val buffer = ByteArray(CHUNK)
        onProgress(BackupProgress(0, total))
        for (e in plan.entries) {
            currentCoroutineContext().ensureActive()
            zip.setLevel(if (isCompressed(e.path)) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry(e.path).apply { time = e.file.lastModified() })
            e.file.inputStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    zip.write(buffer, 0, n)
                    done += n
                    onProgress(BackupProgress(minOf(done, total), total))
                    currentCoroutineContext().ensureActive()
                }
            }
            zip.closeEntry()
        }
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(manifest(plan, createdAt).encodeToByteArray())
        zip.closeEntry()
        zip.finish()
        zip.flush()
        return counted.count
    }

    /**
     * Extracts a backup zip from [input] into [destDir] (the projects home folder).
     * Returns the list of project folder names that were restored.
     * Skips the manifest entry and `.cache/` entries.
     * Throws on I/O error; caller is responsible for cleanup on exception.
     */
    fun restore(input: InputStream, destDir: File): List<String> {
        val restored = mutableSetOf<String>()
        val zip = ZipInputStream(input)
        val buffer = ByteArray(CHUNK)
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name != MANIFEST && !entry.name.contains("/$CACHE_DIR/")) {
                val parts = entry.name.split('/', limit = 2)
                val projectFolder = parts[0]
                val relativePath = parts.getOrElse(1) { "" }
                if (projectFolder.isNotBlank() && relativePath.isNotBlank()) {
                    val dest = File(destDir, entry.name)
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { out ->
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                    restored += projectFolder
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        return restored.sorted()
    }

    fun manifest(plan: Plan, createdAt: String): String = buildJsonObject {
        put("format", "autoflow-backup")
        put("version", 1)
        put("createdAt", createdAt)
        putJsonArray("projects") {
            plan.projects.forEach { p -> addJsonObject { put("folder", p.name); put("files", p.files); put("bytes", p.bytes) } }
        }
    }.toString()

    fun skipped(fileName: String): Boolean = fileName.endsWith(".tmp") || PART.containsMatchIn(fileName)

    fun isCompressed(path: String): Boolean = path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in COMPRESSED

    private fun unique(name: String, used: MutableSet<String>): String {
        var candidate = name
        var n = 2
        while (!used.add(candidate.lowercase())) candidate = "$name ($n)".also { n++ }
        return candidate
    }

    private class CountingStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    private const val CACHE_DIR = ".cache"
    private const val CHUNK = 1 shl 20
    private val PART = Regex("""\.part\.[^.]+$""")
    private val COMPRESSED = setOf(
        "mp4", "m4v", "mov", "mkv", "webm", "mp3", "m4a", "aac", "ogg", "opus", "flac",
        "png", "jpg", "jpeg", "webp", "gif", "heic", "zip",
    )
}
