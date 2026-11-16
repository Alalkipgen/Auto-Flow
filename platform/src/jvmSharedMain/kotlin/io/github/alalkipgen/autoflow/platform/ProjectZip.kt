package io.github.alalkipgen.autoflow.platform

import io.github.alalkipgen.autoflow.core.model.CoreJson
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Bounds for [ProjectZip.restore]; [maxBytes] null = the destination's free space less 50 MB. */
data class RestoreLimits(val maxEntries: Int = 200_000, val maxNameLength: Int = 1024, val maxBytes: Long? = null)

/** A restore that was refused before anything reached the projects folder (or that stopped part-way, see message). */
class RestoreException(message: String) : IOException(message)

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
     * Restores a backup zip from [input] into [destDir] (the projects home folder) without ever touching what is
     * already there (audit F01):
     *
     * 1. every entry name is checked before anything is written: no absolute, `..`, `.`, empty, backslash or `:`
     *    (drive letter / NTFS stream) segments, no duplicates (case-insensitive), no hidden top-level folders;
     * 2. files are extracted into a fresh staging folder `<destDir>/.restore-…` (never `.autoflow`, so the
     *    Dashboard does not list it) with `CREATE_NEW`, so nothing is overwritten and nothing follows a link, and
     *    every target must canonically stay inside the staging folder;
     * 3. the archive must be complete: the [MANIFEST] (written last by [write]) must be there, list exactly the
     *    folders found, with the same number of files each, and every folder needs a readable `project.json`;
     * 4. only then each project folder is moved into [destDir]. A folder name that already exists (a project, a
     *    file or a link) is never replaced: the copy gets `Name (restored).autoflow`, `Name (restored 2).autoflow`…
     *
     * Any failure or cancellation deletes only the staging folder; existing projects stay byte-identical. The
     * expansion is bounded by [limits] (entry count, name length, total bytes — by default the free space).
     */
    suspend fun restore(input: InputStream, destDir: File, limits: RestoreLimits = RestoreLimits()): List<RestoredProject> {
        if (!destDir.isDirectory && !destDir.mkdirs()) throw RestoreException("Cannot create the projects folder")
        val staging = Files.createTempDirectory(destDir.toPath(), STAGING_PREFIX)
        try {
            val root = staging.toRealPath()
            val maxBytes = limits.maxBytes ?: destDir.usableSpace.let { if (it > 0) it - SPARE_BYTES else Long.MAX_VALUE }
            val seen = mutableSetOf<String>()
            val files = mutableMapOf<String, Int>()
            var manifest: String? = null
            var entries = 0
            var total = 0L
            val buffer = ByteArray(CHUNK)
            val zip = ZipInputStream(input)
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                if (++entries > limits.maxEntries) throw RestoreException("The backup has more than ${limits.maxEntries} entries")
                val segments = safeSegments(entry.name, entry.isDirectory, limits.maxNameLength)
                if (!seen.add(segments.joinToString("/").lowercase())) throw RestoreException("The backup lists ${entry.name} twice")
                when {
                    segments.size == 1 && entry.name == MANIFEST -> manifest = zip.readBounded(MANIFEST_MAX)
                    entry.isDirectory || segments.size == 1 || CACHE_DIR in segments.drop(1) -> Unit
                    else -> {
                        val target = segments.fold(staging) { dir, s -> dir.resolve(s) }
                        Files.createDirectories(target.parent)
                        if (!target.parent.toRealPath().startsWith(root)) throw RestoreException("${entry.name} points outside the restore folder")
                        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > maxBytes) throw RestoreException("The backup does not fit: it needs more than ${maxBytes / 1_000_000} MB")
                                out.write(buffer, 0, n)
                                currentCoroutineContext().ensureActive()
                            }
                        }
                        files[segments[0]] = (files[segments[0]] ?: 0) + 1
                    }
                }
                zip.closeEntry()
            }
            checkComplete(manifest ?: throw RestoreException("This zip is not a complete Auto Flow backup (no $MANIFEST)"), files)
            for (folder in files.keys) checkProject(staging.resolve(folder), folder)
            return promote(staging, destDir.toPath(), files.keys.sorted())
        } finally {
            deleteTree(staging)
        }
    }

    /** The checked `/`-separated segments of a zip entry name, or [RestoreException] when it is unsafe anywhere. */
    fun safeSegments(name: String, directory: Boolean = false, maxLength: Int = MAX_NAME): List<String> {
        val bad = { why: String -> RestoreException("Unsafe name in the backup ($why): $name") }
        if (name.isEmpty() || name.length > maxLength) throw bad("length")
        if ('\\' in name || ':' in name || '\u0000' in name) throw bad("character")
        if (name.startsWith("/")) throw bad("absolute")
        val segments = (if (directory) name.removeSuffix("/") else name).split('/')
        for (s in segments) if (s.isEmpty() || s == "." || s == "..") throw bad("path")
        if (segments.size > 1 && segments[0].startsWith(".")) throw bad("hidden folder")
        return segments
    }

    private fun checkComplete(manifest: String, files: Map<String, Int>) {
        val listed = try {
            val json = CoreJson.parseToJsonElement(manifest).jsonObject
            if (json["format"]?.jsonPrimitive?.contentOrNull != "autoflow-backup") throw RestoreException("This zip is not an Auto Flow backup")
            json["projects"]!!.jsonArray.associate { p ->
                p.jsonObject["folder"]!!.jsonPrimitive.content to p.jsonObject["files"]!!.jsonPrimitive.int
            }
        } catch (e: RestoreException) {
            throw e
        } catch (e: Exception) {
            throw RestoreException("The backup's $MANIFEST is unreadable")
        }
        if (listed != files) {
            val missing = listed.filter { (k, v) -> files[k] != v }.keys + (files.keys - listed.keys)
            throw RestoreException("The backup is incomplete or damaged (${missing.sorted().joinToString(", ")})")
        }
    }

    private fun checkProject(dir: Path, folder: String) {
        val json = dir.resolve("project.json")
        val ok = try {
            Files.isRegularFile(json, LinkOption.NOFOLLOW_LINKS) && Files.size(json) <= PROJECT_MAX &&
                // API 26; unlike File.readText(), this reader REPORTs malformed UTF-8.
                CoreJson.parseToJsonElement(Files.newBufferedReader(json, Charsets.UTF_8).use { it.readText() }).jsonObject.let { o ->
                    listOf("id", "name", "mode").all { o[it]?.jsonPrimitive?.isString == true }
                }
        } catch (e: Exception) {
            false
        }
        if (!ok) throw RestoreException("$folder in the backup has no readable project.json")
    }

    private fun promote(staging: Path, dest: Path, folders: List<String>): List<RestoredProject> {
        val done = mutableListOf<RestoredProject>()
        for (folder in folders) {
            var n = 1
            while (true) {
                val name = restoredName(folder, n++)
                val target = dest.resolve(name)
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue
                try {
                    Files.move(staging.resolve(folder), target)
                } catch (_: FileAlreadyExistsException) {
                    continue
                } catch (e: IOException) {
                    val before = if (done.isEmpty()) "" else " (already restored: ${done.joinToString { it.folder }})"
                    throw RestoreException("Cannot move $folder into the projects folder$before: ${e.message}")
                }
                done += RestoredProject(name, folder)
                break
            }
        }
        return done
    }

    /** `Rain.autoflow` → `Rain (restored).autoflow`, `Rain (restored 2).autoflow`…; [n] 1 is the name itself. */
    fun restoredName(folder: String, n: Int): String {
        if (n == 1) return folder
        val tag = if (n == 2) " (restored)" else " (restored ${n - 1})"
        return if (folder.endsWith(SUFFIX)) folder.removeSuffix(SUFFIX) + tag + SUFFIX else folder + tag
    }

    private fun ZipInputStream.readBounded(max: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) throw RestoreException("The backup's $MANIFEST is too large")
        }
        return out.toString(Charsets.UTF_8)
    }

    /** Deletes the staging tree without following links (Kotlin's deleteRecursively would follow a linked folder). */
    private fun deleteTree(dir: Path) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
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
    private const val SUFFIX = ".autoflow"
    private const val STAGING_PREFIX = ".restore-"
    private const val MAX_NAME = 1024
    private const val MANIFEST_MAX = 1 shl 20
    private const val PROJECT_MAX = 64L shl 20
    private const val SPARE_BYTES = 50L * 1_000_000
    private const val CHUNK = 1 shl 20
    private val PART = Regex("""\.part\.[^.]+$""")
    private val COMPRESSED = setOf(
        "mp4", "m4v", "mov", "mkv", "webm", "mp3", "m4a", "aac", "ogg", "opus", "flac",
        "png", "jpg", "jpeg", "webp", "gif", "heic", "zip",
    )
}
