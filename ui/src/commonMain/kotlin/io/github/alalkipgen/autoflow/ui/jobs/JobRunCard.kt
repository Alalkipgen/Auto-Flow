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
    actions: @Composable ColumnScope.() -> Unit = {}) {
    val timing = if (row.timedRun && row.status == StudioStatus.RUNNING) {
        val active by AiRunTiming.active.collectAsState()
        val now = row.jobId?.let { active[it] }
        val tick by produceState(0, now) { while (now != null) { delay(500); value++ } }
        val language = LocalUiLanguage.current
        remember(tick, now, language) { liveLabel(now, language) }
    } else null
    val detail = listOfNotNull(row.title.takeIf { row.projectTitle != null }, row.detail?.takeIf { it != row.projectTitle && !row.failed }, timing)
        .joinToString(" · ")
    JobCard(row.projectTitle ?: row.title, row.status, My.breakSyllables(detail), modifier,
        progress = row.progress?.takeIf { row.running && it.isFinite() && it in 0.0..1.0 }?.toFloat(),
        indeterminate = row.indeterminate, headerAction = headerAction, actions = actions)
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
    JobRunCard(row.copy(projectTitle = null), modifier.testTag("gemini-project-run"), headerAction = {
        AfButton(tr(UiText.CANCEL), { jobs.cancelRun(run.id) }, kind = ButtonKind.Danger)
    })
}

@Composable
internal fun ProjectAiJobCard(job: AiJob, onCancel: () -> Unit, modifier: Modifier = Modifier,
    cancelModifier: Modifier = Modifier, onResume: (() -> Unit)? = null) {
    val row = jobRows(null, null, null, listOf(job), LocalUiLanguage.current).single()
    JobRunCard(row.copy(projectTitle = null), modifier, headerAction = if (row.canCancel) ({
        AfButton(tr(UiText.CANCEL), onCancel, cancelModifier, kind = ButtonKind.Danger)
    }) else null) {
        if (row.failed && job.error != null) AfText(job.error.orEmpty(), color = io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors.Failed)
        row.resumeCount?.let { n -> onResume?.let { AfButton(tr(UiText.V3_RESUME_REMAINING, "n" to n), it) } }
    }
}
