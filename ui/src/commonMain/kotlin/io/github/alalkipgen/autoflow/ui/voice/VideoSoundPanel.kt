package io.github.alalkipgen.autoflow.ui.voice

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.subtitles.SubProgressCard
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors

/**
 * Video sound (V-3b; Group 6 H): the file card (Blue), the action card (three radio rows), the sound card (Replace / Add: pick
 * or the picked file + Change), the Add card (start · volume · lower the original), the busy strip and the amber notice, then
 * [export]. What is made and how is unchanged.
 */
@Composable
internal fun VideoSoundStep(video: VideoSoundModel, voice: VoiceTool?, dir: String?, export: VoiceExportModel?, title: String) {
    val v by video.state.collectAsState()
    val s = v.takeIf { it.dir == dir } ?: VideoSoundUi(dir)
    val source = voice?.source
    if (source != null) {
        // H 1: the file card — file-video tile, the name in mono, "3:12 · 1920×1080", the picture kept; no sound = amber callout.
        GlassCard(Modifier.testTag("video-sound-file"), tint = GlassColors.ExportBlue, tintAlpha = .12f, gap = 10.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SoftTile(AfIcons.Regular.FileVideo, GlassColors.ExportBlue, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // D-100: the cut name stays readable in full (content description).
                    val name = source.file.substringAfterLast('/')
                    AfText(name, Modifier.semantics { contentDescription = name }, mono = true, bold = true, maxLines = 1, ellipsis = true)
                    AfText("${RecapModel.clock(source.durationMs)} · ${source.width}×${source.height}", muted = true, size = 12.sp)
                }
            }
            GlassFact(AfIcons.Regular.CheckCircle, My.breakSyllables(tr(UiText.VA_PICTURE_KEPT)), colour = AutoFlowColors.Muted, caption = true)
        }
        if (!source.hasAudio) GlassCallout(AfIcons.Fill.Warn, My.breakSyllables(tr(UiText.VA_NO_ORIGINAL_SOUND)), AutoFlowColors.Amber)
        val idle = s.busy == null
        // H 2: the action — three glass radio rows with their icon at the end; Sound only is off without a sound of its own.
        GlassCard(gap = 8.dp) {
            listOf(Triple(SOUND_ONLY, UiText.VA_SOUND_ONLY, AfIcons.Regular.Volume), Triple(REPLACE, UiText.VA_REPLACE_SOUND, AfIcons.Regular.Swap),
                Triple(ADD, UiText.VA_ADD_ON_TOP, AfIcons.Regular.Stack)).forEach { (id, label, icon) ->
                ActionRow(My.breakSyllables(tr(label)), icon, s.action == id, { video.setAction(id) }, Modifier.testTag("video-sound-$id"),
                    enabled = idle && (id != SOUND_ONLY || source.hasAudio))
            }
        }
        if (s.action != SOUND_ONLY) GlassCard(gap = 10.dp) {
            // H 3: the sound — nothing picked = the folder-open button; picked = the file row + a small Change (same tag).
            val picked = s.sound
            if (picked == null) GlassButton(My.breakSyllables(tr(UiText.VA_PICK_SOUND_FILE)), { video.pickSound() },
                Modifier.fillMaxWidth().testTag("video-sound-pick"), enabled = idle, icon = AfIcons.Regular.FolderOpen)
            else {
                val change = @Composable { m: Modifier -> GlassButton(My.breakSyllables(tr(UiText.CHANGE)), { video.pickSound() },
                    m.testTag("video-sound-pick"), enabled = idle, icon = AfIcons.Regular.FolderOpen, small = true) }
                // A large font (≥ 1.5) puts Change under the file so the name keeps its width.
                val stacked = LocalDensity.current.fontScale >= 1.5f
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftTile(AfIcons.Regular.FileAudio, GlassColors.RecapTeal, size = 34.dp)
                    AfText(My.breakSyllables(tr(UiText.VA_SOUND_FILE, "name" to picked.substringAfterLast('/'))), Modifier.weight(1f).testTag("video-sound-picked"),
                        weight = FontWeight.Bold)
                    if (!stacked) change(Modifier)
                }
                if (stacked) change(Modifier.align(Alignment.End))
            }
            GlassFact(AfIcons.Regular.Info, My.breakSyllables(tr(UiText.VA_PAD_CUT)), colour = AutoFlowColors.Muted, caption = true)
        }
        if (s.action == ADD) GlassCard(gap = 10.dp) {
            // H 4: Add's controls — start (160 dp field), volume chips, lower the original (only with a sound of its own).
            var start by remember(dir) { mutableStateOf(if (s.startMs == 0L) "0" else (s.startMs / 1000.0).toString()) }
            AfText(My.breakSyllables(tr(UiText.VA_START_AT)), size = 14.sp, bold = true)
            AfTextField(start, { t -> start = t; t.replace(',', '.').toDoubleOrNull()?.let { video.setStart((it * 1000).toLong()) } }, "0",
                Modifier.widthIn(max = 160.dp).testTag("video-sound-start"), enabled = idle, glass = true)
            AfText(My.breakSyllables(tr(UiText.VA_VOLUME)), size = 14.sp, bold = true)
            WrapRow {
                listOf(0.5, 1.0, 1.5).forEach { vol ->
                    GlassFilterChip("${(vol * 100).toInt()}%", s.volume == vol, { video.setVolume(vol) },
                        Modifier.testTag("video-sound-volume-${(vol * 100).toInt()}"), enabled = idle)
                }
            }
            if (source.hasAudio) SwitchRow(My.breakSyllables(tr(UiText.VA_LOWER_ORIGINAL)), null, s.lower, { video.setLower(!s.lower) },
                Modifier.testTag("video-sound-lower"), icon = AfIcons.Regular.SpeakerLow, enabled = idle)
        }
    }
    // H 5: busy = the cyan strip; the notice = an amber callout.
    s.busy?.let { SubProgressCard(My.breakSyllables(it.text(LocalUiLanguage.current)), "video-sound-busy") }
    s.notice?.let { GlassCallout(AfIcons.Fill.Warn, My.breakSyllables(it.text(LocalUiLanguage.current)), AutoFlowColors.Amber,
        textModifier = Modifier.testTag("video-sound-notice")) }
    if (dir != null && export != null) ExportPanel(export, dir, title)
}

/** One action (H 2): a glass row (radius 14) — `radio-button` Fill when chosen (else a ring), the label, its icon at the end. */
@Composable
private fun ActionRow(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier, enabled: Boolean) {
    val shape = RoundedCornerShape(14.dp)
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
        .background(if (selected) AutoFlowColors.Purple.copy(alpha = .14f) else Color.White.copy(alpha = .03f))
        .border(if (selected) 1.5.dp else 1.dp, if (selected) AutoFlowColors.Purple else Color.White.copy(alpha = .08f), shape)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).alpha(if (enabled) 1f else .4f)
        .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AfIcon(if (selected) AfIcons.Fill.RadioButton else AfIcons.Regular.Circle, tint = if (selected) AutoFlowColors.Purple else AutoFlowColors.Muted,
            size = 22.dp)
        AfText(label, Modifier.weight(1f), weight = if (selected) FontWeight.Bold else null)
        AfIcon(icon, tint = if (selected) AutoFlowColors.PrimaryText else AutoFlowColors.Muted, size = 20.dp)
    }
}
