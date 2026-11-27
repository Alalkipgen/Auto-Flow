package io.github.alalkipgen.autoflow.ui.voice

import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.jobs.ToolAiJobCard
import io.github.alalkipgen.autoflow.ui.jobs.jobErrorText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors

/**
 * Above the parts list (V-1e): the job (progress · Resume · Cancel), or how the last Speak ended; the confirm is a glass
 * dialog (🆕 VA-3, Group 6 E 1) over the screen. [s] = the screen's state (voice, tone, which parts are voiced) and
 * [edge] the Edge voices, so the dialog names the voice as the voice card does.
 */
@Composable
internal fun SpeakPanel(model: VoiceSpeakModel, dir: String, job: AiJob?, s: VoiceAudioUi? = null,
    edge: io.github.alalkipgen.autoflow.ai.config.EdgeTtsConfig? = null) {
    val all by model.state.collectAsState()
    val ui = all.takeIf { it.dir == dir } ?: VoiceSpeakUi()
    val c = ui.confirm
    val language = LocalUiLanguage.current
    if (c != null && job == null) SpeakDialog(model, c, s, edge)
    when {
        // E 2: the Group 1 job card (microphone in Pink, the state pill, the bar); paused = the reason on its own amber line,
        // stopped = a red callout; the engine's English error is said in the UI language (§8.2, jobErrorText).
        job != null -> ToolAiJobCard(job, Modifier.testTag("voice-speak-job"), tile = CardTile(AfIcons.Fill.Microphone, GlassColors.RecapPink)) {
            val n = maxOf(job.initialItemCount ?: 0, job.items.size)
            val done = n - job.items.size
            val reason = jobErrorText(job.error, language, s?.lock)
            when (job.state) {
                AiJobState.FAILED -> GlassCallout(AfIcons.Fill.XCircle, My.breakSyllables(tr(UiText.VA_SPEAK_STOPPED, "detail" to (reason ?: ""),
                    "done" to done, "n" to n)), AutoFlowColors.Failed, textModifier = Modifier.testTag("voice-speak-progress"))
                AiJobState.PAUSED -> {
                    AfText(My.breakSyllables(tr(UiText.VA_SPEAK_PAUSED, "done" to done, "n" to n)), Modifier.testTag("voice-speak-progress"), size = 15.sp)
                    reason?.let { IconLine(AfIcons.Regular.Hourglass, it, AutoFlowColors.Amber) }
                }
                else -> AfText(My.breakSyllables(tr(UiText.VA_SPEAK_PROGRESS, "k" to minOf(done + 1, n), "n" to n)), Modifier.testTag("voice-speak-progress"),
                    size = 15.sp)
            }
            IconLine(AfIcons.Regular.Info, tr(UiText.VA_EDIT_PAUSED), AutoFlowColors.Muted)
            WrapRow {
                if (job.state == AiJobState.PAUSED || job.state == AiJobState.FAILED) GlassButton(My.breakSyllables(tr(UiText.V3_RESUME_REMAINING,
                    "n" to job.items.size)), { model.resume(job) }, Modifier.testTag("voice-speak-resume"), kind = ButtonKind.Primary, icon = AfIcons.Fill.Play)
                GlassButton(My.breakSyllables(tr(UiText.CANCEL)), { model.cancel(job) }, Modifier.testTag("voice-speak-cancel"), kind = ButtonKind.Danger,
                    icon = AfIcons.Regular.Close)
            }
        }
        // E 3: how the last Speak ended = a callout (green when done, else amber).
        c == null -> ui.notice?.let {
            GlassCallout(if (ui.ok) AfIcons.Fill.CheckCircle else AfIcons.Fill.Warn, My.breakSyllables(it.text(language)),
                if (ui.ok) AutoFlowColors.Green else AutoFlowColors.Amber, textModifier = Modifier.testTag("voice-speak-notice"))
        }
    }
}

/** A 12 sp line with its icon (the paused reason, the editing hint). */
@Composable
private fun IconLine(icon: ImageVector, text: String, colour: Color) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AfIcon(icon, Modifier.padding(top = 2.dp), tint = colour, size = 15.dp)
        AfText(My.breakSyllables(text), Modifier.weight(1f), color = colour, size = 12.sp)
    }
}

/**
 * 🆕 VA-3: the Speak confirm as a glass dialog (Group 6 E 1; Dubbing's look): the microphone tile, "Speak 2 parts?" (one
 * voiced part again = "Speak this part again"), the facts — Edge says Edge requests (free), Gemini Gemini requests, both with
 * the voice as the card names it, never the raw model id (§8.1) — the voice, tone and parts rows, then Back · Start.
 */
@Composable
private fun SpeakDialog(model: VoiceSpeakModel, c: VoiceSpeakRequest, s: VoiceAudioUi?, edge: io.github.alalkipgen.autoflow.ai.config.EdgeTtsConfig?) {
    val n = c.parts.size
    val label = voiceLabel(c.lock, edge)
    val minutes = model.minutes(n, c.lock)
    val facts = if (c.lock.provider == EdgeVoices.PROVIDER) tr(UiText.V3_DUB_EDGE_CONFIRM, "n" to n, "voice" to label, "minutes" to minutes)
        else tr(UiText.VA_SPEAK_CONFIRM, "n" to n, "voice" to label, "minutes" to minutes)
    val redo = n == 1 && s?.rows?.firstOrNull { it.id == c.parts[0].id }?.voiced == true
    val title = if (redo) tr(UiText.VA_REDO_PART) else tr(UiText.VA_SPEAK_TITLE, "n" to n)
    val tone = TONES.firstOrNull { it.first == (c.tone ?: s?.tone) }?.second?.let { tr(it) }
    GlassDialog({ model.dismiss() }, AfIcons.Fill.Microphone, GlassColors.RecapPink, My.breakSyllables(title), Modifier.testTag("voice-speak-confirm")) {
        AfText(My.breakSyllables(facts), Modifier.testTag("voice-speak-facts"), size = 15.sp, bold = true)
        val shape = RoundedCornerShape(12.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(Color.White.copy(alpha = .04f)).border(1.dp, Color.White.copy(alpha = .08f), shape)
            .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Brush.linearGradient(listOf(GlassColors.RecapPink, AutoFlowColors.Purple))),
                    contentAlignment = Alignment.Center) { AfIcon(AfIcons.Fill.UserSound, tint = Color.White, size = 16.dp) }
                AfText(My.breakSyllables(label), Modifier.weight(1f), bold = true)
            }
            if (tone != null) FactRow(AfIcons.Regular.SlidersHorizontal, "${tr(UiText.VA_TONE)} · $tone")
            FactRow(AfIcons.Regular.ListNumbers, c.parts.joinToString(" · ") { "#" + it.id.removePrefix("p") })
        }
        GlassDialogButtons(GlassDialogAction(My.breakSyllables(tr(UiText.BACK)), { model.dismiss() }, icon = AfIcons.Regular.Back, tag = "voice-speak-back"),
            GlassDialogAction(My.breakSyllables(tr(UiText.START)), { model.start() }, ButtonKind.Primary, AfIcons.Fill.Play, tag = "voice-speak-start"))
    }
}

/** A 13 sp muted row with its icon in the dialog. */
@Composable
private fun FactRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AfIcon(icon, tint = AutoFlowColors.Muted, size = 16.dp)
        AfText(My.breakSyllables(text), Modifier.weight(1f), muted = true, size = 13.sp)
    }
}
