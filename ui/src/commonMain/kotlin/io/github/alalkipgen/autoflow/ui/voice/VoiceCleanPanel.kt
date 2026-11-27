package io.github.alalkipgen.autoflow.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.subtitles.SubProgressCard
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors

/**
 * Clean up sound (V-2b; Group 6 G): the file card (Teal), the choices card (three switches, at least one stays on), the
 * Before / After card (`ear`; the two rows with play), the busy strip and the amber notice, then the saved result ([export]).
 * What is cleaned and saved is unchanged.
 */
@Composable
internal fun CleanStep(clean: VoiceCleanModel, voice: VoiceTool?, dir: String?, export: VoiceExportModel?, title: String) {
    val c by clean.state.collectAsState()
    val source = voice?.source
    if (source != null) {
        // G 1: the file card — 40 dp file-audio tile, the name in mono (one line), the length; a video gives its sound only.
        GlassCard(Modifier.testTag("voice-clean-file"), tint = GlassColors.RecapTeal, tintAlpha = .12f, gap = 10.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SoftTile(AfIcons.Regular.FileAudio, GlassColors.RecapTeal, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // D-100: the cut name stays readable in full (content description).
                    val name = source.file.substringAfterLast('/')
                    AfText(name, Modifier.semantics { contentDescription = name }, mono = true, bold = true, maxLines = 1, ellipsis = true)
                    AfText(RecapModel.clock(source.durationMs), muted = true, size = 12.sp)
                }
            }
            if (source.width > 0) GlassFact(AfIcons.Regular.Info, My.breakSyllables(tr(UiText.VA_VIDEO_GIVES_SOUND)), colour = AutoFlowColors.Muted,
                caption = true)
        }
        val choice = voice.clean
        val idle = c.busy == null || c.dir != dir
        // G 2: the choices — the last one on cannot be switched off (next.any), all off while busy.
        GlassCard(gap = 4.dp) {
            listOf(Triple(UiText.VA_CUT_PAUSES to AfIcons.Regular.Scissors, choice.pauses, choice.copy(pauses = !choice.pauses)),
                Triple(UiText.VA_REDUCE_NOISE to AfIcons.Regular.Broom, choice.noise, choice.copy(noise = !choice.noise)),
                Triple(UiText.VA_EVEN_LOUDNESS to AfIcons.Regular.Gauge, choice.loudness, choice.copy(loudness = !choice.loudness)))
                .forEachIndexed { i, (label, on, next) ->
                    SwitchRow(My.breakSyllables(tr(label.first)), null, on, { clean.setChoice(next) }, Modifier.testTag("voice-clean-choice-$i"),
                        icon = label.second, enabled = idle && next.any)
                }
        }
        // G 3: Before / After — the ear button, then the two rows (After in green); the one playing is the cyan button.
        GlassCard(gap = 10.dp) {
            GlassButton(My.breakSyllables(tr(UiText.VA_BEFORE_AFTER, "s" to (clean.previewMs / 1000))), { clean.preview() },
                Modifier.fillMaxWidth().testTag("voice-clean-preview"), enabled = idle, icon = AfIcons.Regular.Ear)
            if (c.dir == dir && c.before != null && c.after != null) {
                var playing by remember { mutableStateOf<String?>(null) }
                listOf(UiText.VA_BEFORE to c.before, UiText.VA_AFTER to c.after).forEach { (label, path) ->
                    val after = label == UiText.VA_AFTER
                    Row(Modifier.fillMaxWidth().testTag("voice-clean-${if (after) "after" else "before"}"), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AudioPlayButton(path!!, playing == path, { playing = if (playing == path) null else path }, glass = true,
                            glassIcon = AfIcons.Fill.Play, playingIcon = AfIcons.Fill.Pause, playingTone = AutoFlowColors.Cyan)
                        AfText(My.breakSyllables(tr(label)), Modifier.weight(1f), color = if (after) AutoFlowColors.Green else null,
                            weight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    if (c.dir == dir) {
        // G 4: busy = the cyan strip (hourglass + the moving bar); the notice = an amber callout.
        c.busy?.let { SubProgressCard(My.breakSyllables(it.text(LocalUiLanguage.current)), "voice-clean-busy") }
        c.notice?.let { GlassCallout(AfIcons.Fill.Warn, My.breakSyllables(it.text(LocalUiLanguage.current)), AutoFlowColors.Amber,
            textModifier = Modifier.testTag("voice-clean-notice")) }
    }
    if (dir != null && export != null) ExportPanel(export, dir, title)
}
