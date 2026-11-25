package io.github.alalkipgen.autoflow.ui.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.media.render.MediaTaskJob
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.export.ExportModel
import io.github.alalkipgen.autoflow.ui.export.ExportRun
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.media.MediaTaskRun
import io.github.alalkipgen.autoflow.ui.media.MediaTasks
import io.github.alalkipgen.autoflow.ui.media.MediaThumbnails
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.recap.RecapUiState
import io.github.alalkipgen.autoflow.ui.recap.RecapVoiceRun
import io.github.alalkipgen.autoflow.ui.storyboard.JobState
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.theme.*
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
    /** What the job does, for the card's detail line when the title is the project ("Dub voice", "Render · Export"). */
    val kindLabel: String? = null,
    /** The provider's error for an AI job (shown as stored, in red), apart from its progress [detail]. */
    val reason: String? = null,
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
        add(row.copy(projectDir = run.projectDir, jobId = run.id, kindLabel = UiText.JOB_RENDER_KIND.text(language, "kind" to kind)))
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
        // The progress part only: the card names the project (title) and the kind, and shows the error on its own line.
        val detail = when {
            clips != null && total != null -> UiText.JOB_SAVED_CLIPS.text(language, "done" to clips, "total" to total)
            multi && completed != null && active -> UiText.JOB_REQUEST_UNIT.text(language, "n" to minOf(completed + 1, total), "total" to total)
            job.kind != AiJobKind.SCRIPT && !(active && transcribe) ->
                UiText.JOB_ITEMS_LEFT.text(language, "size" to job.items.size)
            else -> null
        }
        // Voice jobs speak one item per request, so their bar is the items done (F-2), like a multi-part transcription.
        val voiceItems = (job.kind == AiJobKind.TEXT_TTS || job.kind == AiJobKind.DUB_TTS) && total != null && total > 1
        val progress = total?.let { n -> (clips ?: completed?.takeIf { multi || voiceItems })?.toDouble()?.div(n) }
        add(
            JobRow(
                JobSource.AI, "${aiKindLabel(job.kind, language)} — ${aiStateLabel(job.state, language)}", detail,
                progress = progress, running = active, failed = job.state == AiJobState.FAILED, canCancel = active, aiJobId = job.id,
                timedRun = timed, indeterminate = timed && progress == null && job.state == AiJobState.RUNNING, projectIdentity = job.projectIdentity,
                projectDir = job.project, jobId = job.id, resumeCount = job.items.size.takeIf { job.canResume() }, aiKind = job.kind,
                kindLabel = aiKindLabel(job.kind, language), reason = job.error?.takeIf { it.isNotBlank() },
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
    return row.copy(projectDir = run.projectDir, jobId = run.id, projectScreen = run.screen, kindLabel = kind)
}

/** A project folder's name (`<name>.autoflow`): what render and media rows name their project by. */
internal fun projectName(dir: String?): String? =
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
 * Studio v2 Jobs screen (5A-5; DESIGN.md §9.7 panel 3): title with the group counts, then Running (thumbnail, title,
 * kind · detail · live timing, progress), Needs you (warning / error cards with the plain reason; Resume remaining /
 * Retry) and Finished (thumbnail, file actions where the platform has them). Every job keeps its "Open" to the screen
 * it belongs to ([onOpen]) and Cancel where the job supports it; finished jobs keep long-press / right-click → remove
 * from history. [thumbSource] reads a project's recorded source for the thumbnail (read-only). One column ≤ 720 dp.
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
    thumbSource: (suspend (dir: String) -> MediaThumbnails.Source?)? = null,
) {
    val live = actions ?: rememberJobsActions(scope)
    val language = LocalUiLanguage.current
    // A row's buttons and its feedback: under the card on phones, in the detail pane on the desktop.
    val buttons: @Composable ColumnScope.(JobRow) -> Unit = { row ->
        val key = row.tagKey
        WrapRow {
            if (row.projectDeleted) AfText(My.breakSyllables(tr(UiText.JOB_PROJECT_DELETED)), muted = true)
            else GlassButton(tr(UiText.OPEN), { onOpen(row) }, Modifier.testTag("job-open-$key"), small = true)
            row.resumeCount?.takeIf { live.canResume(row) }?.let { n -> GlassButton(
                if (row.failed) tr(UiText.RETRY) else tr(UiText.V3_RESUME_REMAINING, "n" to n),
                { live.resume(row) }, Modifier.testTag("job-resume-$key"), kind = ButtonKind.Primary, small = true)
            }
            if (row.canCancel && live.canCancel(row)) GlassButton(tr(UiText.CANCEL), { live.cancel(row) },
                Modifier.testTag("job-cancel-$key"), kind = ButtonKind.Danger, small = true)
            if (live.hasOutput(row)) JobFileAction.entries.forEach { action ->
                GlassButton(tr(action.label), { live.file(row, action, language) },
                    Modifier.testTag("job-file-${action.name}-$key"), enabled = !live.saving,
                    icon = if (action == JobFileAction.OPEN) AfIcons.Fill.Play else null, small = true)
            }
        }
        val external = noticeRow?.identity == row.identity
        val feedback = if (external) notice else live.notice?.takeIf { live.noticeTarget == row.identity }?.text(language)
        // Saved to Gallery: green with the check-circle (the string has no "✓", Group 4 §4).
        feedback?.let { if (!external && live.notice?.key == UiText.EXPORT_GALLERY_SAVED)
            io.github.alalkipgen.autoflow.ui.components.GlassFact(AfIcons.Fill.CheckCircle, My.breakSyllables(it), colour = AutoFlowColors.Green,
                iconColour = AutoFlowColors.Green, textModifier = Modifier.testTag("job-notice-$key"))
            else AfText(My.breakSyllables(it), Modifier.testTag("job-notice-$key"), color = AutoFlowColors.Failed) }
    }
    // No background of its own: the shell's ambient shows through the glass cards (DESIGN.md §9.10).
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // Desktop (expanded Jobs width): list left, the selected job right; the top job is selected until a card is clicked.
        val wide = widthClass(maxWidth.value) == WidthClass.EXPANDED && rows.isNotEmpty()
        var picked by remember { mutableStateOf<String?>(null) }
        val selected = if (wide) rows.firstOrNull { it.identity == picked } ?: rows.sortedBy { it.group.ordinal }.first() else null
        val list = @Composable {
            GlassScrollColumn(Modifier.widthIn(max = AfSize.ContentMax).fillMaxSize().testTag("jobs-content"), PaddingValues(screenPadding),
                Arrangement.spacedBy(GlassShapes.SectionGap)) {
                Column(Modifier.testTag("jobs-header"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AfText(tr(UiText.JOBS), Modifier.testTag("jobs-heading"), type = AfType.ScreenTitle)
                    val counts = JobGroup.entries.mapNotNull { g -> rows.count { it.group == g }.takeIf { it > 0 }?.let { "${tr(g.label)} $it" } }
                    if (counts.isNotEmpty()) AfText(My.breakSyllables(counts.joinToString(" · ")), Modifier.testTag("jobs-counts"),
                        color = AutoFlowColors.Muted, type = AfType.Caption)
                }
                notice?.takeIf { noticeRow == null }?.let { AfText(My.breakSyllables(it), Modifier.testTag("jobs-notice"), color = AutoFlowColors.Failed) }
                if (rows.isEmpty()) EmptyState(AfIcons.Duotone.Jobs, My.breakSyllables(tr(UiText.JOBS_EMPTY_FIRST) + tr(UiText.JOBS_EMPTY_LAST)),
                    null, Modifier.testTag("jobs-empty"))
                JobGroup.entries.forEach { group ->
                    val members = rows.filter { it.group == group }
                    if (members.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(GlassShapes.BetweenCards)) {
                        AfText(My.breakSyllables(tr(group.label)), Modifier.testTag("jobs-group-${group.name}"), color = AutoFlowColors.Muted,
                            type = AfType.Caption)
                        members.forEach { row -> key(row.identity) {
                            val key = row.tagKey
                            val isSelected = selected?.identity == row.identity
                            val pick = { picked = row.identity }
                            val card: @Composable (Modifier) -> Unit = { context ->
                                Column(Modifier.testTag("job-${row.source.name.lowercase()}")) {
                                    JobItemCard(row, thumbSource, Modifier.testTag("job-card-$key").then(context), isSelected) {
                                        if (!wide) buttons(row)
                                    }
                                }
                            }
                            // Desktop: a click selects (and right-click / long-press still opens the history menu).
                            val select = if (wide) Modifier.semantics { this.selected = isSelected; onClick { pick(); true } } else Modifier
                            if (live.canRemoveFinished(row)) CardRemoval("job-card-$key", UiText.JOB_DELETE_HISTORY,
                                tr(UiText.JOB_DELETE_HISTORY_CONFIRM), UiText.JOB_DELETE_HISTORY_KEEPS_FILES,
                                { { live.removeFinished(row) } }, onTap = if (wide) pick else null, name = row.projectTitle ?: row.title,
                                caption = jobKind(row), leading = { JobLead(row, thumbSource) }) { card(it.then(select)) }
                            else card(if (wide) Modifier.pointerInput(key) { detectTapGestures(onTap = { pick() }) }.then(select) else Modifier)
                        } }
                    }
                }
            }
        }
        if (selected == null) list()
        else ListDetailPane(maxWidth.value, true, list, { JobDetail(selected, thumbSource) { buttons(selected) } })
    }
}

/**
 * The selected job on the desktop (list–detail), on a glass card: caption, 16:9 thumbnail (≤ [AfDesktop.DetailThumbMax]),
 * title, kind · detail · timing, the failure reason, status, progress and the row's buttons and feedback ([actions]).
 */
@Composable
private fun JobDetail(row: JobRow, thumbSource: (suspend (String) -> MediaThumbnails.Source?)?, actions: @Composable ColumnScope.() -> Unit) {
    val dir = row.projectDir?.takeIf { !row.projectDeleted }
    val source by produceState<MediaThumbnails.Source?>(null, dir, thumbSource) { value = dir?.let { thumbSource?.invoke(it) } }
    val progress = row.progress?.takeIf { row.running && it.isFinite() && it in 0.0..1.0 }
    val detail = jobDetail(row)
    val attention = row.attention
    Column(Modifier.fillMaxSize().testTag("jobs-detail").paneScroll(rememberScrollState())
        .padding(top = screenPadding, end = screenPadding, bottom = screenPadding)) {
        GlassCard(Modifier.fillMaxWidth().testTag("jobs-detail-card"), attention = attention, tint = attention?.fill,
            tintAlpha = GlassColors.TintJobs, gap = GlassShapes.JobCardGap) {
            AfText(My.breakSyllables(tr(UiText.JOBS_SELECTED)), color = AutoFlowColors.Muted, type = AfType.Caption)
            BoxWithConstraints {
                val w = minOf(maxWidth, AfDesktop.DetailThumbMax)
                MediaThumb(source, row.icon(), Modifier.glassThumb(), size = DpSize(w, w * 9f / 16f), durationMs = source?.durationMs)
            }
            AfText(My.breakSyllables(row.projectTitle ?: row.title), Modifier.testTag("jobs-detail-title"), type = AfType.CardTitle)
            if (detail.isNotEmpty()) AfText(My.breakSyllables(detail), color = AutoFlowColors.Muted, type = AfType.Caption)
            row.redLine?.let { AfText(My.breakSyllables(it), Modifier.testTag("jobs-detail-reason"), color = AutoFlowColors.Failed) }
            StatusChip(row.status)
            if (progress != null || row.indeterminate) GlassProgress(progress?.toFloat())
            if (!row.timedRun) progress?.let { AfText("${(it * 100).toInt()} %", color = AutoFlowColors.Muted, type = AfType.Caption) }
            actions()
        }
    }
}

/** The key in a job's tags (`job-card-<key>`, `job-open-<key>`, …). */
private val JobRow.tagKey: String
    get() = aiJobId ?: jobId?.takeIf { source == JobSource.RECAP_VOICE } ?: source.name.lowercase()

/** Needs you: a warning (paused) or error (failed) card. */
private val JobRow.attention: Tone? get() = when { group != JobGroup.NEEDS_YOU -> null; failed -> Tone.Error; else -> Tone.Warning }

/**
 * A glass job card (panel 3; Home's Running too): Needs you = a warning (paused) or error (failed) bar, a soft 36 dp
 * tile in its tone and the card tinted in that tone; else the project's thumbnail 64 × 40 (real frame when cached,
 * else the job's type icon) and the card tinted with that frame. Title (the project, else the job; one line), kind ·
 * detail · live timing, the failure reason in red, the status chip, progress while it runs (percent when not timed),
 * then [actions].
 */
@Composable
internal fun JobItemCard(row: JobRow, thumbSource: (suspend (String) -> MediaThumbnails.Source?)?, modifier: Modifier = Modifier,
    selected: Boolean = false, actions: @Composable ColumnScope.() -> Unit = {}) {
    val attention = row.attention
    val dir = row.projectDir?.takeIf { !row.projectDeleted && attention == null }
    val source by produceState<MediaThumbnails.Source?>(null, dir, thumbSource) { value = dir?.let { thumbSource?.invoke(it) } }
    var art by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    val progress = row.progress?.takeIf { row.running && it.isFinite() && it in 0.0..1.0 }
    val detail = jobDetail(row)
    val thumbnail = art.takeIf { attention == null }
    val caption = glassCaptionColour(thumbnail)
    GlassCard(modifier, selected = selected, attention = attention, tint = attention?.fill, tintAlpha = GlassColors.TintJobs,
        thumbnail = thumbnail, gap = GlassShapes.JobCardGap) {
        Row(horizontalArrangement = Arrangement.spacedBy(AfSpace.M), verticalAlignment = Alignment.Top) {
            if (attention != null) SoftTile(if (row.failed) AfIcons.Fill.XCircle else AfIcons.Fill.Warn, attention.fill, size = AttentionTile)
            else MediaThumb(source, row.icon(), Modifier.glassThumb(), size = AfSize.ThumbCompact, onImage = { art = it })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AfText(row.projectTitle ?: row.title, type = AfType.RowTitle, maxLines = 1, ellipsis = true)
                if (detail.isNotEmpty()) AfText(My.breakSyllables(detail), color = caption, type = AfType.Caption)
                row.redLine?.let { AfText(My.breakSyllables(it), color = AutoFlowColors.Failed, type = AfType.Caption) }
                StatusChip(row.status, Modifier.padding(top = 2.dp))
            }
        }
        if (progress != null || row.indeterminate) GlassProgress(progress?.toFloat())
        if (!row.timedRun) progress?.let { AfText("${(it * 100).toInt()} %", color = caption, type = AfType.Caption) }
        actions()
    }
}

/** What a job does, under its name in the delete dialog: its kind ("Dub voice", "Render · Export"), else its group. */
@Composable
private fun jobKind(row: JobRow): String = (row.kindLabel ?: row.title).takeIf { row.projectTitle != null } ?: tr(row.group.label)

/**
 * A job in the delete dialog (Library follow-up README §3): its card's leading at 44 dp — the Needs-you tone tile, else
 * the project's thumbnail (16:10, 70 × 44; the job's type icon until a frame is cached).
 */
@Composable
private fun JobLead(row: JobRow, thumbSource: (suspend (String) -> MediaThumbnails.Source?)?) {
    val attention = row.attention
    if (attention != null) {
        return SoftTile(if (row.failed) AfIcons.Fill.XCircle else AfIcons.Fill.Warn, attention.fill, size = GlassShapes.DialogTile)
    }
    val dir = row.projectDir?.takeIf { !row.projectDeleted }
    val source by produceState<MediaThumbnails.Source?>(null, dir, thumbSource) { value = dir?.let { thumbSource?.invoke(it) } }
    MediaThumb(source, row.icon(), Modifier.glassThumb(), size = DialogThumb)
}

/** The job thumbnail in the delete dialog: [AfSize.ThumbCompact] (64 × 40) at 44 dp tall. */
private val DialogThumb = DpSize(70.dp, 44.dp)

/** The tone tile of a Needs-you job card (Exact UI v4). */
private val AttentionTile = 36.dp

/** The placeholder icon of a job's thumbnail: what it makes. */
private fun JobRow.icon(): ImageVector = when {
    source == JobSource.STORYBOARD -> AfIcons.Duotone.Music
    source == JobSource.RECAP_VOICE -> AfIcons.Duotone.Wave
    aiKind == AiJobKind.TTS_ALL || aiKind == AiJobKind.TEXT_TTS -> AfIcons.Duotone.Wave
    aiKind == AiJobKind.DUB_TTS -> AfIcons.Duotone.Dub
    aiKind == AiJobKind.TRANSCRIBE || aiKind == AiJobKind.MEDIA_TRANSCRIBE || aiKind == AiJobKind.SUB_TRANSLATE -> AfIcons.Duotone.Subtitles
    aiKind != null -> AfIcons.Duotone.Spark
    else -> AfIcons.Duotone.Film
}
