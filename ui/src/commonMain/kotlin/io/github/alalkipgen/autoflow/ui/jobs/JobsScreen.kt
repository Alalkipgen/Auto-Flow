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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.alalkipgen.autoflow.media.render.RenderState
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
import io.github.alalkipgen.autoflow.ui.storyboard.JobState
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.getKoin

/** Where a job row came from; also where its "Open" button goes. */
enum class JobSource { RECAP, RENDER, STORYBOARD, AI }

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
)

/**
 * Every job the app runs, in one list: Movie Recap steps (scenes, script, voice), the render on the Export screen,
 * the Music Video storyboard job and the desktop AI runner's jobs. Finished renders and failures stay listed until the
 * next one starts, so the owner sees how it ended.
 */
fun jobRows(recap: RecapUiState?, render: ExportRun?, storyboard: JobState?, ai: List<AiJob>, language: UiLanguage = UiLanguage.ENGLISH): List<JobRow> = buildList {
    recap?.job?.let { label ->
        add(JobRow(JobSource.RECAP, "Movie Recap — $label", projectName(recap.projectDir), recap.progress, canCancel = recap.job == RecapModel.WRITING))
    }
    render?.let { run ->
        val kind = if (run.quality == Quality.PREVIEW) UiText.JOB_PREVIEW.text(language) else UiText.EXPORT.text(language)
        val name = projectName(run.projectDir)
        when (val s = run.state) {
            RenderState.Queued -> add(JobRow(JobSource.RENDER, UiText.JOB_RENDER_QUEUED.text(language, "kind" to (kind)), name, canCancel = true, status = StudioStatus.QUEUED))
            is RenderState.Running -> add(JobRow(JobSource.RENDER, "Render — $kind", name, s.fraction, canCancel = true))
            is RenderState.Done -> add(JobRow(JobSource.RENDER, UiText.JOB_RENDER_DONE.text(language, "kind" to (kind)), name, 1.0, running = false))
            is RenderState.Failed -> add(JobRow(JobSource.RENDER, UiText.JOB_RENDER_FAILED.text(language, "kind" to (kind)), s.reason, running = false, failed = true))
            RenderState.Cancelled -> add(JobRow(JobSource.RENDER, UiText.JOB_RENDER_CANCELLED.text(language, "kind" to (kind)), name, running = false))
        }
    }
    storyboard?.running?.let { add(JobRow(JobSource.STORYBOARD, "Storyboard — $it", canCancel = true)) }
    ai.forEach { job ->
        val active = job.state == AiJobState.RUNNING || job.state == AiJobState.QUEUED
        val detail = listOfNotNull(
            projectName(job.project),
            UiText.JOB_ITEMS_LEFT.text(language, "size" to (job.items.size)),
            job.error,
        ).joinToString(" · ")
        add(
            JobRow(
                JobSource.AI, "${aiKindLabel(job.kind, language)} — ${aiStateLabel(job.state, language)}", detail,
                running = active, failed = job.state == AiJobState.FAILED, canCancel = active, aiJobId = job.id,
                status = when (job.state) {
                    AiJobState.QUEUED -> StudioStatus.QUEUED; AiJobState.RUNNING -> StudioStatus.RUNNING
                    AiJobState.PAUSED -> StudioStatus.PAUSED; AiJobState.FAILED -> StudioStatus.FAILED
                    AiJobState.DONE -> StudioStatus.DONE; AiJobState.CANCELLED -> StudioStatus.CANCELLED
                },
            ),
        )
    }
}

/** Burmese name of an AI job kind (5B-13); the saved kind stays the enum. */
internal fun aiKindLabel(kind: AiJobKind, language: UiLanguage = UiLanguage.ENGLISH): String = when (kind) {
    AiJobKind.TTS_ALL -> UiText.JOB_TTS_ALL.text(language)
    AiJobKind.TRANSCRIBE -> UiText.JOB_TRANSCRIBE.text(language)
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

/** Badge for the Jobs tab: > 0 = jobs still running, < 0 = that many failed, 0 = none. */
fun jobsBadgeValue(rows: List<JobRow>): Int {
    val failed = rows.count { it.failed }
    val running = rows.count { it.running }
    return when {
        failed > 0 -> -failed
        running > 0 -> running
        else -> 0
    }
}

/** Badge from the AI runner's jobs alone. */
@kotlin.jvm.JvmName("aiJobsBadgeValue")
fun jobsBadgeValue(jobs: List<AiJob>): Int = jobsBadgeValue(jobRows(null, null, null, jobs))

private fun projectName(dir: String?): String? =
    dir?.trimEnd('/')?.substringAfterLast('/')?.removeSuffix(".autoflow")?.takeIf { it.isNotBlank() }

/** The live rows from every job source bound in Koin. */
@Composable
fun rememberJobRows(): List<JobRow> {
    val koin = getKoin()
    val recapModel = remember(koin) { koin.getOrNull<RecapModel>() }
    val exportModel = remember(koin) { koin.getOrNull<ExportModel>() }
    val storyboardJobs = remember(koin) { koin.getOrNull<StoryboardJobs>() }
    val runner = remember(koin) { koin.getOrNull<AiJobRunner>() }
    val recap by (recapModel?.state?.collectAsState() ?: remember { mutableStateOf(null) })
    val export by (exportModel?.state?.collectAsState() ?: remember { mutableStateOf(null) })
    val storyboard by (storyboardJobs?.state?.collectAsState() ?: remember { mutableStateOf(null) })
    val ai by (runner?.jobs?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
    return jobRows(recap, export?.run, storyboard, ai, LocalUiLanguage.current)
}

/**
 * Studio v2 Jobs screen (5A-5, DESIGN.md §7 "Jobs"): every running job with its project, progress, Cancel, and
 * "Open" to the screen it belongs to ([onOpen]).
 */
@Composable
fun JobsScreen(
    rows: List<JobRow>,
    scope: CoroutineScope,
    onOpen: (JobSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val koin = getKoin()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AfText(tr(UiText.JOBS), size = 20.sp, bold = true)
        if (rows.isEmpty()) {
            AfText(
                tr(UiText.JOBS_EMPTY_FIRST) +
                    tr(UiText.JOBS_EMPTY_LAST),
                Modifier.testTag("jobs-empty"),
                muted = true,
            )
        }
        rows.forEach { row ->
            AfCard(row.title, Modifier.testTag("job-${row.source.name.lowercase()}")) {
                row.detail?.let { AfText(it, color = if (row.failed) AutoFlowColors.Amber else AutoFlowColors.Muted, size = 12.sp) }
                row.progress?.takeIf { row.running }?.let { AfText("${(it * 100).toInt()} %", size = 12.sp) }
                WrapRow {
                    if (row.source != JobSource.AI) AfButton(tr(UiText.OPEN), { onOpen(row.source) }, kind = ButtonKind.Primary)
                    if (row.canCancel) {
                        AfButton(tr(UiText.CANCEL), {
                            when (row.source) {
                                JobSource.RECAP -> koin.getOrNull<RecapModel>()?.cancelScript()
                                JobSource.RENDER -> koin.getOrNull<ExportModel>()?.cancel()
                                JobSource.STORYBOARD -> koin.getOrNull<StoryboardJobs>()?.cancel()
                                JobSource.AI -> row.aiJobId?.let { id -> koin.getOrNull<AiJobRunner>()?.let { r -> scope.launch { r.cancel(id) } } }
                            }
                        }, kind = ButtonKind.Danger)
                    }
                }
            }
        }
    }
}
