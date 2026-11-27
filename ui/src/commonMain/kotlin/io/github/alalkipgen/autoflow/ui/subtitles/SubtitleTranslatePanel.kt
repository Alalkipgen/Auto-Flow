package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.ui.jobs.ToolAiJobCard
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.alalkipgen.autoflow.ai.subtitles.SubtitleTranslator
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** Above the editor's lines (S-6b): the confirm card, the job (progress · Resume · Cancel), the outcome with Retry. */
@Composable
internal fun TranslatePanel(model: SubtitleTranslateModel, dir: String) {
    val all by model.state.collectAsState()
    val jobs by model.jobs.collectAsState()
    val s = all.takeIf { it.dir == dir } ?: SubtitleTranslateUi()
    val job = model.jobOf(dir, jobs)
    val c = s.confirm
    when {
        job != null -> TranslateJobCard(model, job)
        c != null -> AfCard(My.breakSyllables(tr(UiText.SUB_TRANSLATE)), Modifier.testTag("subtitles-translate-confirm")) {
            AfText(My.breakSyllables(tr(UiText.SUB_TRANSLATE_CONFIRM, "lines" to c.lines, "requests" to c.requests)),
                Modifier.testTag("subtitles-translate-facts"), bold = true)
            AfText(My.breakSyllables(tr(UiText.SUB_TRANSLATE_ONLY_NEW)), muted = true)
            AfText(My.breakSyllables(tr(UiText.SUB_TRANSLATE_STYLE)))
            WrapRow {
                listOf(SubtitleTranslator.STYLE_SPOKEN to UiText.SUB_STYLE_SPOKEN, SubtitleTranslator.STYLE_FORMAL to UiText.SUB_STYLE_FORMAL).forEach { (v, l) ->
                    AfChip(My.breakSyllables(tr(l)), c.style == v, { model.setStyle(v) }, Modifier.testTag("subtitles-translate-style-$v"))
                }
            }
            AfText(My.breakSyllables(tr(UiText.SUB_TRANSLATE_NAMES)))
            c.names.forEachIndexed { i, n ->
                WrapRow {
                    AfTextField(n.from, { model.setName(i, n.copy(from = it)) }, tr(UiText.SUB_NAME_FROM), Modifier.testTag("subtitles-translate-name-from-$i"), mono = false)
                    AfTextField(n.my, { model.setName(i, n.copy(my = it)) }, tr(UiText.SUB_NAME_MY), Modifier.testTag("subtitles-translate-name-my-$i"), mono = false)
                    AfButton(My.breakSyllables(tr(UiText.SUB_NAME_REMOVE)), { model.removeName(i) }, Modifier.testTag("subtitles-translate-name-remove-$i"))
                }
            }
            AfButton(My.breakSyllables(tr(UiText.SUB_NAME_ADD)), { model.addName() }, Modifier.testTag("subtitles-translate-name-add"))
            AfSwitch(My.breakSyllables(tr(UiText.SUB_TRANSLATE_REPLACE)), c.replaceEdited, { model.setReplaceEdited(it) },
                Modifier.testTag("subtitles-translate-replace"))
            WrapRow {
                AfButton(My.breakSyllables(tr(UiText.START)), { model.start() }, Modifier.testTag("subtitles-translate-start"),
                    kind = ButtonKind.Primary, enabled = c.lines > 0)
                AfButton(My.breakSyllables(tr(UiText.BACK)), { model.dismiss() }, Modifier.testTag("subtitles-translate-back"))
            }
        }
        else -> s.notice?.let {
            AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("subtitles-translate-notice"),
                color = if (s.ok) AutoFlowColors.Green else AutoFlowColors.Amber)
            if (s.retry) AfButton(My.breakSyllables(tr(UiText.RETRY)), { model.ask() }, Modifier.testTag("subtitles-translate-retry"), kind = ButtonKind.Primary)
        }
    }
}

@Composable
private fun TranslateJobCard(model: SubtitleTranslateModel, job: AiJob) {
    val n = maxOf(job.initialItemCount ?: 0, job.items.size)
    val done = n - job.items.size
    ToolAiJobCard(job, Modifier.testTag("subtitles-translate-job")) {
        val text = when (job.state) {
            AiJobState.PAUSED -> tr(UiText.SUB_TRANSLATE_PAUSED, "done" to done, "n" to n) + (job.error?.let { " $it" } ?: "")
            AiJobState.FAILED -> tr(UiText.SUB_TRANSLATE_STOPPED, "detail" to (job.error ?: ""), "done" to done, "n" to n)
            else -> tr(UiText.SUB_TRANSLATE_PROGRESS, "k" to minOf(done + 1, n), "n" to n)
        }
        AfText(My.breakSyllables(text), Modifier.testTag("subtitles-translate-progress"),
            color = if (job.state == AiJobState.FAILED) AutoFlowColors.Failed else AutoFlowColors.Text)
        if (job.state == AiJobState.PAUSED || job.state == AiJobState.FAILED) {
            AfButton(My.breakSyllables(tr(UiText.V3_RESUME_REMAINING, "n" to job.items.size)), { model.resume(job) },
                Modifier.testTag("subtitles-translate-resume"), kind = ButtonKind.Primary)
        }
    }
}
