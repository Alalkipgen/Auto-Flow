package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.jobs.ToolAiJobCard
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** The Transcribe button of the source card (S-5b); without sound it says why and stays off. */
@Composable
internal fun TranscribeButton(model: SubtitleTranscribeModel, dir: String, hasSound: Boolean, idle: Boolean) {
    val s by model.state.collectAsState()
    val jobs by model.jobs.collectAsState()
    val free = model.jobOf(dir, jobs) == null && !(s.dir == dir && (s.planning || s.confirm != null))
    AfButton(My.breakSyllables(tr(UiText.SUB_TRANSCRIBE)), { model.ask() }, Modifier.fillMaxWidth().testTag("subtitles-transcribe"),
        enabled = idle && hasSound && free, icon = AfIcons.Regular.Wave)
    if (!hasSound) AfText(My.breakSyllables(tr(UiText.MOVIE_NO_SOUND_TO_TRANSCRIBE)), Modifier.testTag("subtitles-transcribe-no-sound"), muted = true)
}

/** Above the source card and the editor: finding pauses, the confirm card, the job (progress · Pause · Resume · Cancel), the outcome. */
@Composable
internal fun TranscribePanel(model: SubtitleTranscribeModel, dir: String) {
    val all by model.state.collectAsState()
    val jobs by model.jobs.collectAsState()
    val s = all.takeIf { it.dir == dir } ?: SubtitleTranscribeUi()
    val job = model.jobOf(dir, jobs)
    val c = s.confirm
    when {
        s.planning -> AfText(My.breakSyllables(tr(UiText.SUB_TRANSCRIBE_FINDING)), Modifier.testTag("subtitles-transcribe-finding"), muted = true)
        c != null -> AfCard(My.breakSyllables(tr(UiText.SUB_TRANSCRIBE)), Modifier.testTag("subtitles-transcribe-confirm"), icon = AfIcons.Regular.Wave) {
            AfText(My.breakSyllables(tr(UiText.SUB_TRANSCRIBE_CONFIRM, "minutes" to maxOf(1L, (c.audioMs + 30_000) / 60_000), "requests" to c.requests)),
                Modifier.testTag("subtitles-transcribe-facts"), bold = true)
            AfText(My.breakSyllables(tr(UiText.TRANSCRIBE_USED_TODAY, "toString" to (c.usedToday?.toString() ?: "…"))),
                Modifier.testTag("subtitles-transcribe-today"), muted = true)
            AfText(My.breakSyllables(tr(UiText.SUB_TRANSCRIBE_LANG)))
            WrapRow {
                model.languages.forEach { l ->
                    val label = if (l == VideoTranscriber.AUTO) My.breakSyllables(tr(UiText.SUB_TRANSCRIBE_AUTO)) else l.uppercase()
                    AfChip(label, c.lang == l, { model.setLanguage(l) }, Modifier.testTag("subtitles-transcribe-lang-$l"))
                }
            }
            if (c.again) AfText(My.breakSyllables(tr(UiText.SUB_TRANSCRIBE_AGAIN)), Modifier.testTag("subtitles-transcribe-again"), color = AutoFlowColors.Amber)
            WrapRow {
                AfButton(My.breakSyllables(tr(UiText.START)), { model.start() }, Modifier.testTag("subtitles-transcribe-start"), kind = ButtonKind.Primary,
                    icon = AfIcons.Fill.Play)
                AfButton(My.breakSyllables(tr(UiText.BACK)), { model.dismiss() }, Modifier.testTag("subtitles-transcribe-back"))
            }
        }
        job != null -> JobCard(model, job)
        else -> s.notice?.let {
            AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("subtitles-transcribe-notice"),
                color = if (s.ok) AutoFlowColors.Green else AutoFlowColors.Amber)
        }
    }
}

@Composable
private fun JobCard(model: SubtitleTranscribeModel, job: AiJob) {
    val n = maxOf(job.initialItemCount ?: 0, job.items.size)
    val done = n - job.items.size
    var pausing by remember(job.id, job.state) { mutableStateOf(false) }
    ToolAiJobCard(job, Modifier.testTag("subtitles-transcribe-job")) {
        val text = when (job.state) {
            AiJobState.PAUSED -> tr(UiText.SUB_TRANSCRIBE_PAUSED, "done" to done, "n" to n) +
                (job.error?.takeIf { it != VideoTranscriber.PAUSED }?.let { " $it" } ?: "")
            AiJobState.FAILED -> tr(UiText.SUB_TRANSCRIBE_STOPPED, "detail" to (job.error ?: tr(UiText.TRANSCRIBE_STOPPED)), "done" to done, "n" to n)
            else -> tr(UiText.SUB_TRANSCRIBE_PROGRESS, "k" to minOf(done + 1, n), "n" to n)
        }
        AfText(My.breakSyllables(text), Modifier.testTag("subtitles-transcribe-progress"),
            color = if (job.state == AiJobState.FAILED) AutoFlowColors.Failed else AutoFlowColors.Text)
        WrapRow {
            if (job.state == AiJobState.PAUSED || job.state == AiJobState.FAILED) {
                AfButton(My.breakSyllables(tr(UiText.V3_RESUME_REMAINING, "n" to job.items.size)), { model.resume(job) },
                    Modifier.testTag("subtitles-transcribe-resume"), kind = ButtonKind.Primary)
            } else {
                AfButton(My.breakSyllables(tr(UiText.PAUSE)), { pausing = true; model.pause(job) }, Modifier.testTag("subtitles-transcribe-pause"), enabled = !pausing,
                    icon = AfIcons.Fill.Pause)
            }
            AfButton(My.breakSyllables(tr(UiText.CANCEL_JOB)), { model.cancel(job) }, Modifier.testTag("subtitles-transcribe-cancel"), kind = ButtonKind.Danger)
        }
    }
}
