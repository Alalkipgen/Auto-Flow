package io.github.alalkipgen.autoflow.ui.inbox

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.HandoffState
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.workflow.Inputs
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.shots.ShotsModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

enum class InboxItemKind { VIDEO, IMAGE, OTHER }

data class InboxItem(
    val name: String,
    val path: String,
    val kind: InboxItemKind,
)

data class InboxUiState(
    val projectName: String? = null,
    val items: List<InboxItem> = emptyList(),
    val shotNumbers: List<Int> = emptyList(),
    val message: String? = null,
    val inboxDir: String? = null,
)

/**
 * Inbox screen: lists files in `<project>/inbox/`, lets the user attach a video or image to a shot.
 * No live watcher — the list is refreshed when the screen is opened or "Refresh" is tapped.
 * Done files are moved to `.cache/inbox-done/` (spec).
 */
class InboxModel(private val session: ProjectSession, private val fs: FileSystem) {
    private val _state = MutableStateFlow(InboxUiState())
    val state: Flow<InboxUiState> = session.state.map { open ->
        _state.value.copy(
            projectName = open?.project?.name,
            shotNumbers = open?.project?.shots?.map { it.number } ?: emptyList(),
            inboxDir = open?.let { joinPath(it.dir, INBOX_DIR) },
        )
    }
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun current(): InboxUiState {
        val open = session.open
        return _state.value.copy(
            projectName = open?.project?.name,
            shotNumbers = open?.project?.shots?.map { it.number } ?: emptyList(),
            inboxDir = open?.let { joinPath(it.dir, INBOX_DIR) },
        )
    }

    /** Scan the inbox folder and return the items. */
    fun refresh(): InboxUiState {
        val dir = session.open?.let { joinPath(it.dir, INBOX_DIR) } ?: return current().also { _message.value = "No project open" }
        val items = try {
            if (!fs.exists(dir)) {
                fs.createDirectories(dir)
                emptyList()
            } else {
                fs.list(dir)
                    .filter { name -> !name.startsWith(".") }
                    .map { name ->
                        InboxItem(
                            name = name,
                            path = joinPath(dir, name),
                            kind = when (name.substringAfterLast('.').lowercase()) {
                                "mp4", "mov" -> InboxItemKind.VIDEO
                                "png", "jpg", "jpeg" -> InboxItemKind.IMAGE
                                else -> InboxItemKind.OTHER
                            },
                        )
                    }
                    .sortedBy { it.name }
            }
        } catch (e: Exception) {
            _message.value = "Could not read inbox: ${e.message ?: e::class.simpleName}"
            emptyList()
        }
        val next = current().copy(items = items, message = _message.value)
        _state.value = next
        return next
    }

    /**
     * Attach [item] to shot [shotNumber]:
     * - VIDEO → save as `vd` artifact, state = IMPORTED
     * - IMAGE → save as `im` artifact, set as chosenRef
     */
    fun attach(item: InboxItem, shotNumber: Int) {
        if (item.kind == InboxItemKind.OTHER) { _message.value = "Cannot attach this file type"; return }
        val project = session.open?.project ?: run { _message.value = "No project open"; return }
        val shot = project.shots.firstOrNull { it.number == shotNumber } ?: run { _message.value = "Shot $shotNumber not found"; return }
        val bytes = try { fs.read(item.path) } catch (e: Exception) { _message.value = "Could not read ${item.name}"; return }
        val ext = item.name.substringAfterLast('.').lowercase()
        val artifacts = session.artifacts() ?: run { _message.value = "No project open"; return }

        when (item.kind) {
            InboxItemKind.VIDEO -> attachVideo(project, shot, bytes, ext, artifacts, item)
            InboxItemKind.IMAGE -> attachImage(project, shot, bytes, ext, artifacts, item)
            InboxItemKind.OTHER -> { /* guarded above */ }
        }
    }

    private fun attachVideo(project: Project, shot: Shot, bytes: ByteArray, ext: String, artifacts: io.github.alalkipgen.autoflow.core.store.ArtifactStore, item: InboxItem) {
        val inputs = buildMap {
            shot.promptIr?.let { put(Inputs.PROMPT_IR, it.value) }
            shot.chosenRef?.let { put(Inputs.REF, it.value) }
        }
        val artifact = when (val r = artifacts.put(bytes, ArtifactKind.VIDEO, ext, shot.number, inputs, provider = "inbox", note = shot.handoff.pass.name.lowercase())) {
            is CoreResult.Ok -> r.value
            is CoreResult.Err -> { _message.value = "Failed to save video: ${r.error.message}"; return }
        }
        val shots = project.shots.map { s ->
            if (s.number == shot.number)
                s.copy(videos = (s.videos + artifact.id).distinct(), handoff = s.handoff.copy(state = HandoffState.IMPORTED))
            else s
        }
        when (val r = session.save(project.copy(shots = shots))) {
            is CoreResult.Ok -> { _message.value = "Video attached to Shot ${shot.number}."; moveToArchive(item) }
            is CoreResult.Err -> _message.value = "Save failed: ${r.error.message}"
        }
    }

    private fun attachImage(project: Project, shot: Shot, bytes: ByteArray, ext: String, artifacts: io.github.alalkipgen.autoflow.core.store.ArtifactStore, item: InboxItem) {
        val inputs = shot.promptIr?.let { mapOf(Inputs.PROMPT_IR to it.value) } ?: emptyMap()
        val artifact = when (val r = artifacts.put(bytes, ArtifactKind.IMAGE, ext, shot.number, inputs, provider = "inbox")) {
            is CoreResult.Ok -> r.value
            is CoreResult.Err -> { _message.value = "Failed to save image: ${r.error.message}"; return }
        }
        val shots = project.shots.map { s ->
            if (s.number == shot.number) {
                val refs = (s.refImages + artifact.id).distinct()
                val chosen = s.chosenRef ?: artifact.id
                s.copy(refImages = refs, chosenRef = chosen, approvals = s.approvals.copy(ref = false))
            } else s
        }
        when (val r = session.save(project.copy(shots = shots))) {
            is CoreResult.Ok -> { _message.value = "Image attached to Shot ${shot.number}."; moveToArchive(item) }
            is CoreResult.Err -> _message.value = "Save failed: ${r.error.message}"
        }
    }

    private fun moveToArchive(item: InboxItem) {
        try {
            val doneDir = session.open?.let { joinPath(it.dir, DONE_DIR) } ?: return
            fs.createDirectories(doneDir)
            val dest = joinPath(doneDir, item.name)
            fs.atomicMove(item.path, dest)
        } catch (e: Exception) {
            // Advisory: file stays; not fatal
        }
    }

    companion object {
        const val INBOX_DIR = "inbox"
        const val DONE_DIR = ".cache/inbox-done"
    }
}
