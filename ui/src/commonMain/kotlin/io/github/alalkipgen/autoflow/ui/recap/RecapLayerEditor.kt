package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ui.i18n.*
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
data class LayerEditorState(val draft: LayerDraft? = null, val problem: String? = null, val importing: Boolean = false,
    val notice: UiNotice? = null, val fields: Map<String, String> = emptyMap())

/** Cohesive native-v2 editing; the existing session/store owns locks, atomic saves and revision conflicts. */
class RecapLayerEditor(
    private val session: ProjectSession,
    private val fs: FileSystem,
    private val picker: FilePicker?,
    private val scope: CoroutineScope,
    private val uiLanguage: () -> UiLanguage = { UiLanguage.ENGLISH },
    private val changed: () -> Unit,
) {
    private val _state = MutableStateFlow(LayerEditorState())
    val state = _state.asStateFlow()
    private var nextToken = 0L

    fun edit(id: String) {
        val open = session.open ?: return
        val layers = open.project.recap?.layers ?: return
        val index = layers.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        _state.value = LayerEditorState(LayerDraft(++nextToken, open.dir, open.project, layers[index], index, false), fields = LayerForm.of(layers[index]).fields)
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
        val layer = Layer(id, type, rect)
        _state.value = LayerEditorState(LayerDraft(++nextToken, open.dir, open.project, layer, recap.layers.size, true), fields = LayerForm.of(layer).fields)
    }

    fun cancel() { _state.value = LayerEditorState() }

    /** One transient form for numeric controls and on-video handles, never a second persisted model. */
    internal fun field(token: Long, name: String, text: String) {
        val d = _state.value.draft ?: return
        if (d.token == token && isCurrent(d) && !_state.value.importing)
            _state.value = _state.value.copy(fields = _state.value.fields + (name to text))
    }
    internal fun value(): Layer? {
        val d = _state.value.draft ?: return null
        if (!isCurrent(d)) return null
        return LayerForm(_state.value.fields).read(d.layer)?.takeIf { LayerEdits.problem(it) == null }
    }
    internal fun rect(token: Long, rect: List<Float>) {
        val d = _state.value.draft ?: return
        val layer = value() ?: return
        if (d.token != token || _state.value.importing || !LayerEdits.usesRect(layer.type) || LayerEdits.problem(layer.copy(rect = rect)) != null) return
        _state.value = _state.value.copy(fields = LayerForm(_state.value.fields).rect(rect).fields)
    }
    internal fun preview(recap: RecapV2): RecapV2 {
        val d = _state.value.draft ?: return recap
        val layer = value() ?: return recap
        if (d.project.recap != recap) return recap
        val layers = recap.layers.filterNot { it.id == layer.id }.toMutableList()
        layers.add(d.index.coerceIn(0, layers.size), layer)
        return recap.copy(layers = layers)
    }
    fun report(problem: String) = reportNotice(UiNotice(UiText.RESULT_DETAIL, "detail" to problem))
    private fun reportNotice(notice: UiNotice) {
        _state.value = _state.value.copy(problem = notice.text(UiLanguage.ENGLISH), notice = notice)
    }

    /** Staged drawing order; moving an unsaved layer never writes the project. */
    fun move(delta: Int) {
        val d = _state.value.draft ?: return
        val last = d.project.recap!!.layers.size - if (d.isNew) 0 else 1
        _state.value = _state.value.copy(draft = d.copy(index = (d.index + delta).coerceIn(0, last)))
    }

    fun save(layer: Layer): Boolean {
        val d = _state.value.draft ?: return false
        if (layer.id != d.layer.id || layer.type != d.layer.type) return fail(UiText.LAYER_ID_CHANGED)
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
        val open = session.open ?: return fail(UiText.LAYER_PROJECT_CLOSED)
        if (open.dir != d.dir || open.project.id != d.project.id) return fail(UiText.LAYER_PROJECT_CHANGED)
        if (open.project.updatedAt != d.project.updatedAt) return fail(UiText.LAYER_REVISION_CHANGED)
        val recap = d.project.recap ?: return false
        return when (val result = session.save(d.project.copy(recap = recap.copy(layers = change(recap.layers))))) {
            is CoreResult.Err -> fail(result.error.message)
            is CoreResult.Ok -> { cancel(); changed(); true }
        }
    }

    fun importImage(): Job? {
        val d = _state.value.draft ?: return null
        if (_state.value.importing) return null
        val p = picker ?: return fail(UiText.CHOOSE_FILES_UNAVAILABLE).let { null }
        _state.value = _state.value.copy(importing = true, problem = null, notice = null)
        return scope.launch {
            try {
                val path = when (val r = p.pickFiles(UiText.PICK_LAYER_IMAGE.text(uiLanguage()), RecapModel.IMAGE_EXTENSIONS)) {
                    is PlatformResult.Ok -> r.value.firstOrNull() ?: return@launch
                    is PlatformResult.Failed -> return@launch reportIfCurrent(d, r.reason)
                    is PlatformResult.Unsupported -> return@launch reportIfCurrent(d, r.reason)
                }
                val ext = path.substringAfterLast('.', "").lowercase()
                if (ext !in RecapModel.IMAGE_EXTENSIONS) return@launch reportIfCurrent(d, UiText.LAYER_IMAGE_TYPE)
                val bytes = withContext(Dispatchers.Default) { runCatching { fs.read(path) }.getOrNull() }
                    ?: return@launch reportIfCurrent(d, UiText.LAYER_IMAGE_UNREADABLE)
                if (bytes.isEmpty() || decodeImage(bytes) == null) return@launch reportIfCurrent(d, UiText.LAYER_IMAGE_DIMENSIONS)
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
    private fun reportIfCurrent(d: LayerDraft, key: UiText) { if (isCurrent(d)) reportNotice(UiNotice(key)) }
    private fun fail(problem: String): Boolean { report(problem); return false }
    private fun fail(key: UiText): Boolean { reportNotice(UiNotice(key)); return false }
}
