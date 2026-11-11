package io.github.alalkipgen.autoflow.ui.references

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.model.ShotSpec
import io.github.alalkipgen.autoflow.core.prompt.ImageDialect
import io.github.alalkipgen.autoflow.core.prompt.PromptIR
import io.github.alalkipgen.autoflow.core.workflow.ApprovalStep
import io.github.alalkipgen.autoflow.core.workflow.Inputs
import io.github.alalkipgen.autoflow.core.workflow.ShotStatus
import io.github.alalkipgen.autoflow.core.workflow.WorkflowRules
import io.github.alalkipgen.autoflow.platform.Clipboard
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.UrlOpener
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.shots.ShotsModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** One row on the References screen. */
data class RefRow(
    val number: Int,
    val timecode: String,
    val lyric: String,
    val imagePrompt: String?,
    val refCount: Int,
    val chosenRef: ArtifactId?,
    val refApproved: Boolean,
    val status: ShotStatus,
    /** null = ok to copy prompt; non-null = gate message */
    val copyGateMsg: String?,
)

data class ReferencesUiState(
    val projectName: String? = null,
    val rows: List<RefRow> = emptyList(),
    val selected: Int? = null,
    val message: String? = null,
    val aiStudioUrl: String = "https://aistudio.google.com/",
)

/**
 * References screen (D-14 manual ref-image handoff): copy ImageDialect prompt → paste in AI Studio / Flow →
 * import the generated PNG/JPG → choose Ref A/B → approve.
 */
class ReferencesModel(
    private val session: ProjectSession,
    private val clipboard: Clipboard,
    private val urlOpener: UrlOpener,
    private val filePicker: FilePicker,
    private val aiStudioUrl: String = "https://aistudio.google.com/",
) {
    val state: Flow<ReferencesUiState> = session.state.map { open -> build(open?.project) }
    private val _selected = MutableStateFlow<Int?>(null)
    private val _message = MutableStateFlow<String?>(null)

    fun current(): ReferencesUiState = build(session.open?.project)

    fun select(number: Int?) {
        _selected.value = number
        _message.value = null
    }

    /** Copy the ImageDialect prompt for shot [number] to clipboard. */
    fun copyImagePrompt(number: Int) {
        val project = session.open?.project ?: run { _message.value = "No project open"; return }
        val shot = project.shots.firstOrNull { it.number == number } ?: run { _message.value = "Shot $number not found"; return }
        val ir = readPromptIr(shot) ?: run { _message.value = "No compiled prompt for shot $number — compile it on the Shots screen first"; return }
        val prompt = ImageDialect.render(ir)
        when (val r = clipboard.copyText(prompt)) {
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Ok -> { /* ok */ }
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Failed -> { _message.value = "Could not copy: ${r.reason}"; return }
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Unsupported -> { _message.value = "Clipboard not available"; return }
        }
        _message.value = "Image prompt copied! Paste it in AI Studio or Flow."
    }

    fun openAiStudio() { urlOpener.open(aiStudioUrl) }

    /** Pick an image file and import it as a reference for shot [number]. */
    suspend fun pickAndImportRef(number: Int, readBytes: suspend (String) -> ByteArray?) {
        val project = session.open?.project ?: run { _message.value = "No project open"; return }
        val shot = project.shots.firstOrNull { it.number == number } ?: run { _message.value = "Shot $number not found"; return }
        val result = filePicker.pickFiles("Import reference image for Shot $number", extensions = setOf("png", "jpg", "jpeg"), multiple = false)
        val path = when (result) {
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Ok -> result.value.firstOrNull() ?: return
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Failed -> { _message.value = "File pick failed: ${result.reason}"; return }
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Unsupported -> { _message.value = "File picker not available on this platform yet"; return }
        }
        val bytes = readBytes(path) ?: run { _message.value = "Could not read the file"; return }
        val ext = path.substringAfterLast('.').lowercase().let { if (it in setOf("png", "jpg", "jpeg")) it else "png" }
        importRefBytes(project, shot, bytes, ext)
    }

    /** Import image bytes as a reference image for [shot]; wires into ArtifactStore. */
    fun importRefBytes(project: Project, shot: Shot, bytes: ByteArray, ext: String) {
        val artifacts = session.artifacts() ?: run { _message.value = "No project open"; return }
        val inputs = shot.promptIr?.let { mapOf(Inputs.PROMPT_IR to it.value) } ?: emptyMap()
        val artifact = when (val r = artifacts.put(bytes, ArtifactKind.IMAGE, ext, shot.number, inputs, provider = "user")) {
            is CoreResult.Ok -> r.value
            is CoreResult.Err -> { _message.value = "Failed to save image: ${r.error.message}"; return }
        }
        // Add to refImages; set as chosenRef automatically if none chosen yet
        val shots = project.shots.map { s ->
            if (s.number == shot.number) {
                val refs = (s.refImages + artifact.id).distinct()
                val chosen = s.chosenRef ?: artifact.id
                s.copy(refImages = refs, chosenRef = chosen, approvals = s.approvals.copy(ref = false))
            } else s
        }
        when (val r = session.save(project.copy(shots = shots))) {
            is CoreResult.Ok -> _message.value = "Reference image imported for Shot ${shot.number}."
            is CoreResult.Err -> _message.value = "Save failed: ${r.error.message}"
        }
    }

    /** Set the chosen ref for shot [number] to [refId]. */
    fun setChosenRef(number: Int, refId: ArtifactId) {
        val project = session.open?.project ?: return
        val shots = project.shots.map { s ->
            if (s.number == number) s.copy(chosenRef = refId, approvals = s.approvals.copy(ref = false)) else s
        }
        session.save(project.copy(shots = shots))
    }

    /** Approve / un-approve the chosen reference for shot [number]. */
    fun approveRef(number: Int, approved: Boolean = true): String? {
        val project = session.open?.project ?: return "No project open"
        val shot = project.shots.firstOrNull { it.number == number } ?: return "Shot $number not found"
        if (shot.chosenRef == null && approved) return "Import a reference image first"
        val shots = project.shots.map { if (it.number == number) it.copy(approvals = it.approvals.copy(ref = approved)) else it }
        return when (val r = session.save(project.copy(shots = shots))) {
            is CoreResult.Ok -> null
            is CoreResult.Err -> r.error.message
        }
    }

    /** Approve all shots with a chosen ref. */
    fun approveAll() {
        val project = session.open?.project ?: return
        session.save(WorkflowRules.approveAll(project, ApprovalStep.REF, session.lookup()))
    }

    private fun readPromptIr(shot: Shot): PromptIR? =
        shot.promptIr?.let { (session.readJson(it, PromptIR.serializer()) as? CoreResult.Ok)?.value }

    private fun build(project: Project?): ReferencesUiState {
        if (project == null) return ReferencesUiState()
        val lookup = session.lookup()
        val rows = project.shots.map { shot ->
            val ir = readPromptIr(shot)
            val gate = WorkflowRules.canGenerateRef(project, shot, lookup)
            val copyGate = if (gate.allowed) null else gate.blockers.firstOrNull()?.refMsg()
            RefRow(
                number = shot.number,
                timecode = ShotsModel.formatMs(shot.startMs),
                lyric = shot.lyricsMy.take(40),
                imagePrompt = ir?.let { ImageDialect.render(it) },
                refCount = shot.refImages.size,
                chosenRef = shot.chosenRef,
                refApproved = shot.approvals.ref,
                status = ShotStatus.of(project, shot, lookup),
                copyGateMsg = copyGate,
            )
        }
        return ReferencesUiState(
            projectName = project.name,
            rows = rows,
            selected = _selected.value,
            message = _message.value,
            aiStudioUrl = aiStudioUrl,
        )
    }

    private fun io.github.alalkipgen.autoflow.core.workflow.Blocker.refMsg(): String = when (this) {
        io.github.alalkipgen.autoflow.core.workflow.Blocker.NO_PROMPT -> "Compile the shot prompt first (Shots screen)"
        io.github.alalkipgen.autoflow.core.workflow.Blocker.PROMPT_NOT_APPROVED -> "Approve the shot prompt first (Shots screen)"
        io.github.alalkipgen.autoflow.core.workflow.Blocker.PROMPT_STALE -> "Prompt is stale — recompile it (Shots screen)"
        else -> "Shot not ready"
    }
}
