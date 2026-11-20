package io.github.alalkipgen.autoflow.platform

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Which files of a project a render reads (F-7, D-96). Everything in the folder except: `exports/`, `inbox/`,
 * `packages/` and every `.cache/` (made again), backups of `project.json`, half-written files, and artifacts that
 * nothing points at (old takes, versions and drafts the project moved on from). An artifact counts as used when its id
 * (`<kind>_<12 hex>`) is in a kept text file (`project.json`, lyrics…) or in a used text artifact (a story, a subtitle
 * version, a storyboard…); its `.meta.json` goes with it.
 */
object RenderPackage {
    const val KIND = "render-package"

    /**
     * The plan for one project folder [dir] as `<folder>/…`. An [extras] file replaces a project file at its path, and
     * extras in `fonts/` replace the project's whole `fonts/` folder (a package made from an unpacked package).
     */
    fun plan(dir: File, folder: String, extras: List<PackageFile> = emptyList()): ProjectZip.Plan {
        val name = folder.trim('/').ifBlank { dir.name }
        val extraPaths = extras.map { it.path }.toSet()
        val newFonts = extras.any { it.path.startsWith("$FONTS/") }
        val files = dir.walkTopDown()
            .onEnter { it == dir || (!Files.isSymbolicLink(it.toPath()) && !skippedDir(it.relativeTo(dir).invariantSeparatorsPath)) }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && !ProjectZip.skipped(it.name) }
            .map { it.relativeTo(dir).invariantSeparatorsPath to it }
            .filter { (rel, _) -> rel !in SKIPPED_FILES && rel !in extraPaths && !(newFonts && rel.startsWith("$FONTS/")) }
            .toList()
        val (artifacts, others) = files.partition { (rel, _) -> rel.startsWith("$ARTIFACTS/") && rel.count { it == '/' } == 1 }
        val byId = artifacts.groupBy { (rel, _) -> rel.substringAfter('/').substringBefore('.') }
        val used = used(others.map { it.second }, byId)
        val kept = others + artifacts.filter { (rel, _) -> rel.substringAfter('/').substringBefore('.') in used }
        val entries = kept.map { (rel, f) -> ProjectZip.Entry(f, "$name/$rel", f.length()) } +
            extras.map { ProjectZip.Entry(null, "$name/${it.path}", it.bytes.size.toLong(), it.bytes) }
        val sorted = entries.sortedBy { it.path }
        return ProjectZip.Plan(sorted, listOf(ProjectZip.ProjectPlan(name, sorted.size, sorted.sumOf { it.size })), KIND)
    }

    /** Artifact ids reachable from [roots] through text files (breadth first; a file is read once). */
    private fun used(roots: List<File>, byId: Map<String, List<Pair<String, File>>>): Set<String> {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(roots.filter(::isText))
        while (queue.isNotEmpty()) {
            val text = runCatching { queue.removeFirst().readText() }.getOrNull() ?: continue
            for (m in ID.findAll(text)) {
                val id = m.value
                if (!seen.add(id)) continue
                byId[id]?.forEach { (rel, f) -> if (!rel.endsWith(META) && isText(f)) queue.addLast(f) }
            }
        }
        return seen
    }

    private fun isText(f: File) = f.extension.lowercase() in TEXT && f.length() <= MAX_SCAN

    private fun skippedDir(rel: String): Boolean = rel.substringAfterLast('/') == ".cache" || rel in SKIPPED_DIRS

    /** Fonts a package carries (the font the render used), read before the Settings fonts ([PackageFile]). */
    const val FONTS = "fonts"
    private const val ARTIFACTS = "artifacts"
    private const val META = ".meta.json"
    private const val MAX_SCAN = 64L shl 20
    private val SKIPPED_DIRS = setOf("exports", "inbox", "packages")
    private val SKIPPED_FILES = setOf("project.json.bak", "project.v1.bak.json")
    private val TEXT = setOf("json", "txt", "lrc", "srt", "vtt", "ass")
    private val ID = Regex("""\b(?:an|sb|sp|pr|im|vd|au|tx|rg|rs|st)_[0-9a-f]{12}\b""")
}

/** [RenderPackages] on the JVM (desktop and Android): the zip is written next to [PackedProject.zip] as `.part` first. */
class JvmRenderPackages(private val now: () -> Instant = Instant::now) : RenderPackages {
    override suspend fun pack(projectDir: String, folder: String, extras: List<PackageFile>, zip: String,
        onProgress: (BackupProgress) -> Unit): PlatformResult<PackedProject> = withContext(Dispatchers.IO) {
        val dir = File(projectDir)
        if (!File(dir, "project.json").isFile) return@withContext PlatformResult.Failed("This folder has no project.json")
        val plan = RenderPackage.plan(dir, folder, extras)
        val target = File(zip)
        val part = File(target.parentFile, "${target.name}.part")
        val free = target.parentFile?.let { it.mkdirs(); it.usableSpace } ?: 0L
        if (free in 1 until plan.totalBytes + SPARE_BYTES) {
            return@withContext PlatformResult.Failed("Not enough free space: the package needs about ${mb(plan.totalBytes + SPARE_BYTES)}, ${mb(free)} free")
        }
        try {
            val size = part.outputStream().buffered().use { ProjectZip.write(plan, it, now().toString(), onProgress) }
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            PlatformResult.Ok(PackedProject(target.path, size, plan.entries.size, plan.totalBytes))
        } catch (e: CancellationException) {
            withContext(NonCancellable) { part.delete() }
            throw e
        } catch (e: Exception) {
            part.delete()
            PlatformResult.Failed("The render package was not written: ${e.message ?: e::class.simpleName}")
        }
    }

    override suspend fun unpack(zipPath: String, destDir: String): PlatformResult<List<RestoredProject>> = withContext(Dispatchers.IO) {
        try {
            PlatformResult.Ok(FileInputStream(zipPath).buffered().use { ProjectZip.restore(it, File(destDir)) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PlatformResult.Failed("This package cannot be opened: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun mb(bytes: Long) = "${(bytes + 999_999) / 1_000_000} MB"

    private companion object { const val SPARE_BYTES = 50L * 1_000_000 }
}
