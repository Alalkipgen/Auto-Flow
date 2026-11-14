package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapScript
import io.github.alalkipgen.autoflow.core.recap.RecapRules
import io.github.alalkipgen.autoflow.core.recap.VoiceSource
import io.github.alalkipgen.autoflow.core.recap.VoiceTab
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfChip
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.AfTextField
import io.github.alalkipgen.autoflow.ui.components.AudioPlayButton
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.recap.RecapModel.Companion.clock
import io.github.alalkipgen.autoflow.ui.recap.RecapModel.Companion.seconds
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes

/**
 * Voice & Audio for a single block (5B-5, DESIGN.md §7 Voice step).
 * Tabs: Speak · Record (teleprompter) · Clean · Clone (Labs-only placeholder).
 * Shown when the owner taps a block row and opens its detail.
 */
@Composable
fun VoiceBlockCard(
    model: RecapModel,
    state: RecapUiState,
    recap: Recap,
    script: RecapScript,
    segmentIndex: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val seg = script.segments.getOrNull(segmentIndex) ?: return
    val take = RecapRules.currentTake(recap, script, segmentIndex)
    val stale = RecapRules.isStale(recap, script, segmentIndex)
    val idle = !state.busy && state.recording == null
    var tab by remember { mutableStateOf(
        if (recap.voiceSource == VoiceSource.TTS) VoiceTab.SPEAK else VoiceTab.RECORD
    ) }
    var playing by remember { mutableStateOf(false) }

    AfCard("Voice — Block ${segmentIndex + 1}", modifier = modifier) {
        // Back link
        AfButton("← Back", onClose)
        AfText("${clock(seg.startMs)}–${clock(seg.endMs)} · ${seconds(seg.durationMs)}", muted = true, size = 12.sp)

        // Tab row
        WrapRow {
            VoiceTab.entries.filter { it != VoiceTab.CLONE }.forEach { t ->
                AfChip(t.name.lowercase().replaceFirstChar { it.uppercase() }, tab == t, onClick = { tab = t })
            }
            // Clone: Labs placeholder (always disabled)
            AfChip("Clone 🔬", false, onClick = {})
        }

        when (tab) {
            VoiceTab.SPEAK -> SpeakTab(model, state, recap, script, segmentIndex, idle, seg.textMy)
            VoiceTab.RECORD -> RecordTab(model, state, recap, script, segmentIndex, idle, seg, take, stale, playing) { playing = !playing }
            VoiceTab.CLEAN -> CleanTab(model, state, segmentIndex, idle, take)
            VoiceTab.CLONE -> AfText("Voice Clone is a Labs feature.", muted = true)
        }
    }
}

@Composable
private fun SpeakTab(
    model: RecapModel,
    state: RecapUiState,
    recap: Recap,
    script: RecapScript,
    index: Int,
    idle: Boolean,
    text: String,
) {
    val seg = script.segments[index]
    AfText(seg.textMy, size = 13.sp)
    AfText("Voice: ${recap.ttsVoice} · ${recap.voiceSource.name}", muted = true, size = 11.sp)
    WrapRow {
        AfButton("🔊 Speak", { model.speak(index) },
            kind = ButtonKind.Primary, enabled = idle && text.isNotBlank())
    }
    // Per-chunk caption timing edit (D-52): edit startMs / endMs in mm:ss.s format.
    TimingEditRow(seg.startMs, seg.endMs) { start, end -> model.saveSegmentTiming(index, start, end) }
}

/** Inline timing editor: shows current start/end, two text fields, Save button (D-52). */
@Composable
private fun TimingEditRow(startMs: Long, endMs: Long, onSave: (Long, Long) -> Unit) {
    var editing by remember(startMs, endMs) { mutableStateOf(false) }
    var startText by remember(startMs) { mutableStateOf(msToField(startMs)) }
    var endText by remember(endMs) { mutableStateOf(msToField(endMs)) }

    if (!editing) {
        AfText("⏱ ${msToField(startMs)} → ${msToField(endMs)}", muted = true, size = 11.sp)
        AfButton("Edit timing…", { editing = true }, enabled = true)
    } else {
        AfText("Edit timing (mm:ss.s)", bold = true, size = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            io.github.alalkipgen.autoflow.ui.components.AfTextField(
                startText, { startText = it }, "Start",
                modifier = androidx.compose.ui.Modifier.weight(1f)
            )
            AfText("→", size = 13.sp)
            io.github.alalkipgen.autoflow.ui.components.AfTextField(
                endText, { endText = it }, "End",
                modifier = androidx.compose.ui.Modifier.weight(1f)
            )
        }
        WrapRow {
            AfButton("Save", {
                val s = fieldToMs(startText)
                val e = fieldToMs(endText)
                if (s != null && e != null) { onSave(s, e); editing = false }
            }, kind = ButtonKind.Primary)
            AfButton("Cancel", { editing = false })
        }
    }
}

private fun msToField(ms: Long): String {
    val totalSec = ms / 1000.0
    val min = (totalSec / 60).toInt()
    val sec = totalSec % 60
    return "%d:%04.1f".format(min, sec)
}

private fun fieldToMs(text: String): Long? {
    val t = text.trim()
    return try {
        if (':' in t) {
            val (m, s) = t.split(':')
            ((m.trim().toLong() * 60 + s.trim().toDouble()) * 1000).toLong()
        } else {
            (t.toDouble() * 1000).toLong()
        }
    } catch (e: Exception) { null }
}

@Composable
private fun RecordTab(
    model: RecapModel,
    state: RecapUiState,
    recap: Recap,
    script: RecapScript,
    index: Int,
    idle: Boolean,
    seg: io.github.alalkipgen.autoflow.core.recap.RecapSegment,
    take: io.github.alalkipgen.autoflow.core.recap.VoiceTake?,
    stale: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
) {
    // Teleprompter: current line big, next muted
    val next = script.segments.getOrNull(index + 1)
    TeleprompterDisplay(seg.textMy, next?.textMy)

    WrapRow {
        if (state.recording == index) {
            AfButton(STOP, { model.stopRecording() }, kind = ButtonKind.Danger)
            AfButton("Cancel", { model.cancelRecording() })
        } else {
            if (model.canRecord) {
                AfButton(RECORD, { model.startRecording(index) }, kind = ButtonKind.Primary,
                    enabled = idle && seg.textMy.isNotBlank())
            }
            AfButton("Import file", { model.importTake(index) }, enabled = idle && seg.textMy.isNotBlank())
        }
        val file = state.takeFiles[index]
        if (take != null && file != null) AudioPlayButton(file, playing, onPlay)
    }
    if (stale) AfText("⚠ Stale — text changed since this take was recorded", color = AutoFlowColors.Amber, size = 12.sp)
}

@Composable
private fun CleanTab(
    model: RecapModel,
    state: RecapUiState,
    index: Int,
    idle: Boolean,
    take: io.github.alalkipgen.autoflow.core.recap.VoiceTake?,
) {
    if (take == null) {
        AfText("Record or import a take first.", muted = true)
        return
    }
    AfText("Clean: trim silence gaps, normalise loudness.", muted = true, size = 12.sp)
    // Polish / trim silence — deferred to 5A-8 SmartFit; button shown as stub.
    AfButton("✨ Polish (trim silence)", {}, enabled = false)
    AfText("Smart Fit polish (5A-8) not yet available.", muted = true, size = 11.sp)
}

/**
 * Teleprompter display (5B-5): current segment text large, next segment text muted below.
 */
@Composable
private fun TeleprompterDisplay(current: String, next: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AutoFlowShapes.Card)
            .background(Color(0xFF0D1526))
            .border(AutoFlowShapes.Border, AutoFlowColors.Line, AutoFlowShapes.Card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Current segment — big
        AfText(current.ifBlank { "(empty — write the script first)" }, size = 20.sp,
            color = if (current.isBlank()) AutoFlowColors.Muted else Color.White, bold = true)
        // Next segment — muted preview
        next?.let {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(AutoFlowColors.Background).padding(8.dp)) {
                AfText("Next: $it", size = 13.sp, muted = true)
            }
        }
    }
}
