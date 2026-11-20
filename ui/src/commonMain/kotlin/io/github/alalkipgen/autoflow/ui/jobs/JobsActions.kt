package io.github.alalkipgen.autoflow.ui.jobs

import androidx.compose.runtime.*
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.export.ExportModel
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.media.MediaTasks
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.recap.RecapStep
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import kotlinx.coroutines.*

enum class JobFileAction(val label: UiText) { OPEN(UiText.PLAY), SHARE(UiText.SHARE), GALLERY(UiText.SAVE_GALLERY) }

/** UI dispatch only. Re-read live identities before invoking the unchanged runner/model/platform ports. */
class JobsActions(
    private val scope: CoroutineScope,
    private val recap: RecapModel? = null,
    private val render: ExportModel? = null,
    private val storyboard: StoryboardJobs? = null,
    private val runner: AiJobRunner? = null,
    private val fs: FileSystem? = null,
    private val share: FileShare? = null,
    private val renderHistory: RenderHistory? = null,
    private val tasks: MediaTasks? = null,
) {
    var notice by mutableStateOf<UiNotice?>(null); private set
    var noticeTarget by mutableStateOf<String?>(null); private set
    var saving by mutableStateOf(false); private set
    private var pending by mutableStateOf(setOf<String>())
    private fun ai(row: JobRow) = runner?.history?.value?.firstOrNull { it.id == row.aiJobId && it.project == row.projectDir }
    private fun run(row: JobRow) = render?.state?.value?.run?.takeIf { it.id == row.jobId && it.projectDir == row.projectDir }
    private fun task(row: JobRow) = row.jobId?.let { tasks?.run(it) }?.takeIf { it.projectDir == row.projectDir }
    private fun doneOutput(row: JobRow) =
        ((if (row.source == JobSource.MEDIA) task(row)?.state else run(row)?.state) as? RenderState.Done)?.output
    fun canResume(row: JobRow) = !row.projectDeleted && row.aiJobId !in pending && ai(row)?.canResume() == true
    fun canCancel(row: JobRow): Boolean = when (row.source) {
        JobSource.AI -> ai(row)?.state in listOf(AiJobState.QUEUED, AiJobState.RUNNING)
        JobSource.RECAP -> recap?.state?.value?.let { it.projectDir == row.projectDir && (it.scriptRunId ?: it.job) == row.jobId && it.job == RecapModel.WRITING } == true
        JobSource.RECAP_VOICE -> recap?.canCancelForegroundVoice(row.jobId) == true
        JobSource.RENDER -> run(row)?.state.let { it == RenderState.Queued || it is RenderState.Running }
        JobSource.STORYBOARD -> storyboard?.matches(row.jobId) == true
        JobSource.MEDIA -> task(row)?.state?.finished == false
    }
    fun cancel(row: JobRow) {
        if (!canCancel(row)) return
        dispatch(row) { when (row.source) {
            JobSource.AI -> runner?.cancel(requireNotNull(row.aiJobId))
            JobSource.RECAP -> recap?.cancelScriptRun(row.jobId)
            JobSource.RECAP_VOICE -> recap?.cancelForegroundVoice(row.jobId)
            JobSource.RENDER -> render?.cancel()
            JobSource.STORYBOARD -> storyboard?.cancelRun(row.jobId)
            JobSource.MEDIA -> tasks?.cancel(requireNotNull(row.jobId))
        } }
    }
    fun resume(row: JobRow) {
        if (!canResume(row)) return
        val id = requireNotNull(row.aiJobId)
        pending = pending + id
        dispatch(row) {
            try { ai(row)?.takeIf { it.canResume() }?.let { runner?.enqueue(it) } }
            finally { pending = pending - id }
        }
    }
    fun canRemoveFinished(row: JobRow): Boolean = row.finished && row.aiJobId !in pending && when (row.source) {
        JobSource.AI -> runner?.supportsHistoryRemoval == true && ai(row)?.state?.finished == true
        JobSource.RENDER -> run(row)?.state?.finished == true && renderHistory != null && row.identity !in renderHistory.removedIds.value
        else -> false
    }

    fun removeFinished(row: JobRow) {
        if (!canRemoveFinished(row)) return
        dispatch(row) {
            val removed = when (row.source) {
                JobSource.AI -> runner?.removeFinished(requireNotNull(row.aiJobId), requireNotNull(row.projectDir)) == true
                JobSource.RENDER -> if (run(row)?.state?.finished == true) {
                    withContext(Dispatchers.IO) { requireNotNull(renderHistory).remove(row.identity) }
                    true
                } else false
                else -> false
            }
            if (!removed) notice = UiNotice(UiText.JOB_HISTORY_CHANGED)
        }
    }
    fun hasOutput(row: JobRow): Boolean = share != null && row.output != null &&
        doneOutput(row) == row.output && fs?.exists(row.output) == true

    fun file(row: JobRow, action: JobFileAction, language: UiLanguage) {
        if (saving) return
        if (!hasOutput(row)) {
            noticeTarget = row.identity
            notice = UiNotice(UiText.V3_JOB_OUTPUT_UNAVAILABLE)
            return
        }
        val output = row.output ?: return
        val galleryName = run(row)?.let { ExportModel.galleryName(row.projectTitle ?: "AutoFlow", it) } ?: output.substringAfterLast('/')
        saving = true
        dispatch(row) {
            try {
                val result = when (action) {
                    JobFileAction.OPEN -> share!!.open(output, ExportModel.MP4)
                    JobFileAction.SHARE -> share!!.share(output, ExportModel.MP4, UiText.EXPORT_SHARE_VIDEO.text(language))
                    JobFileAction.GALLERY -> share!!.saveToGallery(output, ExportModel.MP4, galleryName)
                }
                notice = when (result) {
                    is PlatformResult.Ok -> if (action == JobFileAction.GALLERY) UiNotice(UiText.EXPORT_GALLERY_SAVED, "path" to result.value) else null
                    is PlatformResult.Failed -> UiNotice(UiText.RESULT_DETAIL, "detail" to result.reason)
                    is PlatformResult.Unsupported -> UiNotice(UiText.RESULT_DETAIL, "detail" to result.reason)
                }
            } finally { saving = false }
        }
    }
    private fun dispatch(row: JobRow, block: suspend () -> Unit) {
        notice = null
        noticeTarget = row.identity
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = UiNotice(UiText.RESULT_DETAIL, "detail" to (e.message ?: e::class.simpleName)) }
        }
    }
}

/** A row without a project identity opens the chooser, never the unrelated currently open project. */
fun openJobProject(row: JobRow, dashboard: DashboardModel, current: CurrentProject): V2Nav? {
    if (row.projectDeleted) return null
    val dir = row.projectDir ?: return V2Nav(StudioV2Dest.PROJECTS)
    if (current.state.value?.dir != dir && !dashboard.open(dir)) return null
    if (row.projectIdentity != null && current.state.value?.project?.id != row.projectIdentity) return null
    val screen = when (row.source) {
        JobSource.RENDER -> "Export"
        JobSource.MEDIA -> row.projectScreen ?: "Export"
        JobSource.STORYBOARD -> row.projectScreen ?: SHOT_SHEET
        JobSource.AI -> when (row.aiKind) {
            AiJobKind.PUBLISH_KIT -> "Export"
            AiJobKind.MEDIA_TRANSCRIBE, AiJobKind.SUB_TRANSLATE -> SUBTITLES_SCREEN
            else -> "Recap"
        }
        JobSource.RECAP, JobSource.RECAP_VOICE -> "Recap"
    }
    val step = when {
        screen == "Export" -> RecapStep.STYLE
        row.aiKind == AiJobKind.TTS_ALL || row.source == JobSource.RECAP_VOICE -> RecapStep.VOICE
        row.aiKind == AiJobKind.SCRIPT || (row.source == JobSource.RECAP && row.jobId == RecapModel.WRITING) -> RecapStep.STORY
        else -> RecapStep.SOURCE
    }
    return V2Nav(StudioV2Dest.PROJECTS, screen, recapStep = step)
}