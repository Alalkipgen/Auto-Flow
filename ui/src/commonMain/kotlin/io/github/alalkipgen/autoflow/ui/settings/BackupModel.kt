package io.github.alalkipgen.autoflow.ui.settings

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.BackupProgress
import io.github.alalkipgen.autoflow.platform.BackupProject
import io.github.alalkipgen.autoflow.platform.BackupResult
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.ProjectBackup
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
            _state.update { it.copy(projects = 0, message = "No projects to back up yet") }
            return
        }
        _state.update { it.copy(projects = list.size, progress = BackupProgress(0, 0), message = null) }
        job = scope.launch {
            val r = try {
                b.backUp(list) { p -> _state.update { s -> if (s.running) s.copy(progress = p) else s } }
            } catch (e: CancellationException) {
                _state.update { it.copy(progress = null, message = "Backup cancelled; nothing was saved") }
                throw e
            }
            _state.update { s ->
                when (r) {
                    is PlatformResult.Ok -> s.copy(progress = null, last = r.value, message = saved(r.value))
                    is PlatformResult.Failed -> s.copy(progress = null, message = r.reason)
                    is PlatformResult.Unsupported -> s.copy(progress = null, message = r.reason)
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun share() {
        val b = backup ?: return
        val last = _state.value.last ?: return
        when (val r = b.share(last)) {
            is PlatformResult.Ok -> Unit
            is PlatformResult.Failed -> _state.update { it.copy(message = r.reason) }
            is PlatformResult.Unsupported -> _state.update { it.copy(message = r.reason) }
        }
    }

    private fun exists(path: String): Boolean = try {
        fs.exists(path)
    } catch (e: Exception) {
        false
    }

    companion object {
        fun saved(r: BackupResult): String =
            "Saved ${r.location} (${megabytes(r.zipBytes)}, ${r.projects} project${if (r.projects == 1) "" else "s"})"

        /** `0.4 MB`, `14.5 MB`, `1203 MB`: decimal megabytes like the phone's Files app. */
        fun megabytes(bytes: Long): String {
            val tenths = (bytes + 50_000) / 100_000
            return if (tenths >= 1000) "${tenths / 10} MB" else "${tenths / 10}.${tenths % 10} MB"
        }
    }
}
