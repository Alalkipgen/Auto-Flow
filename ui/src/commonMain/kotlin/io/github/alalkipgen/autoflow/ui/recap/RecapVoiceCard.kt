package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.RecapRulesV2
import io.github.alalkipgen.autoflow.core.recap.TakeSource
import io.github.alalkipgen.autoflow.core.recap.RecapScript
import io.github.alalkipgen.autoflow.core.recap.VoiceSource
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfChip
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.AudioPlayButton
import io.github.alalkipgen.autoflow.ui.recap.RecapModel.Companion.clock
import io.github.alalkipgen.autoflow.ui.recap.RecapModel.Companion.seconds
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.recap.SpeakAllEta
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

const val SPEAK_ALL = "🔊 Speak all (Gemini TTS)"
const val RECORD = "● အသံသွင်းမယ်"
const val STOP = "■ ရပ်မယ်"

/** Card 4 · Voice (docs/specs/recap.md §5, D-34): recorded or Gemini TTS narration per segment. */
@Composable
internal fun VoiceCard(model: RecapModel, state: RecapUiState, recap: RecapV2, blockIndex: Int? = null) {
    AfCard(tr(UiText.RECAP_VOICE_CARD)) {
        val script = state.script
        RecapRulesV2.canVoice(recap, state.story).blockers.firstOrNull()?.let {
            AfText(it.text(LocalUiLanguage.current), muted = true)
            return@AfCard
        }
        script!!
        val idle = !state.busy && state.recording == null
        VoiceLockControls(model, state, recap, idle)
        run {
            val eta = state.speakAllEta
            if (eta != null) {
                // ETA confirmation card (5A-5, D-51): show before the loop starts
                val etaStr = eta.etaSeconds?.let { tr(UiText.SPEAK_ALL_ETA, "it60" to (it / 60)) } ?: ""
                AfText(tr(UiText.SPEAK_ALL_CONFIRM, "count" to (eta.count), "etaStr" to (etaStr)), bold = true)
                WrapRow {
                    AfButton(tr(UiText.START_PLAY), { model.speakAll(confirmed = true) }, kind = ButtonKind.Primary)
                    AfButton(tr(UiText.CANCEL), { model.cancelSpeakAll() })
                }
            } else {
                AfButton(tr(UiText.SPEAK_ALL), { model.speakAll() }, kind = ButtonKind.Primary, enabled = idle)
            }
            AfText(tr(UiText.SPEAK_ALL_HINT), muted = true, size = 12.sp)
        }
        val voiced = script.segments.indices.count { state.currentTake(it) != null }
        AfText(tr(UiText.SCENES_VOICED, "voiced" to (voiced), "size" to (script.segments.size)), bold = true, color = if (voiced == script.segments.size) AutoFlowColors.Green else null)
        var playing by remember(state.projectDir, blockIndex) { mutableStateOf<Int?>(null) }
        // Block detail: tap "Voice & Audio" on a row to open the teleprompter / tab view (5B-5).
        var blockDetail by remember(state.projectDir, blockIndex) { mutableStateOf<Int?>(null) }
        if (blockDetail != null) {
            VoiceBlockCard(model, state, recap, script, blockDetail!!, onClose = { blockDetail = null })
            return@AfCard
        }
        script.segments.forEachIndexed { i, _ ->
            if (blockIndex == null || i == blockIndex) VoiceRow(model, state, recap, script, i, idle, playing == i,
                onPlay = { playing = if (playing == i) null else i },
                onOpenBlock = { blockDetail = i })
        }
    }
}

@Composable
private fun VoiceRow(model: RecapModel, state: RecapUiState, recap: RecapV2, script: RecapScript, i: Int, idle: Boolean, playing: Boolean, onPlay: () -> Unit, onOpenBlock: () -> Unit = {}) {
    val seg = script.segments[i]
    val take = state.currentTake(i)
    val stale = state.isStale(i)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText(tr(UiText.VOICE_SCENE_TIME, "i1" to (i + 1), "startMs" to (clock(seg.startMs)), "durationMs" to (seconds(seg.durationMs))), Modifier.weight(1f), muted = true, size = 12.sp)
            when {
                take != null -> AfText("${if (take.source == TakeSource.TTS) "🔊" else "🎙"} ${seconds(take.durationMs)} ✓", color = AutoFlowColors.Green, size = 12.sp)
                stale -> AfText(tr(UiText.STALE_TEXT_CHANGED), color = AutoFlowColors.Amber, size = 12.sp)
                else -> AfText(tr(UiText.NO_VOICE), muted = true, size = 12.sp)
            }
        }
        TakeVoiceLabel(recap.takeFor(state.story!!.blocks[i].id), recap.voiceLock)
        AfText(seg.textMy, size = 12.sp)
        WrapRow {
            if (state.recording == i) {
                AfButton(tr(UiText.STOP), { model.stopRecording() }, kind = ButtonKind.Danger)
                AfButton(tr(UiText.CANCEL), { model.cancelRecording() })
            } else {
                AfButton("🔊", { model.speak(i) }, enabled = idle && seg.textMy.isNotBlank())
                if (model.canRecord) AfButton(tr(UiText.RECORD), { model.startRecording(i) }, kind = ButtonKind.Primary, enabled = idle && seg.textMy.isNotBlank())
                AfButton(tr(UiText.IMPORT_FILE), { model.importTake(i) }, enabled = idle && seg.textMy.isNotBlank())
            }
            val file = state.takeFiles[i]
            if (take != null && file != null) AudioPlayButton(file, playing, onPlay)
            // Voice & Audio block detail (5B-5)
            AfButton(tr(UiText.VOICE_AUDIO_DETAIL), onOpenBlock, enabled = idle)
        }
    }
}
