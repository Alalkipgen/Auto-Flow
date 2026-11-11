package io.github.alalkipgen.autoflow.ui.shots

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.workflow.WorkflowRules
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.core.model.HandoffState
import io.github.alalkipgen.autoflow.core.model.Pass
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.model.ShotSpec
import io.github.alalkipgen.autoflow.core.prompt.PromptCompiler
import io.github.alalkipgen.autoflow.core.prompt.PromptIR
import io.github.alalkipgen.autoflow.core.prompt.VeoFlowDialect
import io.github.alalkipgen.autoflow.core.workflow.ShotStatus
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** One row in the Shots grid. */
data class ShotRow(
    val number: Int,
    val timecode: String,
    val lyric: String,
    val status: ShotStatus,
)

/** Detail panel for one shot. */
data class ShotDetail(
    val number: Int,
    val timecode: String,
    val lyric: String,
    val spec: ShotSpec?,
    /** Rendered Veo/Flow prompt text; null = not compiled yet. */
    val promptText: String?,
    /** Non-null if spec exists but compile would fail (shows what is missing). */
    val compileError: String?,
    val status: ShotStatus,
    val approvedPrompt: Boolean,
    /** Absolute path to the accepted video file, or null if none yet. */
    val acceptedVideoPath: String? = null,
    /** The shot's Flow pass (label only, specs/workflow-and-approval.md): Draft = Veo Fast, Final = Veo Quality. */
    val pass: Pass = Pass.DRAFT,
    /** Every imported video for this shot, oldest first (v1, v2…): the version browser. */
    val versions: List<VideoVersion> = emptyList(),
    /** The version shown in the player: the one the user picked, else the accepted one, else the newest. */
    val previewing: ArtifactId? = null,
)

/** One imported video of a shot. [pass] comes from the artifact note written at import (`draft` / `final`). */
data class VideoVersion(
    val version: Int,
    val id: ArtifactId,
    val path: String?,
    val pass: Pass?,
    /** `YYYY-MM-DD HH:MM` (UTC) of the import. */
    val importedAt: String,
    val provider: String?,
    val accepted: Boolean,
)

data class ShotsUiState(
    val projectName: String? = null,
    val rows: List<ShotRow> = emptyList(),
    val selected: Int? = null,
    val detail: ShotDetail? = null,
    /** Non-null after a failed compile or approve action. */
    val actionError: String? = null,
)

/** Shots screen logic: grid + detail panel. Compile prompt is deterministic (no AI call). */
class ShotsModel(private val session: ProjectSession) {
    private val _selected = MutableStateFlow<Int?>(null)
    private val _actionError = MutableStateFlow<String?>(null)
    /** Shot number → the video version picked for the player. */
    private val _preview = MutableStateFlow<Map<Int, ArtifactId>>(emptyMap())
    val selected = _selected.asStateFlow()
    // Combine both: re-emit whenever the project saves OR the selection changes
    val state: Flow<ShotsUiState> =
        combine(session.state, _selected, _actionError, _preview) { open, _, _, _ -> build(open?.project) }

    fun current(): ShotsUiState = build(session.open?.project)

    fun select(number: Int?) {
        _selected.value = number
        _actionError.value = null
    }

    /** Compile the PromptIR for shot [number] from its current spec, then save it. Returns error or null. */
    fun compilePrompt(number: Int): String? {
        val project = session.open?.project ?: return "No project open"
        val shot = project.shots.firstOrNull { it.number == number } ?: return "Shot $number not found"
        val gate = WorkflowRules.canCompilePrompt(shot)
        if (!gate.allowed) return gate.blockers.joinToString("; ") { it.text() }.also { _actionError.value = it }
        val spec = readSpec(shot) ?: return "No storyboard for shot $number — generate storyboard first"
        val ratio = project.settings.ratio
        return when (val r = PromptCompiler.compile(spec, project.globalStyle, project.characters, ratio, shot.durationMs)) {
            is CoreResult.Ok -> savePrompt(project, shot, r.value).also { _actionError.value = it }
            is CoreResult.Err -> r.error.message.also { _actionError.value = it }
        }
    }

    /** Approve / un-approve the compiled prompt for shot [number]. */
    fun approvePrompt(number: Int, approved: Boolean = true): String? {
        val project = session.open?.project ?: return "No project open"
        val shot = project.shots.firstOrNull { it.number == number } ?: return "Shot $number not found"
        if (shot.promptIr == null && approved) return "Compile the prompt first"
        val shots = project.shots.map { if (it.number == number) it.copy(approvals = it.approvals.copy(prompt = approved)) else it }
        return when (val r = session.save(project.copy(shots = shots))) {
            is CoreResult.Ok -> null.also { _actionError.value = null }
            is CoreResult.Err -> r.error.message.also { _actionError.value = r.error.message }
        }
    }

    /** Show version [id] of shot [number] in the player (nothing is saved). */
    fun preview(number: Int, id: ArtifactId) {
        _preview.value = _preview.value + (number to id)
    }

    /**
     * Use version [id] as the shot's accepted video (DESIGN.md: regenerate adds a version, never replaces; the user
     * picks). Same effect as Accept on Flow Handoff: accepted video, video approval, handoff ACCEPTED.
     */
    fun acceptVersion(number: Int, id: ArtifactId): String? {
        val project = session.open?.project ?: return "No project open"
        val shot = project.shots.firstOrNull { it.number == number } ?: return "Shot $number not found"
        if (id !in shot.videos) return "That video is not one of Shot $number's versions"
        val shots = project.shots.map { s ->
            if (s.number == number) s.copy(
                acceptedVideo = id,
                approvals = s.approvals.copy(video = true),
                handoff = s.handoff.copy(state = HandoffState.ACCEPTED),
            ) else s
        }
        return when (val r = session.save(project.copy(shots = shots))) {
            is CoreResult.Ok -> null.also { _actionError.value = null; _preview.value = _preview.value + (number to id) }
            is CoreResult.Err -> r.error.message.also { _actionError.value = it }
        }
    }

    private fun versions(shot: Shot): List<VideoVersion> {
        val dir = session.open?.dir ?: return emptyList()
        val artifacts = session.artifacts() ?: return emptyList()
        return shot.videos.mapIndexed { i, id ->
            val a = (artifacts.get(id) as? CoreResult.Ok)?.value
            VideoVersion(
                version = i + 1,
                id = id,
                path = a?.let { joinPath(joinPath(dir, ArtifactStore.ARTIFACTS_DIR), it.file) },
                pass = when (a?.note) {
                    "draft" -> Pass.DRAFT
                    "final" -> Pass.FINAL
                    else -> null
                },
                importedAt = a?.createdAt?.toString()?.take(16)?.replace('T', ' ') ?: "",
                provider = a?.provider,
                accepted = id == shot.acceptedVideo,
            )
        }
    }

    private fun savePrompt(project: Project, shot: Shot, ir: PromptIR): String? {
        val artifacts = session.artifacts() ?: return "No project open"
        val json = CoreJson.encodeToString(PromptIR.serializer(), ir)
        val artifact = (artifacts.put(json.encodeToByteArray(), ArtifactKind.PROMPT_IR, "json", shot.number) as? CoreResult.Ok)?.value
            ?: return "Failed to save prompt artifact"
        val shots = project.shots.map { s ->
            if (s.number == shot.number) s.copy(promptIr = artifact.id, approvals = s.approvals.copy(prompt = false)) else s
        }
        return when (val r = session.save(project.copy(shots = shots))) {
            is CoreResult.Ok -> null
            is CoreResult.Err -> r.error.message
        }
    }

    private fun readSpec(shot: Shot): ShotSpec? =
        shot.spec?.let { (session.readJson(it, ShotSpec.serializer()) as? CoreResult.Ok)?.value }

    private fun readPromptIr(shot: Shot): PromptIR? =
        shot.promptIr?.let { (session.readJson(it, PromptIR.serializer()) as? CoreResult.Ok)?.value }

    /** Returns the absolute file path of [id] in the current project's artifact store, or null on any error. */
    private fun resolveArtifactPath(id: ArtifactId): String? {
        val dir = session.open?.dir ?: return null
        val artifact = (session.artifacts()?.get(id) as? CoreResult.Ok)?.value ?: return null
        return joinPath(joinPath(dir, ArtifactStore.ARTIFACTS_DIR), artifact.file)
    }

    private fun build(project: Project?): ShotsUiState {
        if (project == null) return ShotsUiState()
        val lookup = session.lookup()
        val sel = _selected.value
        val rows = project.shots.map { shot ->
            ShotRow(
                number = shot.number,
                timecode = formatMs(shot.startMs),
                lyric = shot.lyricsMy.take(40),
                status = ShotStatus.of(project, shot, lookup),
            )
        }
        val detail = sel?.let { n ->
            val shot = project.shots.firstOrNull { it.number == n } ?: return@let null
            val spec = readSpec(shot)
            val ir = readPromptIr(shot)
            val promptText = ir?.let { VeoFlowDialect.render(it) }
            val compileError = if (ir == null) {
                val gate = WorkflowRules.canCompilePrompt(shot)
                when {
                    !gate.allowed -> gate.blockers.joinToString("; ") { it.text() }
                    spec != null -> when (val r = PromptCompiler.compile(spec, project.globalStyle, project.characters, project.settings.ratio, shot.durationMs)) {
                        is CoreResult.Ok -> null
                        is CoreResult.Err -> r.error.message
                    }
                    else -> null
                }
            } else null
            ShotDetail(
                number = n,
                timecode = formatMs(shot.startMs),
                lyric = shot.lyricsMy,
                spec = spec,
                promptText = promptText,
                compileError = compileError,
                status = ShotStatus.of(project, shot, lookup),
                approvedPrompt = shot.approvals.prompt,
                acceptedVideoPath = shot.acceptedVideo?.let { resolveArtifactPath(it) },
                pass = shot.handoff.pass,
                versions = versions(shot),
                previewing = _preview.value[n]?.takeIf { it in shot.videos } ?: shot.acceptedVideo ?: shot.videos.lastOrNull(),
            )
        }
        return ShotsUiState(
            projectName = project.name,
            rows = rows,
            selected = sel,
            detail = detail,
            actionError = _actionError.value,
        )
    }

    companion object {
        fun formatMs(ms: Long): String {
            val s = ms / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
