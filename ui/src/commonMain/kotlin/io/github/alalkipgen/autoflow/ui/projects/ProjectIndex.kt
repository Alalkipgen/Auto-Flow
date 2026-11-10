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
private data class ProjectIndexFile(val schemaVersion: Int = 1, val projects: List<ProjectEntry> = emptyList())

/**
 * `~/.autoflow/projects.json`: the rebuildable project list (D-20), most recently opened first. An unreadable file
 * gives an empty list + [problem]; nothing is lost, the folders can be opened again. Call from the UI thread.
 */
class ProjectIndex(private val fs: FileSystem, private val path: String, private val clock: Clock = Clock.System) {
    /** Declared before [entries] so the first [read] can set it. */
    var problem: String? = null
        private set

    private val _entries = MutableStateFlow(read())
    val entries: StateFlow<List<ProjectEntry>> = _entries.asStateFlow()

    /** Adds or refreshes the row for [dir]; [opened] also moves it to the top. */
    fun record(dir: String, project: Project, opened: Boolean = true): PlatformResult<Unit> {
        val old = _entries.value.firstOrNull { it.path == dir }
        val entry = ProjectEntry(
            path = dir,
            name = project.name,
            mode = project.mode,
            ratio = project.settings.ratio,
            acceptedShots = project.shots.count { it.acceptedVideo != null },
            totalShots = project.shots.size,
            lastOpenedAt = if (opened || old == null) clock.now() else old.lastOpenedAt,
        )
        return write(listOf(entry) + _entries.value.filter { it.path != dir })
    }

    fun remove(dir: String): PlatformResult<Unit> = write(_entries.value.filter { it.path != dir })

    private fun write(entries: List<ProjectEntry>): PlatformResult<Unit> {
        val sorted = entries.sortedByDescending { it.lastOpenedAt }
        return try {
            val dir = path.substringBeforeLast('/', "")
            if (dir.isNotEmpty()) fs.createDirectories(dir)
            fs.writeDurably("$path.tmp", json.encodeToString(ProjectIndexFile.serializer(), ProjectIndexFile(projects = sorted)).encodeToByteArray())
            fs.atomicMove("$path.tmp", path)
            _entries.value = sorted
            problem = null
            PlatformResult.Ok(Unit)
        } catch (e: Exception) {
            PlatformResult.Failed("Cannot save the project list: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun read(): List<ProjectEntry> = try {
        if (!fs.exists(path)) emptyList()
        else json.decodeFromString(ProjectIndexFile.serializer(), fs.read(path).decodeToString()).projects.sortedByDescending { it.lastOpenedAt }
    } catch (e: Exception) {
        problem = "projects.json could not be read, so the list starts empty (open the folders again): ${e.message ?: e::class.simpleName}"
        emptyList()
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
