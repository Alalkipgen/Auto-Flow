package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.components.decodeImage
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/** A cancellable, revision-bound edit. None of this presentation state is persisted in project.json. */
data class LayerDraft(
    val token: Long,
    val dir: String,
    val project: Project,
    val layer: Layer,
    val index: Int,
    val isNew: Boolean,
)
data class LayerEditorState(val draft: LayerDraft? = null, val problem: String? = null, val importing: Boolean = false)

/** Cohesive native-v2 editing; the existing session/store owns locks, atomic saves and revision conflicts. */
class RecapLayerEditor(
    private val session: ProjectSession,
    private val fs: FileSystem,
    private val picker: FilePicker?,
    private val scope: CoroutineScope,
    private val changed: () -> Unit,
) {
    private val _state = MutableStateFlow(LayerEditorState())
    val state = _state.asStateFlow()
    private var nextToken = 0L

    fun edit(id: String) {
        val open = session.open ?: return
        val layers = open.project.recap?.layers ?: return
        val index = layers.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        _state.value = LayerEditorState(LayerDraft(++nextToken, open.dir, open.project, layers[index], index, false))
    }

    fun add(type: LayerType) {
        val open = session.open ?: return
        val recap = open.project.recap ?: return
        val id = "l_" + Sha256.hex(Random.nextBytes(32)).take(16)
        val rect = when (type) {
            LayerType.BLUR -> listOf(0.1f, 0.7f, 0.8f, 0.2f)
            LayerType.IMAGE -> listOf(0.05f, 0.05f, 0.22f, 0.14f)
            LayerType.TEXT -> listOf(0.1f, 0.1f, 0.8f, 0.2f)
            else -> listOf(0f, 0f, 1f, 1f)
        }
        _state.value = LayerEditorState(LayerDraft(++nextToken, open.dir, open.project, Layer(id, type, rect), recap.layers.size, true))
    }

    fun cancel() { _state.value = LayerEditorState() }
    fun report(problem: String) { _state.value = _state.value.copy(problem = problem) }

    /** Staged drawing order; moving an unsaved layer never writes the project. */
    fun move(delta: Int) {
        val d = _state.value.draft ?: return
        val last = d.project.recap!!.layers.size - if (d.isNew) 0 else 1
        _state.value = _state.value.copy(draft = d.copy(index = (d.index + delta).coerceIn(0, last)))
    }

    fun save(layer: Layer): Boolean {
        val d = _state.value.draft ?: return false
        if (layer.id != d.layer.id || layer.type != d.layer.type) return fail("Layer identity changed; edit cancelled")
        LayerEdits.problem(layer)?.let { return fail(it) }
        return commit(d) { layers -> layers.filterNot { it.id == layer.id }.toMutableList().apply { add(d.index.coerceIn(0, size), layer) } }
    }

    /** Removes only the reference. Content-hash data and sidecars are never touched. */
    fun remove(): Boolean {
        val d = _state.value.draft ?: return false
        if (d.isNew) { cancel(); return true }
        return commit(d) { layers -> layers.filterNot { it.id == d.layer.id } }
    }

    private fun commit(d: LayerDraft, change: (List<Layer>) -> List<Layer>): Boolean {
        val open = session.open ?: return fail("Project closed; edit was not saved")
        if (open.dir != d.dir || open.project.id != d.project.id) return fail("Project changed; edit was not saved")
        if (open.project.updatedAt != d.project.updatedAt) return fail("Project revision changed; cancel and reopen the layer")
        val recap = d.project.recap ?: return false
        return when (val result = session.save(d.project.copy(recap = recap.copy(layers = change(recap.layers))))) {
            is CoreResult.Err -> fail(result.error.message)
            is CoreResult.Ok -> { cancel(); changed(); true }
        }
    }

    fun importImage(): Job? {
        val d = _state.value.draft ?: return null
        if (_state.value.importing) return null
        val p = picker ?: return fail("Choosing files is not available here").let { null }
        _state.value = _state.value.copy(importing = true, problem = null)
        return scope.launch {
            try {
                val path = when (val r = p.pickFiles("Layer image", RecapModel.IMAGE_EXTENSIONS)) {
                    is PlatformResult.Ok -> r.value.firstOrNull() ?: return@launch
                    is PlatformResult.Failed -> return@launch reportIfCurrent(d, r.reason)
                    is PlatformResult.Unsupported -> return@launch reportIfCurrent(d, r.reason)
                }
                val ext = path.substringAfterLast('.', "").lowercase()
                if (ext !in RecapModel.IMAGE_EXTENSIONS) return@launch reportIfCurrent(d, "Choose PNG, JPEG or WebP")
                val bytes = withContext(Dispatchers.Default) { runCatching { fs.read(path) }.getOrNull() }
                    ?: return@launch reportIfCurrent(d, "Image cannot be read")
                if (bytes.isEmpty() || decodeImage(bytes) == null) return@launch reportIfCurrent(d, "Image has no readable dimensions")
                ensureActive()
                // Capture the folder BEFORE the picker suspends, never session.artifacts() after it returns.
                if (!isCurrent(d)) return@launch
                val artifact = withContext(Dispatchers.Default) { ArtifactStore(fs, d.dir).put(bytes, ArtifactKind.IMAGE, ext, provider = "imported") }
                ensureActive()
                if (!isCurrent(d)) return@launch
                when (artifact) {
                    is CoreResult.Err -> reportIfCurrent(d, artifact.error.message)
                    is CoreResult.Ok -> {
                        val latest = _state.value.draft ?: return@launch
                        _state.value = _state.value.copy(draft = latest.copy(layer = latest.layer.copy(params = latest.layer.params.copy(image = artifact.value.id.value))))
                    }
                }
            } finally {
                if (_state.value.draft?.token == d.token) _state.value = _state.value.copy(importing = false)
            }
        }
    }

    private fun isCurrent(d: LayerDraft): Boolean = _state.value.draft?.token == d.token &&
        session.open?.let { it.dir == d.dir && it.project.id == d.project.id && it.project.updatedAt == d.project.updatedAt } == true
    private fun reportIfCurrent(d: LayerDraft, problem: String) { if (isCurrent(d)) report(problem) }
    private fun fail(problem: String): Boolean { report(problem); return false }
}
