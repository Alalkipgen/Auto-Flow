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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

/** A row this build cannot read (e.g. a mode from a newer Auto Flow, D-78): kept verbatim, shown as "needs a newer app". */
data class NewerProjectEntry(val path: String, val name: String)

@Serializable
private data class ProjectIndexFile(
    val schemaVersion: Int = 1,
    val projects: List<ProjectEntry> = emptyList(),
    val removedPaths: Set<String> = emptySet(),
    val deletedProjects: Map<String, String> = emptyMap(),
    /** Unreadable rows, written back after [projects] unchanged; never decoded from the file directly. */
    @kotlinx.serialization.Transient val newer: List<JsonObject> = emptyList(),
)

/** Same shape as [ProjectIndexFile] on disk, with rows kept as JSON so one unknown row cannot fail the file. */
@Serializable
private data class RawIndexFile(
    val schemaVersion: Int = 1,
    val projects: List<JsonElement> = emptyList(),
    val removedPaths: Set<String> = emptySet(),
    val deletedProjects: Map<String, String> = emptyMap(),
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
    fun isDeleted(dir: String, projectId: String? = null): Boolean {
        val key = canonical(dir)
        val deleted = disk.deletedProjects[key] ?: return false
        return projectId?.let { it == deleted } ?: (key in disk.removedPaths)
    }
    fun markDeleted(dir: String, id: String): PlatformResult<Unit> = change { data ->
        val key = canonical(dir)
        data.withoutNewer(key).copy(projects = data.projects.filterNot { canonical(it.path) == key }, removedPaths = data.removedPaths + key,
            deletedProjects = data.deletedProjects + (key to id))
    }
    val entries: StateFlow<List<ProjectEntry>> = _entries.asStateFlow()

    /** Rows written by a newer Auto Flow (D-78); this build keeps them and cannot open them. */
    fun newerEntries(): List<NewerProjectEntry> = disk.newer.mapNotNull { row ->
        val path = (row["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        NewerProjectEntry(path, (row["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: path)
    }

    private fun ProjectIndexFile.withoutNewer(key: String) =
        copy(newer = newer.filterNot { row -> (row["path"] as? JsonPrimitive)?.content?.let(::canonical) == key })

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
        data.withoutNewer(identity).copy(projects = listOf(entry) + data.projects.filterNot { it in prior }, removedPaths = data.removedPaths - identity,
            deletedProjects = if (opened && data.deletedProjects[identity] == project.id) data.deletedProjects - identity else data.deletedProjects)
    }

    fun remove(dir: String): PlatformResult<Unit> = change { data ->
        val identity = canonical(dir)
        data.withoutNewer(identity).copy(projects = data.projects.filterNot { canonical(it.path) == identity }, removedPaths = data.removedPaths + identity)
    }

    private fun change(update: (ProjectIndexFile) -> ProjectIndexFile): PlatformResult<Unit> = try {
        val dir = path.substringBeforeLast('/', ".").ifEmpty { "/" }
        fs.createDirectories(dir)
        fs.withFolderLock(dir) {
            val previous = read()
            val next = update(previous).let { it.copy(projects = it.projects.sortedByDescending { e -> e.lastOpenedAt }) }
            if (next != previous) {
                fs.writeDurably("$path.tmp", encode(next).encodeToByteArray())
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
        else decode(fs.read(path).decodeToString()).let {
            it.copy(projects = it.projects.sortedByDescending { e -> e.lastOpenedAt })
        }
    } catch (e: Exception) {
        problem = "projects.json could not be read, so the list starts empty (open the folders again): ${e.message ?: e::class.simpleName}"
        ProjectIndexFile()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

        /** Rows are decoded one by one (D-78): an unreadable row is kept verbatim instead of emptying the list. */
        fun decode(text: String): ProjectIndexFile {
            val raw = json.decodeFromString(RawIndexFile.serializer(), text)
            val known = mutableListOf<ProjectEntry>()
            val newer = mutableListOf<JsonObject>()
            for (row in raw.projects) {
                val entry = runCatching { json.decodeFromJsonElement(ProjectEntry.serializer(), row) }.getOrNull()
                when {
                    entry != null -> known += entry
                    row is JsonObject -> newer += row
                    else -> throw IllegalArgumentException("a project row is not an object")
                }
            }
            return ProjectIndexFile(raw.schemaVersion, known, raw.removedPaths, raw.deletedProjects, newer)
        }

        /** Without unreadable rows this is byte-for-byte the former `ProjectIndexFile` encoding. */
        fun encode(data: ProjectIndexFile): String =
            if (data.newer.isEmpty()) json.encodeToString(ProjectIndexFile.serializer(), data)
            else json.encodeToString(RawIndexFile.serializer(), RawIndexFile(data.schemaVersion,
                data.projects.map { json.encodeToJsonElement(ProjectEntry.serializer(), it) } + data.newer,
                data.removedPaths, data.deletedProjects))
    }
}

/** Folder name for a new project: `<name>.autoflow`, without characters Windows rejects. */
fun projectFolderName(name: String): String {
    val safe = name.trim().map { if (it in "<>:\"/\\|?*" || it.code < 32) '_' else it }.joinToString("").trimEnd('.', ' ')
    return (safe.ifEmpty { "project" }) + ".autoflow"
}
