package io.github.alalkipgen.autoflow.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.alalkipgen.autoflow.media.audio.CleanAudio
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** The last Save of this project (V-1f): the m4a's progress with Cancel, then the file with Share / Save to Music / Open folder. */
@Composable
internal fun ExportPanel(export: VoiceExportModel, dir: String, title: String) {
    val e by export.state.collectAsState()
    val runs by export.runs.collectAsState()
    if (e.dir != dir) return
    val run = runs.lastOrNull { it.output == e.file }
    val state = run?.state
    val cleaning = run?.kind == CleanAudio.CLEAN_AUDIO
    when (state) {
        is RenderState.Queued, is RenderState.Running -> JobCard(tr(if (cleaning) UiText.VA_CLEAN else UiText.VA_SAVE_M4A),
            if (state is RenderState.Running) StudioStatus.RUNNING else StudioStatus.QUEUED,
            My.breakSyllables(tr(if (cleaning) UiText.VA_CLEANING else UiText.VA_SAVING_M4A, "percent" to ((state as? RenderState.Running)?.fraction ?: 0.0).times(100).toInt())),
            Modifier.testTag("voice-m4a-job"), progress = (state as? RenderState.Running)?.fraction?.toFloat(), indeterminate = state is RenderState.Queued) {
            AfButton(My.breakSyllables(tr(UiText.CANCEL)), export::cancel, Modifier.testTag("voice-m4a-cancel"))
        }
        is RenderState.Cancelled -> AfText(My.breakSyllables(tr(UiText.SUB_BURN_CANCELLED)), Modifier.testTag("voice-m4a-cancelled"), color = AutoFlowColors.Amber)
        is RenderState.Failed -> AfText(My.breakSyllables(tr(UiText.RESULT_DETAIL, "detail" to state.reason)), Modifier.testTag("voice-m4a-failed"),
            color = AutoFlowColors.Failed)
        else -> {}
    }
    val ready = if (state is RenderState.Done) state.output else e.file?.takeIf { run == null }
    if (ready != null) {
        if (state is RenderState.Done) AfText(My.breakSyllables(tr(UiText.SUB_EXPORT_SAVED, "path" to ready)), Modifier.testTag("voice-saved"),
            color = AutoFlowColors.Green)
        WrapRow {
            if (export.sharesFiles) {
                AfButton(My.breakSyllables(tr(UiText.SHARE)), { export.share(title) }, Modifier.testTag("voice-share"))
                AfButton(My.breakSyllables(tr(UiText.VA_SAVE_MUSIC)), export::saveToMusic, Modifier.testTag("voice-music"))
            } else AfButton(My.breakSyllables(tr(UiText.OPEN_FOLDER)), { export.openFolder() }, Modifier.testTag("voice-folder"))
        }
    }
    e.notice?.let {
        AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("voice-save-notice"), color = if (e.ok) AutoFlowColors.Green else AutoFlowColors.Amber)
    }
}
