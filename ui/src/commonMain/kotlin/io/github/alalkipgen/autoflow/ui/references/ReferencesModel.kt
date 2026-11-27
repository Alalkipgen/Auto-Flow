package io.github.alalkipgen.autoflow.ui.references

import io.github.alalkipgen.autoflow.ui.i18n.*
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
import kotlinx.coroutines.flow.combine
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
    /** Every imported reference image, oldest first: Ref A, Ref B, … on the shot sheet (5B-12). */
    val refs: List<ArtifactId> = emptyList(),
    val copyBlocker: io.github.alalkipgen.autoflow.core.workflow.Blocker? = null,
)

data class ReferencesUiState(
    val projectName: String? = null,
    val rows: List<RefRow> = emptyList(),
    val selected: Int? = null,
    val message: String? = null,
    val notice: UiNotice? = null,
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
    private val uiLanguage: () -> UiLanguage = { UiLanguage.ENGLISH },
) {
    private val _selected = MutableStateFlow<Int?>(null)
    private val _message = MutableStateFlow<String?>(null)
    private val _notice = MutableStateFlow<UiNotice?>(null)
    private fun reportMessage(message: String?) = reportNotice(message?.let { UiNotice(UiText.RESULT_DETAIL, "detail" to it) })
    private fun reportNotice(notice: UiNotice?) {
        _message.value = notice?.text(UiLanguage.ENGLISH)
        _notice.value = notice
    }
    val state: Flow<ReferencesUiState> = combine(session.state, _selected, _notice) { open, _, _ -> build(open?.project) }

    fun current(): ReferencesUiState = build(session.open?.project)

    fun select(number: Int?) {
        _selected.value = number
        reportMessage(null)
    }

    /** Copy the ImageDialect prompt for shot [number] to clipboard. */
    fun copyImagePrompt(number: Int) {
        val project = session.open?.project ?: run { reportNotice(UiNotice(UiText.NO_PROJECT_OPEN)); return }
        val shot = project.shots.firstOrNull { it.number == number } ?: run { reportNotice(UiNotice(UiText.SHOT_NOT_FOUND, "n" to number)); return }
        val ir = readPromptIr(shot) ?: run { reportNotice(UiNotice(UiText.NO_COMPILED_SHOT, "n" to number)); return }
        val prompt = ImageDialect.render(ir)
        when (val r = clipboard.copyText(prompt)) {
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Ok -> { /* ok */ }
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Failed -> { reportNotice(UiNotice(UiText.COPY_FAILED, "detail" to r.reason)); return }
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Unsupported -> { reportNotice(UiNotice(UiText.CLIPBOARD_UNAVAILABLE)); return }
        }
        reportNotice(UiNotice(UiText.IMAGE_PROMPT_COPIED))
    }

    fun openAiStudio() { urlOpener.open(aiStudioUrl) }

    /** Pick an image file and import it as a reference for shot [number]. */
    suspend fun pickAndImportRef(number: Int, readBytes: suspend (String) -> ByteArray?) {
        val project = session.open?.project ?: run { reportNotice(UiNotice(UiText.NO_PROJECT_OPEN)); return }
        val shot = project.shots.firstOrNull { it.number == number } ?: run { reportNotice(UiNotice(UiText.SHOT_NOT_FOUND, "n" to number)); return }
        val result = filePicker.pickFiles(UiText.PICK_REFERENCE_SHOT.text(uiLanguage(), "n" to number), extensions = setOf("png", "jpg", "jpeg"), multiple = false)
        val path = when (result) {
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Ok -> result.value.firstOrNull() ?: return
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Failed -> { reportNotice(UiNotice(UiText.FILE_PICK_FAILED, "detail" to result.reason)); return }
            is io.github.alalkipgen.autoflow.platform.PlatformResult.Unsupported -> { reportNotice(UiNotice(UiText.INPUT_NO_PICKER)); return }
        }
        val bytes = readBytes(path) ?: run { reportNotice(UiNotice(UiText.FILE_UNREADABLE)); return }
        val ext = path.substringAfterLast('.').lowercase().let { if (it in setOf("png", "jpg", "jpeg")) it else "png" }
        importRefBytes(project, shot, bytes, ext)
    }

    /** Import image bytes as a reference image for [shot]; wires into ArtifactStore. */
    fun importRefBytes(project: Project, shot: Shot, bytes: ByteArray, ext: String) {
        val artifacts = session.artifacts() ?: run { reportNotice(UiNotice(UiText.NO_PROJECT_OPEN)); return }
        val inputs = shot.promptIr?.let { mapOf(Inputs.PROMPT_IR to it.value) } ?: emptyMap()
        val artifact = when (val r = artifacts.put(bytes, ArtifactKind.IMAGE, ext, shot.number, inputs, provider = "user")) {
            is CoreResult.Ok -> r.value
            is CoreResult.Err -> { reportNotice(UiNotice(UiText.SAVE_IMAGE_FAILED, "detail" to r.error.message)); return }
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
            is CoreResult.Ok -> reportNotice(UiNotice(UiText.IMAGE_IMPORTED_SHOT, "n" to shot.number))
            is CoreResult.Err -> reportNotice(UiNotice(UiText.SAVE_FAILED, "detail" to r.error.message))
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

    /**
     * The bytes of reference image [id] of the open project for a thumbnail (images are shown as they are; none over
     * 4 MB, so a huge file never stalls the sheet). Null when missing or unreadable.
     */
    suspend fun refImage(id: ArtifactId): ByteArray? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        (session.artifacts()?.readBytes(id) as? CoreResult.Ok)?.value?.takeIf { it.size <= MAX_REF_THUMB_BYTES }
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
                copyBlocker = gate.blockers.firstOrNull(),
                refs = shot.refImages,
            )
        }
        return ReferencesUiState(
            projectName = project.name,
            rows = rows,
            selected = _selected.value,
            message = _message.value,
            notice = _notice.value,
            aiStudioUrl = aiStudioUrl,
        )
    }

    private fun io.github.alalkipgen.autoflow.core.workflow.Blocker.refMsg(): String = when (this) {
        io.github.alalkipgen.autoflow.core.workflow.Blocker.NO_PROMPT -> "Compile the shot prompt first (Shots screen)"
        io.github.alalkipgen.autoflow.core.workflow.Blocker.PROMPT_NOT_APPROVED -> "Approve the shot prompt first (Shots screen)"
        io.github.alalkipgen.autoflow.core.workflow.Blocker.PROMPT_STALE -> "Prompt is stale — recompile it (Shots screen)"
        else -> "Shot not ready"
    }

    companion object {
        const val MAX_REF_THUMB_BYTES = 4 * 1024 * 1024

        /** Ref A, Ref B, …, Ref Z, then Ref 27, … (DESIGN.md §3 "Ref A / Ref B"). */
        fun refLabel(index: Int): String = "Ref " + if (index < 26) ('A' + index).toString() else (index + 1).toString()
    }
}
