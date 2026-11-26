package io.github.alalkipgen.autoflow.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.media.audio.CleanAudio
import io.github.alalkipgen.autoflow.media.audio.VideoSound
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors

/**
 * The last Save of this project (V-1f; Group 6 F): while it runs = the Group 1 job card (Save m4a `download-simple` Pink, Clean
 * `broom` Teal, the video kinds `file-video` Blue) with the bar and Cancel; cancelled = an amber callout, failed = a red one;
 * saved = a green card (the path in mono) with Share / Save to Music (or Gallery) on a phone, Open folder on a computer;
 * then the save notice as a green / amber callout. What is saved and how is unchanged.
 */
@Composable
internal fun ExportPanel(export: VoiceExportModel, dir: String, title: String) {
    val e by export.state.collectAsState()
    val runs by export.runs.collectAsState()
    if (e.dir != dir) return
    val run = runs.lastOrNull { it.output == e.file }
    val state = run?.state
    val (heading, progress) = when (run?.kind) {
        CleanAudio.CLEAN_AUDIO -> UiText.VA_CLEAN to UiText.VA_CLEANING
        VideoSound.SOUND_ONLY -> UiText.VA_SOUND_ONLY to UiText.VA_WORKING
        VideoSound.REPLACE -> UiText.VA_REPLACE_SOUND to UiText.SUB_BURN_RUNNING
        VideoSound.ADD -> UiText.VA_ADD_ON_TOP to UiText.SUB_BURN_RUNNING
        else -> UiText.VA_SAVE_M4A to UiText.VA_SAVING_M4A
    }
    val tile = when (run?.kind) {
        CleanAudio.CLEAN_AUDIO -> CardTile(AfIcons.Fill.Broom, GlassColors.RecapTeal)
        VideoSound.SOUND_ONLY, VideoSound.REPLACE, VideoSound.ADD -> CardTile(AfIcons.Fill.FileVideo, GlassColors.ExportBlue)
        else -> CardTile(AfIcons.Regular.DownloadSimple, GlassColors.RecapPink)
    }
    when (state) {
        is RenderState.Queued, is RenderState.Running -> JobCard(tr(heading),
            if (state is RenderState.Running) StudioStatus.RUNNING else StudioStatus.QUEUED,
            My.breakSyllables(tr(progress, "percent" to ((state as? RenderState.Running)?.fraction ?: 0.0).times(100).toInt())),
            Modifier.testTag("voice-m4a-job"), progress = (state as? RenderState.Running)?.fraction?.toFloat(), indeterminate = state is RenderState.Queued,
            tile = tile) {
            GlassButton(My.breakSyllables(tr(UiText.CANCEL)), export::cancel, Modifier.testTag("voice-m4a-cancel"), kind = ButtonKind.Danger,
                icon = AfIcons.Regular.Close, small = true)
        }
        is RenderState.Cancelled -> GlassCallout(AfIcons.Fill.Warn, My.breakSyllables(tr(UiText.SUB_BURN_CANCELLED)), AutoFlowColors.Amber,
            textModifier = Modifier.testTag("voice-m4a-cancelled"))
        is RenderState.Failed -> GlassCallout(AfIcons.Fill.XCircle, My.breakSyllables(tr(UiText.RESULT_DETAIL, "detail" to state.reason)),
            AutoFlowColors.Failed, textModifier = Modifier.testTag("voice-m4a-failed"))
        else -> {}
    }
    val ready = if (state is RenderState.Done) state.output else e.file?.takeIf { run == null }
    if (ready != null) GlassCard(tint = AutoFlowColors.Green, tintAlpha = .12f, outline = AutoFlowColors.Green.copy(alpha = .45f),
        outlineWidth = 1.dp, gap = 10.dp) {
        if (state is RenderState.Done) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftTile(AfIcons.Fill.CheckCircle, AutoFlowColors.Green, size = 30.dp)
            SavedText(ready, Modifier.weight(1f).padding(top = 4.dp))
        }
        WrapRow {
            if (export.sharesFiles) {
                GlassButton(My.breakSyllables(tr(UiText.SHARE)), { export.share(title) }, Modifier.testTag("voice-share"), icon = AfIcons.Regular.Share,
                    small = true)
                val video = export.isVideo(ready)
                GlassButton(My.breakSyllables(tr(if (video) UiText.SAVE_GALLERY else UiText.VA_SAVE_MUSIC)), export::saveToMusic,
                    Modifier.testTag("voice-music"), icon = if (video) AfIcons.Regular.Image else AfIcons.Regular.MusicNote, small = true)
            } else GlassButton(My.breakSyllables(tr(UiText.OPEN_FOLDER)), { export.openFolder() }, Modifier.testTag("voice-folder"),
                icon = AfIcons.Regular.FolderOpen, small = true)
        }
    }
    e.notice?.let {
        GlassCallout(if (e.ok) AfIcons.Fill.CheckCircle else AfIcons.Fill.Warn, My.breakSyllables(it.text(LocalUiLanguage.current)),
            if (e.ok) AutoFlowColors.Green else AutoFlowColors.Amber, textModifier = Modifier.testTag("voice-save-notice"))
    }
}

/** SUB_EXPORT_SAVED ("Saved: {path}"): the words in green bold, the path in mono under them (one node for a screen reader). */
@Composable
private fun SavedText(path: String, modifier: Modifier) {
    val full = tr(UiText.SUB_EXPORT_SAVED, "path" to path)
    val words = full.substringBefore(path).trimEnd()
    Column(modifier.testTag("voice-saved").semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (words.isNotEmpty() && full.endsWith(path)) {
            AfText(My.breakSyllables(words), color = AutoFlowColors.Green, weight = FontWeight.Bold)
            AfText(path, muted = true, size = 12.sp, mono = true)
        } else AfText(My.breakSyllables(full), color = AutoFlowColors.Green, weight = FontWeight.Bold)
    }
}
