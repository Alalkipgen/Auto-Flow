package io.github.alalkipgen.autoflow.ui.recap

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
import io.github.alalkipgen.autoflow.core.recap.RecapRules
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
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

const val SPEAK_ALL = "🔊 Speak all (Gemini TTS)"
const val RECORD = "● Record"
const val STOP = "■ Stop"

/** Card 4 · Voice (docs/specs/recap.md §5, D-34): recorded or Gemini TTS narration per segment. */
@Composable
internal fun VoiceCard(model: RecapModel, state: RecapUiState, recap: Recap) {
    AfCard("4 · Voice") {
        val script = state.script
        RecapRules.canVoice(recap, script).blockers.firstOrNull()?.let {
            AfText(it.text(), muted = true)
            return@AfCard
        }
        script!!
        val idle = !state.busy && state.recording == null
        WrapRow {
            AfChip("🎙 Recorded", recap.voiceSource == VoiceSource.RECORDED, { if (idle) model.setVoiceSource(VoiceSource.RECORDED) })
            AfChip("🔊 Gemini TTS", recap.voiceSource == VoiceSource.TTS, { if (idle) model.setVoiceSource(VoiceSource.TTS) })
        }
        if (recap.voiceSource == VoiceSource.TTS) {
            AfText("Voice", muted = true, size = 12.sp)
            WrapRow {
                RecapModel.TTS_VOICES.forEach { (name, kind) -> AfChip("$name · $kind", recap.ttsVoice == name, { if (idle) model.setTtsVoice(name) }) }
            }
            AfButton(SPEAK_ALL, { model.speakAll() }, kind = ButtonKind.Primary, enabled = idle)
            AfText("Uses your Gemini key; one request per scene. A new voice or style is used for the scenes spoken next.", muted = true, size = 12.sp)
        } else {
            AfText(
                if (model.canRecord) "Tap $RECORD on a scene, read its text, then $STOP. Or import an audio file." else "Import an audio file for each scene.",
                muted = true, size = 12.sp,
            )
        }
        val voiced = script.segments.indices.count { RecapRules.currentTake(recap, script, it) != null }
        AfText("Voiced $voiced of ${script.segments.size} scenes", bold = true, color = if (voiced == script.segments.size) AutoFlowColors.Green else null)
        var playing by remember { mutableStateOf<Int?>(null) }
        // Block detail: tap "Voice & Audio" on a row to open the teleprompter / tab view (5B-5).
        var blockDetail by remember { mutableStateOf<Int?>(null) }
        if (blockDetail != null) {
            VoiceBlockCard(model, state, recap, script, blockDetail!!, onClose = { blockDetail = null })
            return@AfCard
        }
        script.segments.forEachIndexed { i, _ ->
            VoiceRow(model, state, recap, script, i, idle, playing == i,
                onPlay = { playing = if (playing == i) null else i },
                onOpenBlock = { blockDetail = i })
        }
    }
}

@Composable
private fun VoiceRow(model: RecapModel, state: RecapUiState, recap: Recap, script: RecapScript, i: Int, idle: Boolean, playing: Boolean, onPlay: () -> Unit, onOpenBlock: () -> Unit = {}) {
    val seg = script.segments[i]
    val take = RecapRules.currentTake(recap, script, i)
    val stale = RecapRules.isStale(recap, script, i)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText("#${i + 1} · ${clock(seg.startMs)} · scene ${seconds(seg.durationMs)}", Modifier.weight(1f), muted = true, size = 12.sp)
            when {
                take != null -> AfText("${if (take.source == VoiceSource.TTS) "🔊" else "🎙"} ${seconds(take.durationMs)} ✓", color = AutoFlowColors.Green, size = 12.sp)
                stale -> AfText("⚠ Stale — text changed", color = AutoFlowColors.Amber, size = 12.sp)
                else -> AfText("No voice", muted = true, size = 12.sp)
            }
        }
        AfText(seg.textMy, size = 12.sp)
        WrapRow {
            if (state.recording == i) {
                AfButton(STOP, { model.stopRecording() }, kind = ButtonKind.Danger)
                AfButton("Cancel", { model.cancelRecording() })
            } else if (recap.voiceSource == VoiceSource.TTS) {
                AfButton("🔊", { model.speak(i) }, enabled = idle && seg.textMy.isNotBlank())
            } else {
                if (model.canRecord) AfButton(RECORD, { model.startRecording(i) }, kind = ButtonKind.Primary, enabled = idle && seg.textMy.isNotBlank())
                AfButton("Import file", { model.importTake(i) }, enabled = idle && seg.textMy.isNotBlank())
            }
            val file = state.takeFiles[i]
            if (take != null && file != null) AudioPlayButton(file, playing, onPlay)
            // Voice & Audio block detail (5B-5)
            AfButton("⋮ Voice & Audio", onOpenBlock, enabled = idle)
        }
    }
}
