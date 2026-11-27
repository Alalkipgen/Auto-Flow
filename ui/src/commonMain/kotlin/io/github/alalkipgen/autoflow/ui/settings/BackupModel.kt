package io.github.alalkipgen.autoflow.ui.settings

import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.platform.BackupProgress
import io.github.alalkipgen.autoflow.platform.BackupProject
import io.github.alalkipgen.autoflow.platform.BackupResult
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.ProjectBackup
import io.github.alalkipgen.autoflow.platform.RestoredProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Settings → Backup. [projects] = folders a backup would take now; [progress] is set while one runs. */
data class BackupUiState(
    val projects: Int = 0,
    val progress: BackupProgress? = null,
    val last: BackupResult? = null,
    val message: String? = null,
    val notice: UiNotice? = null,
) {
    val running: Boolean get() = progress != null
}

/**
 * "Back up all projects" (D-27): every listed project whose `project.json` still exists goes into one zip through
 * [backup]; the result stays for "Share…". Runs in [scope], so leaving Settings does not stop it. [backup] is null
 * where the platform has none (desktop), and Settings then shows no Backup card.
 */
class BackupModel(
    private val backup: ProjectBackup?,
    private val index: ProjectIndex,
    private val fs: FileSystem,
    private val scope: CoroutineScope,
    private val filePicker: FilePicker? = null,
    private val appDirs: AppDirs? = null,
    private val language: () -> UiLanguage = { UiLanguage.ENGLISH },
) {
    val available: Boolean get() = backup != null

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()
    private var job: Job? = null

    /** Recounts the projects; call when Settings opens. */
    fun refresh() = _state.update { it.copy(projects = projects().size) }

    /** Folder name inside the zip = the project folder's own name (`Rain.autoflow`). */
    fun projects(): List<BackupProject> = index.entries.value
        .filter { exists(joinPath(it.path, ProjectStore.PROJECT_FILE)) }
        .map { BackupProject(it.path, it.path.trimEnd('/').substringAfterLast('/')) }

    fun start() {
        val b = backup ?: return
        if (job?.isActive == true) return
        val list = projects()
        if (list.isEmpty()) {
            _state.update { it.copy(projects = 0, message = UiText.BACKUP_NONE.en, notice = UiNotice(UiText.BACKUP_NONE)) }
            return
        }
        _state.update { it.copy(projects = list.size, progress = BackupProgress(0, 0), message = null, notice = null) }
        job = scope.launch {
            val r = try {
                b.backUp(list) { p -> _state.update { s -> if (s.running) s.copy(progress = p) else s } }
            } catch (e: CancellationException) {
                _state.update { it.copy(progress = null, message = UiText.BACKUP_CANCELLED.en, notice = UiNotice(UiText.BACKUP_CANCELLED)) }
                throw e
            }
            _state.update { s ->
                when (r) {
                    is PlatformResult.Ok -> s.copy(progress = null, last = r.value, message = saved(r.value), notice = savedNotice(r.value))
                    is PlatformResult.Failed -> s.copy(progress = null, message = r.reason, notice = UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
                    is PlatformResult.Unsupported -> s.copy(progress = null, message = r.reason, notice = UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Restore = pick a zip → extract into projectsHome. The Dashboard auto-discovers restored folders on next open. */
    fun restore() {
        val b = backup ?: run { _state.update { it.copy(message = UiText.RESTORE_UNAVAILABLE.en, notice = UiNotice(UiText.RESTORE_UNAVAILABLE)) }; return }
        val picker = filePicker ?: run { _state.update { it.copy(message = UiText.PICKER_UNAVAILABLE.en, notice = UiNotice(UiText.PICKER_UNAVAILABLE)) }; return }
        val dest = appDirs?.projectsHome ?: run { _state.update { it.copy(message = UiText.PROJECTS_FOLDER_UNSET.en, notice = UiNotice(UiText.PROJECTS_FOLDER_UNSET)) }; return }
        if (job?.isActive == true) return
        job = scope.launch {
            _state.update { it.copy(progress = BackupProgress(0, 1), message = null, notice = null) }
            val paths = when (val r = picker.pickFiles(UiText.RESTORE_PICKER.text(language()), extensions = setOf("zip"))) {
                is PlatformResult.Ok -> r.value
                is PlatformResult.Failed -> { _state.update { it.copy(progress = null, message = r.reason, notice = UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason)) }; return@launch }
                is PlatformResult.Unsupported -> { _state.update { it.copy(progress = null, message = r.reason, notice = UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason)) }; return@launch }
            }
            val path = paths.firstOrNull() ?: run { _state.update { it.copy(progress = null) }; return@launch }
            when (val r = b.restore(path, dest)) {
                is PlatformResult.Ok -> {
                    val store = ProjectStore(fs)
                    var listError: String? = null
                    for (entry in r.value) {
                        // Only the actual published destination, not the original/same-name project.
                        val dir = joinPath(dest, entry.folder)
                        val project = (store.open(dir) as? CoreResult.Ok)?.value ?: continue
                        (index.record(dir, project) as? PlatformResult.Failed)?.let { listError = it.reason }
                    }
                    val notice = listError?.let { UiNotice(UiText.RESULT_DETAIL, "detail" to "${restored(r.value, language())} · $it") } ?: restoredNotice(r.value)
                    _state.update { it.copy(progress = null, message = notice.text(UiLanguage.ENGLISH), notice = notice) }
                }
                is PlatformResult.Failed -> _state.update { it.copy(progress = null, message = r.reason, notice = UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason)) }
                is PlatformResult.Unsupported -> _state.update { it.copy(progress = null, message = r.reason, notice = UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason)) }
            }
        }
    }

    fun share() {
        val b = backup ?: return
        val last = _state.value.last ?: return
        when (val r = b.share(last)) {
            is PlatformResult.Ok -> Unit
            is PlatformResult.Failed -> _state.update { it.copy(message = r.reason, notice = UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason)) }
            is PlatformResult.Unsupported -> _state.update { it.copy(message = r.reason, notice = UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason)) }
        }
    }

    private fun exists(path: String): Boolean = try {
        fs.exists(path)
    } catch (e: Exception) {
        false
    }

    companion object {
        /** The Restore result line; a renamed copy says that the same-named project on the phone was kept as it was. */
        fun restored(projects: List<RestoredProject>, language: UiLanguage = UiLanguage.ENGLISH): String =
            restoredNotice(projects).text(language)

        fun restoredNotice(projects: List<RestoredProject>): UiNotice {
            if (projects.isEmpty()) return UiNotice(UiText.RESTORE_EMPTY)
            val kept = projects.filter { it.renamed }.map { it.original }
            val note: Any = if (kept.isEmpty()) "" else UiNotice(
                if (kept.size == 1) UiText.RESTORE_KEPT_ONE else UiText.RESTORE_KEPT_MANY,
                "names" to kept.joinToString(", "),
            )
            return UiNotice(UiText.RESTORE_DONE, "names" to projects.joinToString(", ") { it.folder }, "note" to note)
        }

        fun saved(r: BackupResult, language: UiLanguage = UiLanguage.ENGLISH): String = savedNotice(r).text(language)
        fun savedNotice(r: BackupResult): UiNotice = UiNotice(UiText.BACKUP_SAVED,
            "location" to r.location, "size" to fileSize(r.zipBytes),
            "projects" to if (r.projects == 1) UiNotice(UiText.ONE_PROJECT) else UiNotice(UiText.PROJECT_COUNT, "n" to r.projects))

        /**
         * `899 B`, `12 KB`, `0.4 MB`, `14.5 MB`, `1203 MB`: decimal units like the phone's Files app, so a backup of
         * projects without media does not read "0.0 MB" (owner's v0.1.6 check).
         */
        fun fileSize(bytes: Long): String {
            if (bytes < 1_000) return "$bytes B"
            if (bytes < 100_000) return "${(bytes + 500) / 1_000} KB"
            val tenths = (bytes + 50_000) / 100_000
            return if (tenths >= 1000) "${tenths / 10} MB" else "${tenths / 10}.${tenths % 10} MB"
        }
    }
}
