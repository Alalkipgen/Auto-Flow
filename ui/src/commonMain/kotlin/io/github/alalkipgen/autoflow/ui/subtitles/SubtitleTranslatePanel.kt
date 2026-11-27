package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.jobs.ToolAiJobCard
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ai.subtitles.SubtitleTranslator
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassShapes

/**
 * Above the editor's lines (S-6b; Group 5 E): the confirm = a glass dialog (🆕-4, [TranslateDialog]), the job = the glass
 * job card ([TranslateJobCard]; Cancel job is the sticky's), how it ended = a callout (+ Retry when lines are left).
 */
@Composable
internal fun TranslatePanel(model: SubtitleTranslateModel, dir: String) {
    val all by model.state.collectAsState()
    val jobs by model.jobs.collectAsState()
    val s = all.takeIf { it.dir == dir } ?: SubtitleTranslateUi()
    val job = model.jobOf(dir, jobs)
    val c = s.confirm
    when {
        job != null -> TranslateJobCard(model, job)
        c != null -> TranslateDialog(model, c)
        else -> s.notice?.let {
            // E 4: done green `check-circle`; not all / nothing to translate / cancelled amber `warning` (+ Retry, small primary).
            val green = s.ok && it.key != UiText.SUB_TRANSLATE_NOTHING // "every line already has Burmese" is amber (sample 08 piece 4)
            GlassCallout(if (green) AfIcons.Fill.CheckCircle else AfIcons.Fill.Warn, My.breakSyllables(it.text(LocalUiLanguage.current)),
                if (green) AutoFlowColors.Green else AutoFlowColors.Amber, textModifier = Modifier.testTag("subtitles-translate-notice"))
            if (s.retry) GlassButton(My.breakSyllables(tr(UiText.RETRY)), { model.ask() }, Modifier.testTag("subtitles-translate-retry"),
                kind = ButtonKind.Primary, icon = AfIcons.Regular.Redo, small = true)
        }
    }
}

/**
 * 🆕-4 (sample 07b): the Translate confirm as a glass dialog — Purple `translate` tile (§8.5; Spark stays the sticky
 * button's) + SUB_TRANSLATE, the facts bold, only-new muted, the style as glass filter chips, the names as a grid (the
 * From / Burmese labels once, then rows `[field | field | ×]`), Add a name, a divider, the replace switch, Back + Start
 * (off when no line would be sent). It scrolls inside when tall; dismissing it = Back (nothing is sent).
 */
@Composable
private fun TranslateDialog(model: SubtitleTranslateModel, c: SubtitleTranslateConfirm) {
    GlassDialog({ model.dismiss() }, AfIcons.Fill.Translate, AutoFlowColors.Purple, My.breakSyllables(tr(UiText.SUB_TRANSLATE)),
        Modifier.testTag("subtitles-translate-confirm")) {
        AfText(My.breakSyllables(tr(UiText.SUB_TRANSLATE_CONFIRM, "lines" to c.lines, "requests" to c.requests)),
            Modifier.testTag("subtitles-translate-facts"), type = AfType.Button, weight = FontWeight.Bold)
        AfText(My.breakSyllables(tr(UiText.SUB_TRANSLATE_ONLY_NEW)), muted = true)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText(My.breakSyllables(tr(UiText.SUB_TRANSLATE_STYLE)), muted = true, type = AfType.Caption)
            WrapRow {
                listOf(SubtitleTranslator.STYLE_SPOKEN to UiText.SUB_STYLE_SPOKEN, SubtitleTranslator.STYLE_FORMAL to UiText.SUB_STYLE_FORMAL).forEach { (v, l) ->
                    GlassFilterChip(My.breakSyllables(tr(l)), c.style == v, { model.setStyle(v) }, Modifier.testTag("subtitles-translate-style-$v"),
                        leading = if (c.style == v) ({ ink -> AfIcon(AfIcons.Regular.Check, tint = ink, size = 16.dp) }) else null)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText(My.breakSyllables(tr(UiText.SUB_TRANSLATE_NAMES)), muted = true, type = AfType.Caption)
            if (c.names.isNotEmpty()) NameRow({ NameLabel(UiText.SUB_NAME_FROM, it) }, { NameLabel(UiText.SUB_NAME_MY, it) }) {
                Spacer(Modifier.width(GlassShapes.IconButtonHeader))
            }
            c.names.forEachIndexed { i, n ->
                NameRow({ m ->
                    AfTextField(n.from, { model.setName(i, n.copy(from = it)) }, tr(UiText.SUB_NAME_FROM), m.testTag("subtitles-translate-name-from-$i"),
                        mono = false, glass = true, showPlaceholder = false)
                }, { m ->
                    AfTextField(n.my, { model.setName(i, n.copy(my = it)) }, tr(UiText.SUB_NAME_MY), m.testTag("subtitles-translate-name-my-$i"),
                        mono = false, glass = true, showPlaceholder = false)
                }) {
                    GlassIconButton(AfIcons.Regular.Close, tr(UiText.SUB_NAME_REMOVE), { model.removeName(i) },
                        Modifier.testTag("subtitles-translate-name-remove-$i"), size = GlassShapes.IconButtonHeader)
                }
            }
            GlassButton(My.breakSyllables(tr(UiText.SUB_NAME_ADD)), { model.addName() }, Modifier.testTag("subtitles-translate-name-add"),
                icon = AfIcons.Regular.Plus, small = true)
        }
        GlassHairline()
        GlassSwitchRow(My.breakSyllables(tr(UiText.SUB_TRANSLATE_REPLACE)), c.replaceEdited, { model.setReplaceEdited(it) },
            Modifier.testTag("subtitles-translate-replace").semantics { stateDescription = if (c.replaceEdited) "on" else "off" })
        GlassDialogButtons(GlassDialogAction(My.breakSyllables(tr(UiText.BACK)), { model.dismiss() }, icon = AfIcons.Regular.Back, tag = "subtitles-translate-back"),
            GlassDialogAction(My.breakSyllables(tr(UiText.START)), { model.start() }, ButtonKind.Primary, AfIcons.Fill.Play, enabled = c.lines > 0,
                tag = "subtitles-translate-start"))
    }
}

/** One row of the names grid: two equal fields (or their labels) and the 40 dp remove button (or its room). */
@Composable
private fun NameRow(from: @Composable (Modifier) -> Unit, my: @Composable (Modifier) -> Unit, end: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        from(Modifier.weight(1f)); my(Modifier.weight(1f)); end()
    }
}

@Composable
private fun NameLabel(text: UiText, modifier: Modifier = Modifier) =
    AfText(My.breakSyllables(tr(text)), modifier, color = AutoFlowColors.Muted, size = 11.5.sp)

/**
 * The Translate job (Group 5 E 2, the Group 1 Jobs card look): Purple `translate` tile + the job, its pill and bar; running =
 * the batch line; paused = an amber `pause` callout; stopped = a red `x-circle` callout — the job's error in the UI language
 * (§8.1, [jobErrorText]); no error = no double space (§8.2). Paused / stopped → Resume N remaining (Cancel job stays in the sticky).
 */
@Composable
private fun TranslateJobCard(model: SubtitleTranslateModel, job: AiJob) {
    val n = maxOf(job.initialItemCount ?: 0, job.items.size)
    val done = n - job.items.size
    val language = LocalUiLanguage.current
    ToolAiJobCard(job, Modifier.testTag("subtitles-translate-job"), tile = CardTile(AfIcons.Fill.Translate, AutoFlowColors.Purple)) {
        val error = jobErrorText(job.error, language)
        val text = when (job.state) {
            AiJobState.PAUSED -> tr(UiText.SUB_TRANSLATE_PAUSED, "done" to done, "n" to n) + (error?.let { " $it" } ?: "")
            AiJobState.FAILED -> tr(UiText.SUB_TRANSLATE_STOPPED, "detail" to (error ?: ""), "done" to done, "n" to n).collapseSpaces()
            else -> tr(UiText.SUB_TRANSLATE_PROGRESS, "k" to minOf(done + 1, n), "n" to n)
        }
        when (job.state) {
            AiJobState.PAUSED -> GlassCallout(AfIcons.Fill.Pause, My.breakSyllables(text), AutoFlowColors.Amber,
                textModifier = Modifier.testTag("subtitles-translate-progress"))
            AiJobState.FAILED -> GlassCallout(AfIcons.Fill.XCircle, My.breakSyllables(text), AutoFlowColors.Failed,
                textModifier = Modifier.testTag("subtitles-translate-progress"))
            else -> AfText(My.breakSyllables(text), Modifier.testTag("subtitles-translate-progress"))
        }
        if (job.state == AiJobState.PAUSED || job.state == AiJobState.FAILED) {
            GlassButton(My.breakSyllables(tr(UiText.V3_RESUME_REMAINING, "n" to job.items.size)), { model.resume(job) },
                Modifier.testTag("subtitles-translate-resume"), kind = ButtonKind.Primary, icon = AfIcons.Fill.Play)
        }
    }
}

/** §8.2: an empty `{detail}` leaves "Stopped:  6 of 13…" — one space, and none at the ends. */
internal fun String.collapseSpaces(): String = replace(Regex(" {2,}"), " ").trim()
