package io.github.alalkipgen.autoflow.ui.voice

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.alalkipgen.autoflow.core.model.CleanChoice
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** Clean up sound (V-2b): the picked file, the three choices, Before / After, then the saved result ([export]). */
@Composable
internal fun CleanStep(clean: VoiceCleanModel, voice: VoiceTool?, dir: String?, export: VoiceExportModel?, title: String) {
    val c by clean.state.collectAsState()
    AfH2(My.breakSyllables(tr(UiText.VA_CLEAN)))
    val source = voice?.source
    if (source != null) {
        AfText(My.breakSyllables("${source.file.substringAfterLast('/')} · ${RecapModel.clock(source.durationMs)}"), Modifier.testTag("voice-clean-file"), muted = true)
        if (source.width > 0) AfText(My.breakSyllables(tr(UiText.VA_VIDEO_GIVES_SOUND)), muted = true)
        val choice = voice.clean
        val idle = c.busy == null || c.dir != dir
        WrapRow {
            listOf(Triple(UiText.VA_CUT_PAUSES, choice.pauses, choice.copy(pauses = !choice.pauses)),
                Triple(UiText.VA_REDUCE_NOISE, choice.noise, choice.copy(noise = !choice.noise)),
                Triple(UiText.VA_EVEN_LOUDNESS, choice.loudness, choice.copy(loudness = !choice.loudness)))
                .forEachIndexed { i, (label, on, next) ->
                    AfChip(My.breakSyllables(tr(label)), on, { clean.setChoice(next) }, Modifier.testTag("voice-clean-choice-$i"), enabled = idle && next.any)
                }
        }
        AfButton(My.breakSyllables(tr(UiText.VA_BEFORE_AFTER, "s" to (clean.previewMs / 1000))), { clean.preview() },
            Modifier.testTag("voice-clean-preview"), enabled = idle)
        if (c.dir == dir && c.before != null && c.after != null) {
            var playing by remember { mutableStateOf<String?>(null) }
            listOf(UiText.VA_BEFORE to c.before, UiText.VA_AFTER to c.after).forEach { (label, path) ->
                WrapRow(Modifier.testTag("voice-clean-${if (label == UiText.VA_BEFORE) "before" else "after"}")) {
                    AudioPlayButton(path!!, playing == path, { playing = if (playing == path) null else path })
                    AfText(My.breakSyllables(tr(label)))
                }
            }
        }
    }
    if (c.dir == dir) {
        c.busy?.let { AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("voice-clean-busy"), muted = true) }
        c.notice?.let { AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("voice-clean-notice"), color = AutoFlowColors.Amber) }
    }
    if (dir != null && export != null) ExportPanel(export, dir, title)
}
