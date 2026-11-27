package io.github.alalkipgen.autoflow.ui.jobs

import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.alalkipgen.autoflow.ui.components.JobCard
import io.github.alalkipgen.autoflow.ui.components.CardRemoval
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.render.MediaTaskJob
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.ui.media.MediaTaskRun
import io.github.alalkipgen.autoflow.ui.media.MediaTasks
import io.github.alalkipgen.autoflow.ui.components.StudioStatus
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.export.ExportModel
import io.github.alalkipgen.autoflow.ui.export.ExportRun
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.recap.RecapUiState
import io.github.alalkipgen.autoflow.ui.recap.RecapVoiceRun
import io.github.alalkipgen.autoflow.ui.storyboard.JobState
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.getKoin

/** Where a job row came from; also where its "Open" button goes. */
enum class JobSource { RECAP, RECAP_VOICE, RENDER, STORYBOARD, AI, MEDIA }

/** One row of the Jobs screen (5A-5). [progress] 0…1 when known; [running] = still working (counts in the badge). */
data class JobRow(
    val source: JobSource,
    val title: String,
    val detail: String? = null,
    val progress: Double? = null,
    val running: Boolean = true,
    val failed: Boolean = false,
    val canCancel: Boolean = false,
    /** [AiJob.id] for AI runner rows. */
    val aiJobId: String? = null,
    val status: StudioStatus = if (failed) StudioStatus.FAILED else if (running) StudioStatus.RUNNING else StudioStatus.DONE,
    val projectDir: String? = null,
    val projectTitle: String? = null,
    val jobId: String? = null,
    val output: String? = null,
    val resumeCount: Int? = null,
    val aiKind: AiJobKind? = null,
    val timedRun: Boolean = false,
    val indeterminate: Boolean = false,
    val projectDeleted: Boolean = false,
    val projectIdentity: String? = null,
    val projectScreen: String? = null,
)

enum class JobGroup(val label: UiText) {
    RUNNING(UiText.JOB_RUNNING), NEEDS_YOU(UiText.V3_JOBS_NEEDS_YOU), FINISHED(UiText.V3_JOBS_FINISHED)
}

val JobRow.group: JobGroup get() = when (status) {
    StudioStatus.FAILED, StudioStatus.PAUSED -> JobGroup.NEEDS_YOU
    StudioStatus.QUEUED, StudioStatus.RUNNING -> JobGroup.RUNNING
    else -> JobGroup.FINISHED
}

internal fun AiJob.canResume() = kind in listOf(AiJobKind.TTS_ALL, AiJobKind.TRANSCRIBE, AiJobKind.MEDIA_TRANSCRIBE, AiJobKind.SUB_TRANSLATE, AiJobKind.TEXT_TTS, AiJobKind.DUB_TTS) &&
    state in listOf(AiJobState.PAUSED, AiJobState.FAILED) && items.isNotEmpty()

internal val JobRow.identity get() = "${source.name}:${projectDir.orEmpty()}:${jobId.orEmpty()}"
internal val JobRow.finished get() = status == StudioStatus.DONE || status == StudioStatus.FAILED || status == StudioStatus.CANCELLED
internal fun List<JobRow>.withoutRemovedRenders(removed: Set<String>) =
    filterNot { it.source == JobSource.RENDER && it.finished && it.identity in removed }

/**
 * Every job the app runs, in one list: Movie Recap steps (scenes, script, voice), the render on the Export screen,
 * the Music Video storyboard job and the desktop AI runner's jobs. Finished renders and failures stay listed until the
 * next one starts, so the owner sees how it ended.
 */
fun jobRows(recap: RecapUiState?, render: ExportRun?, storyboard: JobState?, ai: List<AiJob>, language: UiLanguage = UiLanguage.ENGLISH,
    projectNames: Map<String, String> = emptyMap(),
    voice: List<RecapVoiceRun> = listOfNotNull(recap?.voiceRun),
    tasks: List<MediaTaskRun> = emptyList(),
): List<JobRow> = buildList {
    recap?.takeIf { it.runnerJobId == null }?.job?.let { label ->
        val script = recap.job == RecapModel.WRITING
        add(JobRow(JobSource.RECAP, if (script) aiKindLabel(AiJobKind.SCRIPT, language) else "Movie Recap — $label",
            projectName(recap.projectDir), recap.progress, canCancel = script, projectDir = recap.projectDir,
            jobId = recap.scriptRunId ?: label, aiKind = AiJobKind.SCRIPT.takeIf { script }, timedRun = script, indeterminate = script))
    }
    render?.let { run ->
        val kind = if (run.quality == Quality.PREVIEW) UiText.JOB_PREVIEW.text(language) else UiText.EXPORT.text(language)
        val name = projectName(run.projectDir)
        val row = when (val s = run.state) {
            RenderState.Queued -> JobRow(JobSource.RENDER, UiText.JOB_RENDER_QUEUED.text(language, "kind" to kind), name, canCancel = true, status = StudioStatus.QUEUED)
            is RenderState.Running -> JobRow(JobSource.RENDER, "Render — $kind", name, s.fraction, canCancel = true)
            is RenderState.Done -> JobRow(JobSource.RENDER, UiText.JOB_RENDER_DONE.text(language, "kind" to kind), name, 1.0, running = false, output = s.output)
            is RenderState.Failed -> JobRow(JobSource.RENDER, UiText.JOB_RENDER_FAILED.text(language, "kind" to kind), s.reason, running = false, failed = true)
            RenderState.Cancelled -> JobRow(JobSource.RENDER, UiText.JOB_RENDER_CANCELLED.text(language, "kind" to kind), name, running = false, status = StudioStatus.CANCELLED)
        }
        add(row.copy(projectDir = run.projectDir, jobId = run.id))
    }
    storyboard?.let { musicGeminiRow(it, language) }?.let(::add)
    tasks.forEach { add(mediaTaskRow(it, language, projectName(it.projectDir))) }
    voice.forEach { add(recapVoiceRow(it, language)) }
    ai.forEach { job ->
        val active = job.state == AiJobState.RUNNING || job.state == AiJobState.QUEUED
        val total = job.initialItemCount?.takeIf { it > 0 }
        val completed = total?.let { (it - job.items.size).coerceIn(0, it) }
        val clips = job.savedClips
        val transcribe = job.kind == AiJobKind.TRANSCRIBE || job.kind == AiJobKind.MEDIA_TRANSCRIBE || job.kind == AiJobKind.SUB_TRANSLATE
        val multi = transcribe && total != null && total > 1
        val timed = transcribe || job.kind == AiJobKind.TTS_ALL || job.kind == AiJobKind.TEXT_TTS || job.kind == AiJobKind.DUB_TTS
        val detail = listOfNotNull(
            projectName(job.project),
            when {
                clips != null && total != null -> UiText.JOB_SAVED_CLIPS.text(language, "done" to clips, "total" to total)
                multi && completed != null && active -> UiText.JOB_REQUEST_UNIT.text(language, "n" to minOf(completed + 1, total), "total" to total)
                job.kind != AiJobKind.SCRIPT && !(active && transcribe) ->
                    UiText.JOB_ITEMS_LEFT.text(language, "size" to job.items.size)
                else -> null
            },
            job.error,
        ).joinToString(" · ")
        // Voice jobs speak one item per request, so their bar is the items done (F-2), like a multi-part transcription.
        val voiceItems = (job.kind == AiJobKind.TEXT_TTS || job.kind == AiJobKind.DUB_TTS) && total != null && total > 1
        val progress = total?.let { n -> (clips ?: completed?.takeIf { multi || voiceItems })?.toDouble()?.div(n) }
        add(
            JobRow(
                JobSource.AI, "${aiKindLabel(job.kind, language)} — ${aiStateLabel(job.state, language)}", detail,
                progress = progress, running = active, failed = job.state == AiJobState.FAILED, canCancel = active, aiJobId = job.id,
                timedRun = timed, indeterminate = timed && progress == null && job.state == AiJobState.RUNNING, projectIdentity = job.projectIdentity,
                projectDir = job.project, jobId = job.id, resumeCount = job.items.size.takeIf { job.canResume() }, aiKind = job.kind,
                status = when (job.state) {
                    AiJobState.QUEUED -> StudioStatus.QUEUED; AiJobState.RUNNING -> StudioStatus.RUNNING
                    AiJobState.PAUSED -> StudioStatus.PAUSED; AiJobState.FAILED -> StudioStatus.FAILED
                    AiJobState.DONE -> StudioStatus.DONE; AiJobState.CANCELLED -> StudioStatus.CANCELLED
                },
            ),
        )
    }
}.map { row -> row.copy(projectTitle = row.projectDir?.let { projectNames[it] ?: row.projectTitle ?: projectName(it) }) }

internal fun recapVoiceRow(run: RecapVoiceRun, language: UiLanguage) = JobRow(
    JobSource.RECAP_VOICE, run.label.text(language), canCancel = true, jobId = run.id, projectDir = run.dir,
    projectIdentity = run.projectId, projectTitle = run.name, timedRun = true, indeterminate = true,
)

internal fun musicGeminiRow(state: JobState, language: UiLanguage): JobRow? {
    val running = state.running ?: return null
    val run = state.gemini ?: return JobRow(JobSource.STORYBOARD, state.notice?.text(language) ?: "Storyboard — $running",
        canCancel = true, jobId = running)
    return JobRow(JobSource.STORYBOARD, state.notice?.text(language) ?: running, run.stage?.text(language),
        canCancel = true, jobId = run.id, projectDir = run.dir, projectTitle = run.name, projectIdentity = run.projectId,
        projectScreen = run.screen, timedRun = true, indeterminate = true)
}

/** Burmese name of an AI job kind (5B-13); the saved kind stays the enum. */
internal fun aiKindLabel(kind: AiJobKind, language: UiLanguage = UiLanguage.ENGLISH): String = when (kind) {
    AiJobKind.TTS_ALL -> UiText.JOB_TTS_ALL.text(language)
    AiJobKind.TRANSCRIBE -> UiText.JOB_TRANSCRIBE.text(language)
    AiJobKind.SCRIPT -> UiText.JOB_SCRIPT.text(language)
    AiJobKind.MEDIA_TRANSCRIBE -> UiText.JOB_MEDIA_TRANSCRIBE.text(language)
    AiJobKind.SUB_TRANSLATE -> UiText.JOB_SUB_TRANSLATE.text(language)
    AiJobKind.TEXT_TTS -> UiText.JOB_TEXT_TTS.text(language)
    AiJobKind.DUB_TTS -> UiText.JOB_DUB_TTS.text(language)
    AiJobKind.QUICK_RECAP -> UiText.JOB_QUICK_RECAP.text(language)
    AiJobKind.PUBLISH_KIT -> "Publish Kit"
}

/** Burmese job state (5B-13). */
internal fun aiStateLabel(state: AiJobState, language: UiLanguage = UiLanguage.ENGLISH): String = when (state) {
    AiJobState.QUEUED -> UiText.JOB_QUEUED.text(language)
    AiJobState.RUNNING -> UiText.JOB_RUNNING.text(language)
    AiJobState.PAUSED -> UiText.JOB_PAUSED.text(language)
    AiJobState.DONE -> UiText.JOB_DONE.text(language)
    AiJobState.FAILED -> UiText.JOB_FAILED.text(language)
    AiJobState.CANCELLED -> UiText.JOB_CANCELLED.text(language)
}

/** Negative = Needs-you count (paused/failed), else positive = running/queued count, else none. */
fun jobsBadgeValue(rows: List<JobRow>): Int {
    val attention = rows.count { it.group == JobGroup.NEEDS_YOU }
    val running = rows.count { it.group == JobGroup.RUNNING }
    return when {
        attention > 0 -> -attention
        running > 0 -> running
        else -> 0
    }
}

/** Badge from the AI runner's jobs alone. */
@kotlin.jvm.JvmName("aiJobsBadgeValue")
fun jobsBadgeValue(jobs: List<AiJob>): Int = jobsBadgeValue(jobRows(null, null, null, jobs))

/** A media task (§7): the same Queued / Done / Failed / Cancelled wording as a render, named by its kind. */
private fun mediaTaskRow(run: MediaTaskRun, language: UiLanguage, name: String?): JobRow {
    val kind = when (run.kind) {
        MediaTaskJob.SUBTITLE_BURN -> UiText.SUB_EXPORT_VIDEO
        io.github.alalkipgen.autoflow.media.audio.VoiceJoin.VOICE_M4A -> UiText.VA_SAVE_M4A
        io.github.alalkipgen.autoflow.media.audio.CleanAudio.CLEAN_AUDIO -> UiText.VA_CLEAN
        io.github.alalkipgen.autoflow.media.audio.VideoSound.SOUND_ONLY -> UiText.VA_SOUND_ONLY
        io.github.alalkipgen.autoflow.media.audio.VideoSound.REPLACE -> UiText.VA_REPLACE_SOUND
        io.github.alalkipgen.autoflow.media.audio.VideoSound.ADD -> UiText.VA_ADD_ON_TOP
        io.github.alalkipgen.autoflow.media.audio.DubMixer.STEM, io.github.alalkipgen.autoflow.media.audio.DubMixer.VOICE -> UiText.DUB_EXPORT_MIXING
        io.github.alalkipgen.autoflow.media.audio.DubMixer.EXPORT -> UiText.DUB_EXPORT_MAKING
        else -> UiText.EXPORT
    }.text(language)
    val row = when (val s = run.state) {
        RenderState.Queued -> JobRow(JobSource.MEDIA, UiText.JOB_RENDER_QUEUED.text(language, "kind" to kind), name, canCancel = true, status = StudioStatus.QUEUED)
        is RenderState.Running -> JobRow(JobSource.MEDIA, kind, name, s.fraction, canCancel = true)
        is RenderState.Done -> JobRow(JobSource.MEDIA, UiText.JOB_RENDER_DONE.text(language, "kind" to kind), name, 1.0, running = false, output = s.output)
        is RenderState.Failed -> JobRow(JobSource.MEDIA, UiText.JOB_RENDER_FAILED.text(language, "kind" to kind), s.reason, running = false, failed = true)
        RenderState.Cancelled -> JobRow(JobSource.MEDIA, UiText.JOB_RENDER_CANCELLED.text(language, "kind" to kind), name, running = false, status = StudioStatus.CANCELLED)
    }
    return row.copy(projectDir = run.projectDir, jobId = run.id, projectScreen = run.screen)
}

private fun projectName(dir: String?): String? =
    dir?.replace('\\', '/')?.trimEnd('/')?.substringAfterLast('/')?.removeSuffix(".autoflow")?.takeIf { it.isNotBlank() }

/** The live rows from every job source bound in Koin. */
@Composable
fun rememberJobRows(): List<JobRow> {
    val koin = getKoin()
    val recapModel = remember(koin) { koin.getOrNull<RecapModel>() }
    val exportModel = remember(koin) { koin.getOrNull<ExportModel>() }
    val storyboardJobs = remember(koin) { koin.getOrNull<StoryboardJobs>() }
    val runner = remember(koin) { koin.getOrNull<AiJobRunner>() }
    val current = remember(koin) { koin.getOrNull<CurrentProject>() }
    val index = remember(koin) { koin.getOrNull<ProjectIndex>() }
    val renderHistory = remember(koin) { koin.getOrNull<RenderHistory>() }
    val mediaTasks = remember(koin) { koin.getOrNull<MediaTasks>() }
    val tasks by (mediaTasks?.runs?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
    val recap by (recapModel?.state?.collectAsState() ?: remember { mutableStateOf(null) })
    val voice by (recapModel?.foregroundVoiceRuns?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
    val export by (exportModel?.state?.collectAsState() ?: remember { mutableStateOf(null) })
    val storyboard by (storyboardJobs?.state?.collectAsState() ?: remember { mutableStateOf(null) })
    val ai by (runner?.history?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
    val hidden by (renderHistory?.removedIds?.collectAsState() ?: remember { mutableStateOf(emptySet()) })
    val open by (current?.state?.collectAsState() ?: remember { mutableStateOf(null) })
    val entries by (index?.entries?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
    val names = entries.associate { it.path to it.name } + listOfNotNull(open?.let { it.dir to it.project.name }).toMap()
    return jobRows(recap, export?.run, storyboard, ai, LocalUiLanguage.current, names, voice, tasks).withoutRemovedRenders(hidden).map { row ->
        if (row.projectDir?.let { index?.isDeleted(it, row.projectIdentity) } == true) row.copy(projectDeleted = true) else row
    }
}

@Composable
internal fun rememberJobsActions(scope: CoroutineScope): JobsActions {
    val koin = getKoin()
    return remember(koin, scope) {
        JobsActions(scope, koin.getOrNull(), koin.getOrNull(), koin.getOrNull(), koin.getOrNull(), koin.getOrNull(), koin.getOrNull(), koin.getOrNull(),
            tasks = koin.getOrNull())
    }
}

/**
 * Studio v2 Jobs screen (5A-5, DESIGN.md §7 "Jobs"): every running job with its project, progress, Cancel, and
 * "Open" to the screen it belongs to ([onOpen]).
 */
@Composable
fun JobsScreen(
    rows: List<JobRow>,
    scope: CoroutineScope,
    onOpen: (JobRow) -> Unit,
    modifier: Modifier = Modifier,
    actions: JobsActions? = null,
    notice: String? = null,
    noticeRow: JobRow? = null,
) {
    val live = actions ?: rememberJobsActions(scope)
    val language = LocalUiLanguage.current
    Column(
        modifier.fillMaxSize().background(AutoFlowColors.Background).testTag("jobs-content").verticalScroll(rememberScrollState()).padding(screenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AfText(tr(UiText.JOBS), size = 20.sp, bold = true)
        notice?.takeIf { noticeRow == null }?.let { AfText(it, Modifier.testTag("jobs-notice"), color = AutoFlowColors.Failed) }
        if (rows.isEmpty()) {
            AfText(
                tr(UiText.JOBS_EMPTY_FIRST) +
                    tr(UiText.JOBS_EMPTY_LAST),
                Modifier.testTag("jobs-empty"),
                muted = true,
            )
        }
        JobGroup.entries.forEach { group ->
            val members = rows.filter { it.group == group }
            if (members.isNotEmpty()) {
                AfText(tr(group.label), Modifier.testTag("jobs-group-${group.name}"), size = 18.sp, bold = true)
                members.forEach { row -> key(row.identity) {
                    val key = row.aiJobId ?: row.jobId?.takeIf { row.source == JobSource.RECAP_VOICE } ?: row.source.name.lowercase()
                    val progress = row.progress?.takeIf { row.running && it.isFinite() && it in 0.0..1.0 }
                    val card: @Composable (Modifier) -> Unit = { context ->
                        Column(Modifier.testTag("job-${row.source.name.lowercase()}")) {
                        JobRunCard(row, Modifier.testTag("job-card-$key").then(context)) {
                            if (row.failed) row.detail?.let { AfText(it, color = AutoFlowColors.Failed) }
                            if (!row.timedRun) progress?.let { AfText("${(it * 100).toInt()} %") }
                            WrapRow {
                                if (row.projectDeleted) AfText(My.breakSyllables(tr(UiText.JOB_PROJECT_DELETED)), muted = true)
                                else AfButton(tr(UiText.OPEN), { onOpen(row) }, Modifier.testTag("job-open-$key"))
                                row.resumeCount?.takeIf { live.canResume(row) }?.let { n -> AfButton(
                                    if (row.failed) tr(UiText.RETRY) else tr(UiText.V3_RESUME_REMAINING, "n" to n),
                                    { live.resume(row) }, Modifier.testTag("job-resume-$key"), kind = ButtonKind.Primary)
                                }
                                if (row.canCancel && live.canCancel(row)) AfButton(tr(UiText.CANCEL), { live.cancel(row) },
                                    Modifier.testTag("job-cancel-$key"), kind = ButtonKind.Danger)
                                if (live.hasOutput(row)) JobFileAction.entries.forEach { action ->
                                    AfButton(tr(action.label), { live.file(row, action, language) },
                                        Modifier.testTag("job-file-${action.name}-$key"), enabled = !live.saving)
                                }
                            }
                            val external = noticeRow?.identity == row.identity
                            val feedback = if (external) notice else live.notice?.takeIf { live.noticeTarget == row.identity }?.text(language)
                            feedback?.let { AfText(it, Modifier.testTag("job-notice-$key"),
                                color = if (!external && live.notice?.key == UiText.EXPORT_GALLERY_SAVED) AutoFlowColors.Green else AutoFlowColors.Failed) }
                        }
                        }
                    }
                    if (live.canRemoveFinished(row)) CardRemoval("job-card-$key", UiText.JOB_DELETE_HISTORY,
                        tr(UiText.JOB_DELETE_HISTORY_CONFIRM), UiText.JOB_DELETE_HISTORY_KEEPS_FILES,
                        { { live.removeFinished(row) } }, card = card)
                    else card(Modifier)
                } }
            }
        }
    }
}
