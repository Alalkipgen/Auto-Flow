package io.github.alalkipgen.autoflow.ui.projects

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.platform.PlatformResult
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One Dashboard row (D-20). Copied from `project.json` whenever the project is opened or saved. */
@Serializable
data class ProjectEntry(
    val path: String,
    val name: String,
    val mode: Mode,
    val ratio: Ratio,
    val acceptedShots: Int,
    val totalShots: Int,
    val lastOpenedAt: Instant,
)

@Serializable
private data class ProjectIndexFile(
    val schemaVersion: Int = 1,
    val projects: List<ProjectEntry> = emptyList(),
    val removedPaths: Set<String> = emptySet(),
)

/**
 * `~/.autoflow/projects.json`: the rebuildable project list (D-20), most recently opened first. An unreadable file
 * gives an empty list + [problem]; nothing is lost, the folders can be opened again. The index lock serializes
 * UI and background saves; it is independent of each project folder's lock.
 */
class ProjectIndex(private val fs: FileSystem, private val path: String, private val clock: Clock = Clock.System) {
    /** Declared before [entries] so the first [read] can set it. */
    var problem: String? = null
        private set

    private var disk = read()
    private val _entries = MutableStateFlow(disk.projects)
    fun canonical(dir: String): String = runCatching { fs.canonicalPath(dir) }.getOrDefault(dir.trimEnd('/'))
    fun isRemoved(dir: String): Boolean = canonical(dir) in disk.removedPaths
    fun removedPaths(): Set<String> = disk.removedPaths
    val entries: StateFlow<List<ProjectEntry>> = _entries.asStateFlow()

    /** Adds or refreshes the row for [dir]; [opened] also moves it to the top. */
    fun record(dir: String, project: Project, opened: Boolean = true): PlatformResult<Unit> = change { data ->
        val identity = canonical(dir)
        if (!opened && identity in data.removedPaths) return@change data
        val prior = data.projects.filter { canonical(it.path) == identity }
        val old = prior.firstOrNull()
        val entry = ProjectEntry(
            path = old?.path ?: dir,
            name = project.name,
            mode = project.mode,
            ratio = project.recap?.visual?.outputRatio ?: project.settings.ratio,
            acceptedShots = project.shots.count { it.acceptedVideo != null },
            totalShots = project.shots.size,
            lastOpenedAt = if (opened || old == null) clock.now() else old.lastOpenedAt,
        )
        data.copy(projects = listOf(entry) + data.projects.filterNot { it in prior }, removedPaths = data.removedPaths - identity)
    }

    fun remove(dir: String): PlatformResult<Unit> = change { data ->
        val identity = canonical(dir)
        data.copy(projects = data.projects.filterNot { canonical(it.path) == identity }, removedPaths = data.removedPaths + identity)
    }

    private fun change(update: (ProjectIndexFile) -> ProjectIndexFile): PlatformResult<Unit> = try {
        val dir = path.substringBeforeLast('/', ".").ifEmpty { "/" }
        fs.createDirectories(dir)
        fs.withFolderLock(dir) {
            val previous = read()
            val next = update(previous).let { it.copy(projects = it.projects.sortedByDescending { e -> e.lastOpenedAt }) }
            if (next != previous) {
                fs.writeDurably("$path.tmp", json.encodeToString(ProjectIndexFile.serializer(), next).encodeToByteArray())
                fs.atomicMove("$path.tmp", path)
                problem = null
            }
            disk = next
            _entries.value = next.projects
            PlatformResult.Ok(Unit)
        }
    } catch (e: Exception) {
        PlatformResult.Failed("Cannot save the project list: ${e.message ?: e::class.simpleName}")
    }

    private fun read(): ProjectIndexFile = try {
        if (!fs.exists(path)) ProjectIndexFile()
        else json.decodeFromString(ProjectIndexFile.serializer(), fs.read(path).decodeToString()).let {
            it.copy(projects = it.projects.sortedByDescending { e -> e.lastOpenedAt })
        }
    } catch (e: Exception) {
        problem = "projects.json could not be read, so the list starts empty (open the folders again): ${e.message ?: e::class.simpleName}"
        ProjectIndexFile()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }
}

/** Folder name for a new project: `<name>.autoflow`, without characters Windows rejects. */
fun projectFolderName(name: String): String {
    val safe = name.trim().map { if (it in "<>:\"/\\|?*" || it.code < 32) '_' else it }.joinToString("").trimEnd('.', ' ')
    return (safe.ifEmpty { "project" }) + ".autoflow"
}
