package io.github.alalkipgen.autoflow.ui.projects

import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.core.CoreError
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.ai.config.ModelConfig
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.recap.QuickRecap
import io.github.alalkipgen.autoflow.core.recap.MigrationException
import io.github.alalkipgen.autoflow.core.recap.RecapMigration
import io.github.alalkipgen.autoflow.core.recap.RecapStyle
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion.Refusal
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion.Result
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

data class DashboardUiState(val rows: List<DashboardRow>, val message: String? = null, val notice: UiNotice? = null)

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
    private val modelConfig: ModelConfig? = null,
    private val deletion: ProjectFolderDeletion? = null,
    private val ai: AiJobRunner? = null,
    private val renders: RenderQueue? = null,
    private val foregroundBusy: (String) -> Boolean = { false },
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
        val parent = lastParent ?: return fail(UiText.PROJECT_PARENT_REQUIRED)
        val name = freeName(parent, "Quick Recap")
        return create(parent, name, Mode.MOVIE_RECAP) { project ->
            val quickJson = CoreJson.encodeToJsonElement(QuickRecap.serializer(), QuickRecap(targetMs = targetMs, style = style))
            project.copy(
                recap = RecapV2(
                    rightsConfirmed = true,
                    // Audit F15: the chosen length is the recap's own target (what the Scenes header, the fit check
                    // and the target chips read), not only a copy inside the Quick Recap state.
                    targetMs = targetMs,
                    quick = quickJson,
                )
            )
        }
    }

    /** [base], or "[base] 2", "[base] 3"… when that project folder is already taken. */
    private fun freeName(parent: String, base: String): String {
        var name = base
        var n = 2
        while (fs.exists(joinPath(parent.trimEnd('/'), projectFolderName(name)))) name = "$base ${n++}"
        return name
    }

    fun createMovieRecap(parent: String, name: String, rightsConfirmed: Boolean): Boolean {
        if (!rightsConfirmed) return fail(UiText.PROJECT_RIGHTS_REQUIRED)
        return create(parent, name, Mode.MOVIE_RECAP) { it.copy(recap = RecapV2(rightsConfirmed = true)) }
    }

    private fun create(parent: String, name: String, mode: Mode, init: (Project) -> Project = { it }): Boolean {
        if (name.isBlank()) return fail(UiText.PROJECT_NAME_REQUIRED)
        if (parent.isBlank()) return fail(UiText.PROJECT_DEST_REQUIRED)
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

    suspend fun pickParent(): String? = when (val r = picker.pickFolder(UiText.PROJECT_SAVE_FOLDER.text(UiLanguage.fromCode(settings.state.value.uiLanguage)), lastParent)) {
        is PlatformResult.Ok -> r.value
        is PlatformResult.Failed -> null.also { fail(r.reason) }
        is PlatformResult.Unsupported -> null.also { fail(r.reason) }
    }

    /** "Open folder": any project folder, also ones not in the list yet. */
    suspend fun openFolder(): Boolean = when (val r = picker.pickFolder(UiText.PROJECT_OPEN_FOLDER.text(UiLanguage.fromCode(settings.state.value.uiLanguage)), lastParent)) {
        is PlatformResult.Ok -> r.value?.let(::open) ?: false
        is PlatformResult.Failed -> fail(r.reason)
        is PlatformResult.Unsupported -> fail(r.reason)
    }

    /**
     * Opens [dir]. A v1 project is migrated first ([RecapMigration.migrateFolder]: checked, backed up, then written);
     * one that cannot be migrated without losing work stays untouched and is not opened (audit F02). When
     * `project.json` is missing or unreadable, a [RecapMigration.BACKUP_FILE] is validated (and migrated when v1)
     * before it replaces anything; the damaged file is kept (audit F13).
     */
    fun open(dir: String): Boolean {
        val burmeseTtsModel = modelConfig?.tts?.chainFor("my")?.firstOrNull()
        try {
            // Under the folder lock (audit F04): no save of another window can land between the check and the write.
            fs.withFolderLock(dir) { RecapMigration.migrateFolder(fs, dir, burmeseTtsModel) }
        } catch (e: MigrationException) {
            return fail("Cannot update this project to the new format: ${e.message}. The project was not changed.")
        } catch (e: Exception) {
            return fail("Cannot update this project to the new format (${e.message ?: e::class.simpleName}). The project was not changed.")
        }
        val firstTry = store.open(dir)
        if (firstTry is CoreResult.Ok) return opened(dir, firstTry.value)
        val recovery = try {
            fs.withFolderLock(dir) { RecapMigration.recoverFromBackup(fs, dir, burmeseTtsModel) }
        } catch (e: Exception) {
            RecapMigration.Recovery.Failed("Recovering from ${RecapMigration.BACKUP_FILE} failed (${e.message ?: e::class.simpleName})")
        }
        val problem = (firstTry as CoreResult.Err).error.toText()
        return when (recovery) {
            is RecapMigration.Recovery.Recovered -> when (val reopened = store.open(dir)) {
                is CoreResult.Ok -> opened(dir, reopened.value).also {
                    done("Opened from ${RecapMigration.BACKUP_FILE}" + (recovery.kept?.let { "; the damaged project.json was kept as $it" } ?: ""))
                }
                is CoreResult.Err -> fail("$problem. Recovering from ${RecapMigration.BACKUP_FILE} did not help: ${reopened.error.toText()}")
            }
            is RecapMigration.Recovery.Failed -> fail("$problem. ${recovery.reason}; nothing was changed.")
            RecapMigration.Recovery.NoBackup -> fail(problem)
        }
    }

    /** Android has no arbitrary folder picker. Offer only explicitly hidden, existing managed folders. */
    fun removedManagedProjects(): List<Pair<String, String>> {
        val home = projectsHome ?: return emptyList()
        val managed = index.canonical(home).trimEnd('/')
        return index.removedPaths().sorted().mapNotNull { dir ->
            if (dir.substringBeforeLast('/') != managed) return@mapNotNull null
            (store.open(dir) as? CoreResult.Ok)?.value?.let { dir to it.name }
        }
    }

    fun remove(dir: String) {
        val r = index.remove(dir)
        done((r as? PlatformResult.Failed)?.reason)
    }

    internal fun inspectDeletion(dir: String): Result {
        val root = projectsHome ?: return Result.Refused(Refusal.UNSUPPORTED)
        return deletion?.inspect(root, dir) ?: Result.Refused(Refusal.UNSUPPORTED)
    }

    internal suspend fun deleteProject(target: ProjectFolderDeletion.Target): UiText? {
        val root = projectsHome ?: return UiText.PROJECT_DELETE_UNSUPPORTED
        val guard = deletion ?: return UiText.PROJECT_DELETE_UNSUPPORTED
        val key = index.canonical(target.folder)
        fun busy() = ai?.hasPendingProject(key, index::canonical) == true || foregroundBusy(key)
        val renderPending = try { renders?.hasPendingProject(key, index::canonical) == true }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { return UiText.PROJECT_DELETE_BUSY }
        if (renderPending || busy()) return UiText.PROJECT_DELETE_BUSY
        return when (val result = guard.delete(root, target, ::busy)) {
            Result.Deleted -> {
                if (current.state.value?.let { it.project.id == target.projectId && index.canonical(it.dir) == key } == true) current.set(null)
                val written = index.markDeleted(target.folder, target.projectId)
                done((written as? PlatformResult.Failed)?.reason)
                if (written is PlatformResult.Failed) UiText.PROJECT_DELETE_INDEX_ERROR else null
            }
            is Result.Refused -> result.reason.label()
            is Result.Ready -> UiText.PROJECT_DELETE_FAILED
        }
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
            if (!name.endsWith(".autoflow") || index.isRemoved(dir)) continue
            // Refresh an already-listed recap's shape if older index versions copied only the fallback ratio.
            val existing = index.entries.value.firstOrNull { dir in known && it.path == dir }
            val project = (store.open(dir) as? CoreResult.Ok)?.value ?: continue
            if (existing != null && existing.ratio == (project.recap?.visual?.outputRatio ?: project.settings.ratio)) continue
            (index.record(dir, project, opened = false) as? PlatformResult.Failed)?.let { return it.reason }
        }
        return null
    }

    private fun opened(dir: String, project: Project): Boolean {
        current.set(OpenProject(dir, project))
        done((index.record(dir, project) as? PlatformResult.Failed)?.reason)
        return true
    }

    private fun fail(key: UiText): Boolean {
        val notice = UiNotice(key)
        _state.value = snapshot(notice.text(UiLanguage.ENGLISH)).copy(notice = notice)
        return false
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
        notice = message?.let { UiNotice(UiText.RESULT_DETAIL, "detail" to it) },
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


internal fun Refusal.label(): UiText = when (this) {
    Refusal.UNSUPPORTED -> UiText.PROJECT_DELETE_UNSUPPORTED
    Refusal.PROTECTED_FILES -> UiText.PROJECT_DELETE_PROTECTED
    Refusal.CHANGED -> UiText.PROJECT_DELETE_CHANGED
    Refusal.BUSY -> UiText.PROJECT_DELETE_BUSY
    Refusal.IO -> UiText.PROJECT_DELETE_FAILED
}
