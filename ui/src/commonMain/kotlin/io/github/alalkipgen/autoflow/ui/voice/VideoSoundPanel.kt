package io.github.alalkipgen.autoflow.ui.voice

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** Video sound (V-3b): the video, Save the sound only / Replace / Add on top, the picked sound and Add's controls, then [export]. */
@Composable
internal fun VideoSoundStep(video: VideoSoundModel, voice: VoiceTool?, dir: String?, export: VoiceExportModel?, title: String) {
    val v by video.state.collectAsState()
    val s = v.takeIf { it.dir == dir } ?: VideoSoundUi(dir)
    AfH2(My.breakSyllables(tr(UiText.VA_VIDEO_SOUND)))
    val source = voice?.source
    if (source != null) {
        AfText(My.breakSyllables("${source.file.substringAfterLast('/')} · ${RecapModel.clock(source.durationMs)} · ${source.width}×${source.height}"),
            Modifier.testTag("video-sound-file"), muted = true)
        AfText(My.breakSyllables(tr(UiText.VA_PICTURE_KEPT)), muted = true)
        if (!source.hasAudio) AfText(My.breakSyllables(tr(UiText.VA_NO_ORIGINAL_SOUND)), color = AutoFlowColors.Amber)
        val idle = s.busy == null
        WrapRow {
            listOf(SOUND_ONLY to UiText.VA_SOUND_ONLY, REPLACE to UiText.VA_REPLACE_SOUND, ADD to UiText.VA_ADD_ON_TOP).forEach { (id, label) ->
                AfChip(My.breakSyllables(tr(label)), s.action == id, { video.setAction(id) }, Modifier.testTag("video-sound-$id"),
                    enabled = idle && (id != SOUND_ONLY || source.hasAudio))
            }
        }
        if (s.action != SOUND_ONLY) {
            WrapRow {
                AfButton(My.breakSyllables(tr(UiText.VA_PICK_SOUND_FILE)), { video.pickSound() }, Modifier.testTag("video-sound-pick"), enabled = idle)
                s.sound?.let { AfText(My.breakSyllables(tr(UiText.VA_SOUND_FILE, "name" to it.substringAfterLast('/'))), Modifier.testTag("video-sound-picked")) }
            }
            AfText(My.breakSyllables(tr(UiText.VA_PAD_CUT)), muted = true)
        }
        if (s.action == ADD) {
            var start by remember(dir) { mutableStateOf(if (s.startMs == 0L) "0" else (s.startMs / 1000.0).toString()) }
            AfText(My.breakSyllables(tr(UiText.VA_START_AT)), bold = true)
            AfTextField(start, { t -> start = t; t.replace(',', '.').toDoubleOrNull()?.let { video.setStart((it * 1000).toLong()) } }, "0",
                Modifier.widthIn(max = 160.dp).testTag("video-sound-start"), enabled = idle)
            AfText(My.breakSyllables(tr(UiText.VA_VOLUME)), bold = true)
            WrapRow {
                listOf(0.5, 1.0, 1.5).forEach { vol ->
                    AfChip("${(vol * 100).toInt()}%", s.volume == vol, { video.setVolume(vol) }, Modifier.testTag("video-sound-volume-${(vol * 100).toInt()}"), enabled = idle)
                }
            }
            if (source.hasAudio) AfChip(My.breakSyllables(tr(UiText.VA_LOWER_ORIGINAL)), s.lower, { video.setLower(!s.lower) },
                Modifier.testTag("video-sound-lower"), enabled = idle)
        }
    }
    s.busy?.let { AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("video-sound-busy"), muted = true) }
    s.notice?.let { AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("video-sound-notice"), color = AutoFlowColors.Amber) }
    if (dir != null && export != null) ExportPanel(export, dir, title)
}
