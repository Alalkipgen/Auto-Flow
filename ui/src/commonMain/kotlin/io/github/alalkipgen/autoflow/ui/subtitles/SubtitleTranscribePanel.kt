package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.jobs.ToolAiJobCard
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.icons.AfIcon

/**
 * The Transcribe button of the source card (S-5b; Group 5 C 2): full width, secondary with a purple outline (`waveform`);
 * without sound it is off and a muted `speaker-slash` line says why.
 */
@Composable
internal fun TranscribeButton(model: SubtitleTranscribeModel, dir: String, hasSound: Boolean, idle: Boolean) {
    val s by model.state.collectAsState()
    val jobs by model.jobs.collectAsState()
    val free = model.jobOf(dir, jobs) == null && !(s.dir == dir && (s.planning || s.confirm != null))
    GlassButton(My.breakSyllables(tr(UiText.SUB_TRANSCRIBE)), { model.ask() }, Modifier.fillMaxWidth().testTag("subtitles-transcribe"),
        enabled = idle && hasSound && free, icon = AfIcons.Regular.Wave, accent = AutoFlowColors.Purple)
    if (!hasSound) GlassFact(AfIcons.Regular.SpeakerMuted, My.breakSyllables(tr(UiText.MOVIE_NO_SOUND_TO_TRANSCRIBE)), colour = AutoFlowColors.Muted,
        textModifier = Modifier.testTag("subtitles-transcribe-no-sound"))
}

/**
 * Above the source card and the editor (Group 5 D): finding the pauses = a small glass progress card; the confirm =
 * a glass dialog (🆕-4, [TranscribeDialog]); the job = the glass job card ([JobCard]); how it ended = a callout.
 */
@Composable
internal fun TranscribePanel(model: SubtitleTranscribeModel, dir: String) {
    val all by model.state.collectAsState()
    val jobs by model.jobs.collectAsState()
    val s = all.takeIf { it.dir == dir } ?: SubtitleTranscribeUi()
    val job = model.jobOf(dir, jobs)
    val c = s.confirm
    when {
        s.planning -> SubProgressCard(My.breakSyllables(tr(UiText.SUB_TRANSCRIBE_FINDING)), "subtitles-transcribe-finding")
        c != null -> TranscribeDialog(model, c)
        job != null -> JobCard(model, job)
        else -> s.notice?.let {
            GlassCallout(if (s.ok) AfIcons.Fill.CheckCircle else AfIcons.Fill.Warn, My.breakSyllables(it.text(LocalUiLanguage.current)),
                if (s.ok) AutoFlowColors.Green else AutoFlowColors.Amber, textModifier = Modifier.testTag("subtitles-transcribe-notice"))
        }
    }
}

/**
 * Something running with no progress to show (Group 5 C 3 busy, D 1 finding the pauses): a glass card tinted Cyan — a 30 dp
 * `hourglass` tile, the [text] 14 sp bold (tagged [tag]) — and the indeterminate glass bar.
 */
@Composable
internal fun SubProgressCard(text: String, tag: String, modifier: Modifier = Modifier) {
    GlassCard(modifier, tint = AutoFlowColors.Cyan, tintAlpha = .16f, gap = 12.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftTile(AfIcons.Fill.Hourglass, AutoFlowColors.Cyan, Modifier.testTag("card-tile"), size = 30.dp)
            AfText(text, Modifier.weight(1f).testTag(tag), type = AfType.Button, weight = FontWeight.Bold)
        }
        GlassProgress(null)
    }
}

/**
 * 🆕-4 (sample 07a / 07c): the Transcribe confirm as a glass dialog — Pink `waveform` tile + SUB_TRANSCRIBE, the facts 14 sp
 * bold, today 12 sp muted, the spoken language as glass filter chips, the again warning as an amber callout, Back + Start.
 * Outside the list, so it stays put while the page scrolls; dismissing it = Back (nothing is sent).
 */
@Composable
private fun TranscribeDialog(model: SubtitleTranscribeModel, c: SubtitleTranscribeConfirm) {
    GlassDialog({ model.dismiss() }, AfIcons.Fill.Wave, GlassColors.RecapPink, My.breakSyllables(tr(UiText.SUB_TRANSCRIBE)),
        Modifier.testTag("subtitles-transcribe-confirm")) {
        AfText(My.breakSyllables(tr(UiText.SUB_TRANSCRIBE_CONFIRM, "minutes" to maxOf(1L, (c.audioMs + 30_000) / 60_000), "requests" to c.requests)),
            Modifier.testTag("subtitles-transcribe-facts"), type = AfType.Button, weight = FontWeight.Bold)
        AfText(My.breakSyllables(tr(UiText.TRANSCRIBE_USED_TODAY, "toString" to (c.usedToday?.toString() ?: "…"))),
            Modifier.testTag("subtitles-transcribe-today"), muted = true, size = 12.sp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText(My.breakSyllables(tr(UiText.SUB_TRANSCRIBE_LANG)), muted = true, type = AfType.Caption)
            WrapRow {
                model.languages.forEach { l ->
                    val label = if (l == VideoTranscriber.AUTO) My.breakSyllables(tr(UiText.SUB_TRANSCRIBE_AUTO)) else l.uppercase()
                    GlassFilterChip(label, c.lang == l, { model.setLanguage(l) }, Modifier.testTag("subtitles-transcribe-lang-$l"),
                        leading = if (c.lang == l) ({ ink -> AfIcon(AfIcons.Regular.Check, tint = ink, size = 16.dp) }) else null)
                }
            }
        }
        if (c.again) GlassCallout(AfIcons.Fill.Warn, My.breakSyllables(tr(UiText.SUB_TRANSCRIBE_AGAIN)), AutoFlowColors.Amber,
            textModifier = Modifier.testTag("subtitles-transcribe-again"))
        GlassDialogButtons(GlassDialogAction(My.breakSyllables(tr(UiText.BACK)), { model.dismiss() }, icon = AfIcons.Regular.Back, tag = "subtitles-transcribe-back"),
            GlassDialogAction(My.breakSyllables(tr(UiText.START)), { model.start() }, ButtonKind.Primary, AfIcons.Fill.Play, tag = "subtitles-transcribe-start"))
    }
}

/**
 * The Transcribe job (Group 5 D 3, the Group 1 Jobs card look): Pink `waveform` tile + the job, its status pill and bar, then
 * running = the chunk line; paused = an amber `pause` callout; stopped = a red `x-circle` callout — the job's error in the UI
 * language (§8.1, [jobErrorText]). Running → Pause (SUB_PAUSE, the verb, §8.4) + Cancel job; paused / stopped → Resume N
 * remaining + Cancel job.
 */
@Composable
private fun JobCard(model: SubtitleTranscribeModel, job: AiJob) {
    val n = maxOf(job.initialItemCount ?: 0, job.items.size)
    val done = n - job.items.size
    val language = LocalUiLanguage.current
    var pausing by remember(job.id, job.state) { mutableStateOf(false) }
    ToolAiJobCard(job, Modifier.testTag("subtitles-transcribe-job"), tile = CardTile(AfIcons.Fill.Wave, GlassColors.RecapPink)) {
        val error = jobErrorText(job.error, language)
        val text = when (job.state) {
            AiJobState.PAUSED -> tr(UiText.SUB_TRANSCRIBE_PAUSED, "done" to done, "n" to n) + (error?.let { " $it" } ?: "")
            AiJobState.FAILED -> tr(UiText.SUB_TRANSCRIBE_STOPPED, "detail" to (error ?: tr(UiText.TRANSCRIBE_STOPPED)), "done" to done, "n" to n)
            else -> tr(UiText.SUB_TRANSCRIBE_PROGRESS, "k" to minOf(done + 1, n), "n" to n)
        }
        when (job.state) {
            AiJobState.PAUSED -> GlassCallout(AfIcons.Fill.Pause, My.breakSyllables(text), AutoFlowColors.Amber,
                textModifier = Modifier.testTag("subtitles-transcribe-progress"))
            AiJobState.FAILED -> GlassCallout(AfIcons.Fill.XCircle, My.breakSyllables(text), AutoFlowColors.Failed,
                textModifier = Modifier.testTag("subtitles-transcribe-progress"))
            else -> AfText(My.breakSyllables(text), Modifier.testTag("subtitles-transcribe-progress"))
        }
        WrapRow {
            if (job.state == AiJobState.PAUSED || job.state == AiJobState.FAILED) {
                GlassButton(My.breakSyllables(tr(UiText.V3_RESUME_REMAINING, "n" to job.items.size)), { model.resume(job) },
                    Modifier.testTag("subtitles-transcribe-resume"), kind = ButtonKind.Primary, icon = AfIcons.Fill.Play)
            } else {
                GlassButton(My.breakSyllables(tr(UiText.SUB_PAUSE)), { pausing = true; model.pause(job) }, Modifier.testTag("subtitles-transcribe-pause"),
                    enabled = !pausing, icon = AfIcons.Fill.Pause)
            }
            GlassButton(My.breakSyllables(tr(UiText.CANCEL_JOB)), { model.cancel(job) }, Modifier.testTag("subtitles-transcribe-cancel"),
                kind = ButtonKind.Danger, icon = AfIcons.Regular.Close)
        }
    }
}
