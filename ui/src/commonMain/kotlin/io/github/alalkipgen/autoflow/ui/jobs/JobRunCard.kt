package io.github.alalkipgen.autoflow.ui.jobs

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiRunTiming
import io.github.alalkipgen.autoflow.core.recap.AiTimingStage
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import kotlinx.coroutines.delay

@Composable
internal fun JobRunCard(row: JobRow, modifier: Modifier = Modifier, headerAction: (@Composable () -> Unit)? = null,
    tile: CardTile? = null, actions: @Composable ColumnScope.() -> Unit = {}) {
    JobCard(row.projectTitle ?: row.title, row.status, My.breakSyllables(jobDetail(row)), modifier,
        progress = row.progress?.takeIf { row.running && it.isFinite() && it in 0.0..1.0 }?.toFloat(),
        indeterminate = row.indeterminate, headerAction = headerAction, tile = tile, actions = actions)
}

/**
 * The job's second line: its kind (when the title is the project: [JobRow.kindLabel], else the job's own title), its
 * detail (not the red line, not the project again) and live timing.
 */
@Composable
internal fun jobDetail(row: JobRow): String {
    val timing = if (row.timedRun && row.status == StudioStatus.RUNNING) {
        val active by AiRunTiming.active.collectAsState()
        val now = row.jobId?.let { active[it] }
        val tick by produceState(0, now) { while (now != null) { delay(500); value++ } }
        val language = LocalUiLanguage.current
        remember(tick, now, language) { liveLabel(now, language) }
    } else null
    val red = row.redLine
    return listOfNotNull((row.kindLabel ?: row.title).takeIf { row.projectTitle != null },
        row.detail?.takeIf { it.isNotBlank() && it != row.projectTitle && it != projectName(row.projectDir) && it != red }, row.reason?.takeIf { red == null }, timing)
        .joinToString(" · ")
}

/**
 * The red line of a job card: a failed or paused job's provider error, else a failed job's detail (a render's reason).
 */
internal val JobRow.redLine: String? get() = when {
    reason != null && (failed || status == StudioStatus.PAUSED) -> reason
    failed -> detail?.takeIf { it.isNotBlank() }
    else -> null
}

private fun liveLabel(live: AiRunTiming.Live?, language: UiLanguage): String {
    val left = live?.retryUntil?.elapsedNow()?.inWholeMilliseconds?.let { -it }
    if (left != null && left > 0) return UiText.JOB_RATE_WAIT.text(language, "seconds" to ((left + 999) / 1000))
    val stage = when (live?.stage) {
        AiTimingStage.MEDIA_PREP -> UiText.TIMING_MEDIA
        AiTimingStage.PACER -> UiText.TIMING_PACER
        AiTimingStage.PARSE -> UiText.TIMING_PARSE
        AiTimingStage.RETRY_WAIT -> UiText.TIMING_RETRY
        AiTimingStage.SAVE -> UiText.SAVE
        AiTimingStage.UI_UPDATE -> UiText.TIMING_UI
        else -> UiText.JOB_WAITING_GEMINI
    }.text(language)
    val seconds = live?.started?.elapsedNow()?.inWholeSeconds ?: return stage
    return "$stage · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
internal fun MusicGeminiJobCard(jobs: StoryboardJobs, project: OpenProject?, modifier: Modifier = Modifier) {
    val job by jobs.state.collectAsState()
    val run = job.gemini?.takeIf { it.belongsTo(project) } ?: return
    val row = musicGeminiRow(job, LocalUiLanguage.current) ?: return
    // Group 3 A 4: the glass job card (sparkle tile in Purple, the cyan → purple bar) with the small danger Cancel (x).
    JobRunCard(row.copy(projectTitle = null), modifier.testTag("gemini-project-run"), headerAction = {
        GlassButton(tr(UiText.CANCEL), { jobs.cancelRun(run.id) }, kind = ButtonKind.Danger,
            icon = io.github.alalkipgen.autoflow.ui.icons.AfIcons.Regular.Close, small = true)
    }, tile = CardTile(io.github.alalkipgen.autoflow.ui.icons.AfIcons.Fill.Spark, io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors.Purple))
}

/**
 * A tool page's AI job (F-2: Subtitles Transcribe / Translate, Dubbing Voice, Voice & Audio Speak): the same card as
 * Movie Recap's runs — title with the state, status chip, progress bar, live timing and rate-limit wait — then the
 * tool's own [content] (its "line k of n" and Pause / Resume / Cancel).
 */
@Composable
internal fun ToolAiJobCard(job: AiJob, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val language = LocalUiLanguage.current
    val row = remember(job, language) { jobRows(null, null, null, listOf(job), language).single() }
    JobRunCard(row.copy(projectTitle = null, detail = null, reason = null), modifier, actions = content)
}

/** A tool page's direct AI request with no job (Dubbing Shorten): the same card, running, with live wait time. */
@Composable
internal fun ToolAiRequestCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit = {}) {
    val started = remember { kotlin.time.TimeSource.Monotonic.markNow() }
    val tick by produceState(0) { while (true) { delay(500); value++ } }
    val language = LocalUiLanguage.current
    val seconds = remember(tick) { started.elapsedNow().inWholeSeconds }
    JobCard(title, StudioStatus.RUNNING, "${UiText.JOB_WAITING_GEMINI.text(language)} · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
        modifier, indeterminate = true, actions = content)
}

/** A project's AI job; [tile] = the glass job card (Group 2 D §1) with the small danger Cancel (x) in its header. */
@Composable
internal fun ProjectAiJobCard(job: AiJob, onCancel: () -> Unit, modifier: Modifier = Modifier,
    cancelModifier: Modifier = Modifier, onResume: (() -> Unit)? = null, tile: CardTile? = null) {
    val row = jobRows(null, null, null, listOf(job), LocalUiLanguage.current).single()
    JobRunCard(row.copy(projectTitle = null), modifier, headerAction = if (row.canCancel) ({
        if (tile != null) GlassButton(tr(UiText.CANCEL), onCancel, cancelModifier, kind = ButtonKind.Danger,
            icon = io.github.alalkipgen.autoflow.ui.icons.AfIcons.Regular.Close, small = true)
        else AfButton(tr(UiText.CANCEL), onCancel, cancelModifier, kind = ButtonKind.Danger)
    }) else null, tile = tile) {
        if (row.failed && job.error != null) AfText(job.error.orEmpty(), color = io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors.Failed)
        row.resumeCount?.let { n -> onResume?.let { AfButton(tr(UiText.V3_RESUME_REMAINING, "n" to n), it) } }
    }
}
