package io.github.alalkipgen.autoflow.ui.projects

import io.github.alalkipgen.autoflow.core.CoreError
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.recap.QuickRecap
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapMigration
import io.github.alalkipgen.autoflow.core.recap.RecapStyle
import io.github.alalkipgen.autoflow.core.recap.RecapScript
import io.github.alalkipgen.autoflow.core.recap.RecapStory
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** The project the pipeline screens work on: its folder and the last saved `project.json`. */
data class OpenProject(val dir: String, val project: Project)

/** Holder shared by all screens; set by the Dashboard. */
class CurrentProject {
    private val _state = MutableStateFlow<OpenProject?>(null)
    val state: StateFlow<OpenProject?> = _state.asStateFlow()

    fun set(open: OpenProject?) {
        _state.value = open
    }
}

/** [missing] = the folder or its `project.json` is gone; the row offers "Remove from list". */
data class DashboardRow(val entry: ProjectEntry, val missing: Boolean)

data class DashboardUiState(val rows: List<DashboardRow>, val message: String? = null)

/**
 * Dashboard logic (DESIGN.md §3): project list, New Music Video, Open folder. With a [projectsHome] (Android, D-27)
 * new projects always go there, nobody picks a folder, and project folders found there join the list on [refresh]
 * (after a lost `projects.json` or a restored backup).
 */
class DashboardModel(
    private val fs: FileSystem,
    private val store: ProjectStore,
    private val index: ProjectIndex,
    private val settings: SettingsStore,
    private val picker: FilePicker,
    private val current: CurrentProject,
    val projectsHome: String? = null,
) {
    private val _state = MutableStateFlow(snapshot(index.problem))
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    /** Where new projects go by default: the projects home, else the last folder used. */
    val lastParent: String? get() = projectsHome ?: settings.state.value.projectsDir

    /** Creates `<parent>/<name>.autoflow` with the default ratio / clip length from Settings, then opens it. */
    fun createMusicVideo(parent: String, name: String): Boolean = create(parent, name, Mode.MUSIC_VIDEO)

    /**
     * New Movie Recap (docs/specs/recap.md §1): like [createMusicVideo], but only with the rights tick, which is
     * kept in `recap.rightsConfirmed`.
     */
    /**
     * Creates a Movie Recap project pre-wired for the Quick Recap wizard (5B-11, D-47).
     * Uses [lastParent] so the user is not prompted for a folder.
     * Returns false with a message if no parent is known.
     */
    fun createQuickRecap(targetMs: Long, style: RecapStyle): Boolean {
        val parent = lastParent ?: return fail("Choose a folder for new projects in Settings first")
        val name = "Quick Recap ${kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000}"
        return create(parent, name, Mode.MOVIE_RECAP) { project ->
            project.copy(
                recap = Recap(
                    rightsConfirmed = true,
                    quick = QuickRecap(targetMs = targetMs, style = style),
                )
            )
        }
    }

    fun createMovieRecap(parent: String, name: String, rightsConfirmed: Boolean): Boolean {
        if (!rightsConfirmed) return fail(RIGHTS_REQUIRED)
        return create(parent, name, Mode.MOVIE_RECAP) { it.copy(recap = Recap(rightsConfirmed = true)) }
    }

    private fun create(parent: String, name: String, mode: Mode, init: (Project) -> Project = { it }): Boolean {
        if (name.isBlank()) return fail("Enter a project name")
        if (parent.isBlank()) return fail("Choose where to save the project")
        val dir = joinPath(parent.trimEnd('/'), projectFolderName(name))
        val defaults = settings.state.value
        val created = store.create(dir, name, mode, ProjectSettings(ratio = defaults.defaultRatio, clipMs = defaults.defaultClipMs))
        val project = when (created) {
            is CoreResult.Ok -> init(created.value).let { p -> if (p == created.value) created else store.save(dir, p) }
            is CoreResult.Err -> created
        }
        return when (project) {
            is CoreResult.Ok -> {
                if (projectsHome == null) settings.update { it.copy(projectsDir = parent) }
                opened(dir, project.value)
            }
            is CoreResult.Err -> fail(project.error.toText())
        }
    }

    suspend fun pickParent(): String? = when (val r = picker.pickFolder("Where to save the project", lastParent)) {
        is PlatformResult.Ok -> r.value
        is PlatformResult.Failed -> null.also { fail(r.reason) }
        is PlatformResult.Unsupported -> null.also { fail(r.reason) }
    }

    /** "Open folder": any project folder, also ones not in the list yet. */
    suspend fun openFolder(): Boolean = when (val r = picker.pickFolder("Open a project folder (.autoflow)", lastParent)) {
        is PlatformResult.Ok -> r.value?.let(::open) ?: false
        is PlatformResult.Failed -> fail(r.reason)
        is PlatformResult.Unsupported -> fail(r.reason)
    }

    fun open(dir: String): Boolean {
        // Run recap v1 → v2 migration before decoding the project (spec §2, 5A-2 UI).
        migrateRecapIfNeeded(dir)
        return when (val r = store.open(dir)) {
            is CoreResult.Ok -> opened(dir, r.value)
            is CoreResult.Err -> fail(r.error.toText())
        }
    }

    /**
     * If [dir]/project.json is a v1 recap project, migrates it to v2 in-place (backup → migrate → save).
     * Silent on any error so a migration failure never blocks opening the project.
     */
    private fun migrateRecapIfNeeded(dir: String) {
        try {
            val path = joinPath(dir, "project.json")
            if (!fs.exists(path)) return
            val jsonObj = try {
                Json.parseToJsonElement(fs.read(path).decodeToString()).jsonObject
            } catch (_: Exception) { return }
            // Only migrate Movie Recap projects; music video projects have recap=null so skip them.
            val recapValue = jsonObj["recap"]
            if (recapValue == null || recapValue.toString() == "null") return
            if (!RecapMigration.needsMigration(jsonObj)) return
            RecapMigration.backup(fs, dir)
            val artifacts = ArtifactStore(fs, dir)
            val burmeseTtsModel: String? = null // TTS model lookup wired in 5B-5
            val migrated = RecapMigration.migrate(
                project = jsonObj,
                readScript = { id ->
                    try {
                        val bytes = fs.read(joinPath(joinPath(dir, ArtifactStore.ARTIFACTS_DIR), "${id.value}.json"))
                        CoreJson.decodeFromString(RecapScript.serializer(), bytes.decodeToString())
                    } catch (_: Exception) { null }
                },
                writeStory = { story ->
                    val bytes = CoreJson.encodeToString(RecapStory.serializer(), story).encodeToByteArray()
                    (artifacts.put(bytes, ArtifactKind.RECAP_SCRIPT, "json") as? CoreResult.Ok)?.value?.id
                        ?: throw IllegalStateException("Failed to write story artifact during migration")
                },
                burmeseTtsModel = burmeseTtsModel,
            )
            val tmp = "$path.tmp"
            fs.writeDurably(tmp, migrated.toString().encodeToByteArray())
            fs.atomicMove(tmp, path)
        } catch (_: Exception) {
            // Migration failure must never prevent opening the project
        }
    }

    fun remove(dir: String) {
        val r = index.remove(dir)
        done((r as? PlatformResult.Failed)?.reason)
    }

    /** Re-checks which folders still exist (e.g. after a USB drive comes back) and lists new ones in [projectsHome]. */
    fun refresh() = done(adoptHomeProjects())

    /** Adds the `<home>/<name>.autoflow` folders with a readable `project.json` that the list does not have yet. */
    private fun adoptHomeProjects(): String? {
        val home = projectsHome ?: return null
        val known = index.entries.value.map { it.path }.toSet()
        val names = try {
            fs.list(home)
        } catch (e: Exception) {
            return null
        }
        for (name in names) {
            val dir = joinPath(home, name)
            if (!name.endsWith(".autoflow") || dir in known) continue
            val project = (store.open(dir) as? CoreResult.Ok)?.value ?: continue
            (index.record(dir, project, opened = false) as? PlatformResult.Failed)?.let { return it.reason }
        }
        return null
    }

    private fun opened(dir: String, project: Project): Boolean {
        current.set(OpenProject(dir, project))
        done((index.record(dir, project) as? PlatformResult.Failed)?.reason)
        return true
    }

    private fun fail(message: String): Boolean {
        done(message)
        return false
    }

    private fun done(message: String?) {
        _state.value = snapshot(message)
    }

    private fun snapshot(message: String?) = DashboardUiState(
        rows = index.entries.value.map { DashboardRow(it, missing = !exists(joinPath(it.path, ProjectStore.PROJECT_FILE))) },
        message = message,
    )

    private fun exists(path: String): Boolean = try {
        fs.exists(path)
    } catch (e: Exception) {
        false
    }

    companion object {
        const val RIGHTS_REQUIRED = "Tick the rights box first: use only footage you own or have permission to use."
    }

    private fun CoreError.toText(): String = when (this) {
        is CoreError.NotFound -> "No Auto Flow project here (project.json is missing)"
        is CoreError.AlreadyExists -> "A folder with this name already exists and is not empty: $path"
        is CoreError.Corrupt -> message + if (backupAvailable) ". A backup (project.json.bak) exists." else ""
        else -> message
    }
}
