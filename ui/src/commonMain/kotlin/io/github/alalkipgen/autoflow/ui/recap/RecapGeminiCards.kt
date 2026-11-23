package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.jobs.JobRunCard
import io.github.alalkipgen.autoflow.ui.jobs.ProjectAiJobCard
import io.github.alalkipgen.autoflow.ui.jobs.jobRows
import io.github.alalkipgen.autoflow.ui.jobs.recapVoiceRow

internal val AiJob.active get() = state == AiJobState.QUEUED || state == AiJobState.RUNNING
private val pinned @Composable get() = Modifier.fillMaxWidth().padding(horizontal = screenPadding)

@Composable
internal fun RecapForegroundCards(model: RecapModel, state: RecapUiState) {
    val language = LocalUiLanguage.current
    state.scriptRunId?.let { id ->
        jobRows(state, null, null, emptyList(), language).firstOrNull { it.jobId == id }?.let { row ->
            JobRunCard(row.copy(projectTitle = null, detail = null), pinned.testTag("recap-script-run"), headerAction = {
                AfButton(tr(UiText.CANCEL), { model.cancelScriptRun(id) }, kind = ButtonKind.Danger)
            })
        }
    }
    state.voiceRun?.let { run ->
        JobRunCard(recapVoiceRow(run, language).copy(projectTitle = null), pinned.testTag("recap-voice-run"), headerAction = {
            AfButton(tr(UiText.CANCEL), { model.cancelForegroundVoice(run.id) },
                Modifier.testTag("recap-voice-run-cancel"), kind = ButtonKind.Danger)
        })
    }
}

@Composable
internal fun recapSpeakAllJob(model: RecapModel, state: RecapUiState): AiJob? {
    val jobs by model.runnerJobs.collectAsState()
    val matching = jobs.filter { it.project == state.projectDir && it.kind == AiJobKind.TTS_ALL }
    return matching.firstOrNull { it.active } ?: matching.firstOrNull { it.state == AiJobState.PAUSED || it.state == AiJobState.FAILED }
}

@Composable
internal fun RecapSpeakAllCard(model: RecapModel, job: AiJob, pin: Boolean) {
    ProjectAiJobCard(job, { model.cancelVoiceRun(job.id) },
        (if (pin) pinned else Modifier).testTag("recap-speak-all-run"), onResume = { model.resumeVoiceRun(job.id) })
}

/** The Transcribe job's card; [glass] = the Studio glass job card with the Transcribe tile (Group 2 D 19 · 9). */
@Composable
internal fun RecapTranscribeRun(model: RecapModel, job: AiJob, pin: Boolean = false, glass: Boolean = false) {
    ProjectAiJobCard(job, { model.cancelTranscribeRun(job.id) }, (if (pin) pinned else Modifier).testTag("transcribe-progress"),
        Modifier.testTag("transcribe-cancel"), tile = if (glass) RecapTiles.Transcribe else null)
}