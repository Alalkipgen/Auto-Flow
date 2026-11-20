package io.github.alalkipgen.autoflow.ui.voice

import io.github.alalkipgen.autoflow.ui.jobs.ToolAiJobCard
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** Above the parts list (V-1e): the confirm card, the job (progress · Resume · Cancel), or how the last Speak ended. */
@Composable
internal fun SpeakPanel(model: VoiceSpeakModel, dir: String, job: AiJob?) {
    val all by model.state.collectAsState()
    val s = all.takeIf { it.dir == dir } ?: VoiceSpeakUi()
    val c = s.confirm
    when {
        job != null -> ToolAiJobCard(job, Modifier.testTag("voice-speak-job")) {
            val n = maxOf(job.initialItemCount ?: 0, job.items.size)
            val done = n - job.items.size
            val text = when (job.state) {
                AiJobState.PAUSED -> tr(UiText.VA_SPEAK_PAUSED, "done" to done, "n" to n) + (job.error?.let { " $it" } ?: "")
                AiJobState.FAILED -> tr(UiText.VA_SPEAK_STOPPED, "detail" to (job.error ?: ""), "done" to done, "n" to n)
                else -> tr(UiText.VA_SPEAK_PROGRESS, "k" to minOf(done + 1, n), "n" to n)
            }
            AfText(My.breakSyllables(text), Modifier.testTag("voice-speak-progress"),
                color = if (job.state == AiJobState.FAILED) AutoFlowColors.Failed else AutoFlowColors.Text)
            AfText(My.breakSyllables(tr(UiText.VA_EDIT_PAUSED)), muted = true)
            WrapRow {
                if (job.state == AiJobState.PAUSED || job.state == AiJobState.FAILED) AfButton(My.breakSyllables(tr(UiText.V3_RESUME_REMAINING,
                    "n" to job.items.size)), { model.resume(job) }, Modifier.testTag("voice-speak-resume"), kind = ButtonKind.Primary)
                AfButton(My.breakSyllables(tr(UiText.CANCEL)), { model.cancel(job) }, Modifier.testTag("voice-speak-cancel"))
            }
        }
        c != null -> AfCard(My.breakSyllables(tr(UiText.VA_SPEAK_PARTS, "n" to c.parts.size)), Modifier.testTag("voice-speak-confirm")) {
            AfText(My.breakSyllables(tr(UiText.VA_SPEAK_CONFIRM, "n" to c.parts.size, "voice" to "${c.lock.voice} · ${c.lock.model}",
                "minutes" to (c.parts.size + VoiceSpeakJob.RPM - 1) / VoiceSpeakJob.RPM)), Modifier.testTag("voice-speak-facts"), bold = true)
            WrapRow {
                AfButton(My.breakSyllables(tr(UiText.START)), { model.start() }, Modifier.testTag("voice-speak-start"), kind = ButtonKind.Primary)
                AfButton(My.breakSyllables(tr(UiText.BACK)), { model.dismiss() }, Modifier.testTag("voice-speak-back"))
            }
        }
        else -> s.notice?.let {
            AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("voice-speak-notice"),
                color = if (s.ok) AutoFlowColors.Green else AutoFlowColors.Amber)
        }
    }
}
