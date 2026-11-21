package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
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
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.RecapScript
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
    recap: RecapV2,
    script: RecapScript,
    segmentIndex: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
    showScript: Boolean = true,
    studio: Boolean = false,
) {
    val seg = script.segments.getOrNull(segmentIndex) ?: return
    val take = state.currentTake(segmentIndex)
    val stale = state.isStale(segmentIndex)
    val idle = !state.busy && state.recording == null
    var tab by remember { mutableStateOf(
        if (recap.voiceSource == VoiceSource.TTS) VoiceTab.SPEAK else VoiceTab.RECORD
    ) }
    var playing by remember { mutableStateOf(false) }

    AfCard(if (studio) tr(UiText.V3_BLOCK_TOOLS) else tr(UiText.VOICE_BLOCK_TITLE, "segmentIndex1" to (segmentIndex + 1)), modifier = modifier) {
        // Back link
        if (showBack) AfButton(tr(UiText.BACK), onClose)
        if (!studio) {
            AfText("${clock(seg.startMs)}–${clock(seg.endMs)} · ${seconds(seg.durationMs)}", muted = true, size = 12.sp)
            TakeVoiceLabel(recap.takeFor(state.story!!.blocks[segmentIndex].id), recap.voiceLock)
        }

        // Tab row
        WrapRow {
            VoiceTab.entries.filter { it != VoiceTab.CLONE }.forEach { t ->
                AfChip(voiceTabLabel(t, LocalUiLanguage.current), tab == t, onClick = { tab = t })
            }
            // Clone: Labs placeholder (always disabled)
            AfChip("${voiceTabLabel(VoiceTab.CLONE, LocalUiLanguage.current)} · ${tr(UiText.V3_PLANNED)}", false, onClick = {}, enabled = false)
        }

        when (tab) {
            VoiceTab.SPEAK -> SpeakTab(model, state, recap, script, segmentIndex, idle, seg.textMy, showScript, !studio)
            VoiceTab.RECORD -> RecordTab(model, state, recap, script, segmentIndex, idle, seg, take, stale, playing, !studio) { playing = !playing }
            VoiceTab.CLEAN -> CleanTab(model, state, segmentIndex, idle, take)
            VoiceTab.CLONE -> AfText(tr(UiText.VOICE_CLONE_LABS), muted = true)
        }
    }
}

@Composable
private fun SpeakTab(
    model: RecapModel,
    state: RecapUiState,
    recap: RecapV2,
    script: RecapScript,
    index: Int,
    idle: Boolean,
    text: String,
    showScript: Boolean,
    showAction: Boolean,
) {
    val seg = script.segments[index]
    if (showScript) AfText(seg.textMy, size = 13.sp)
    if (showAction) AfText(tr(UiText.VOICE_DETAILS, "ttsVoice" to (recap.ttsVoice), "name" to (recap.voiceSource.name)), muted = true, size = 11.sp)
    if (showAction) WrapRow {
        AfButton(tr(UiText.SPEAK_BLOCK), { model.speak(index) },
            kind = ButtonKind.Primary, enabled = idle && text.isNotBlank(), icon = AfIcons.Regular.Volume)
    }
    // Per-chunk caption timing edit (D-53, audit F05): the caption window in voice time (0 = the take starts);
    // saved as captions.chunkEdits — the movie clips are not changed.
    val window = state.captionWindow(index) ?: return
    val timing: @Composable () -> Unit = {
        TimingEditRow(window.first, window.second, state.hasCaptionTiming(index), { model.resetCaptionTiming(index) }) { start, end ->
            model.saveSegmentTiming(index, start, end)
        }
    }
    if (showAction) timing() else io.github.alalkipgen.autoflow.ui.components.SectionCard(tr(UiText.V3_MORE_OPTIONS), initiallyExpanded = false) { timing() }
}

/**
 * Inline caption timing editor (D-53, audit F05): the caption's start / end in the voice take (m:ss.s, 0 = the take
 * starts), two text fields, Save; "Automatic" drops the owner's timing.
 */
@Composable
internal fun TimingEditRow(startMs: Long, endMs: Long, custom: Boolean, onReset: () -> Unit, onSave: (Long, Long) -> Boolean) {
    var editing by remember(startMs, endMs) { mutableStateOf(false) }
    var startText by remember(startMs) { mutableStateOf(msToField(startMs)) }
    var endText by remember(endMs) { mutableStateOf(msToField(endMs)) }

    if (!editing) {
        AfText(tr(UiText.CAPTION_WINDOW, "msToFieldstartMs" to (msToField(startMs)), "msToFieldendMs" to (msToField(endMs)), "ifcustomelse" to (if (custom) "" else tr(UiText.AUTOMATIC_SUFFIX))), muted = true, size = 11.sp)
        WrapRow {
            AfButton(tr(UiText.EDIT_CAPTION_TIMING), { editing = true }, enabled = true)
            if (custom) AfButton(tr(UiText.AUTOMATIC), onReset)
        }
    } else {
        AfText(tr(UiText.CAPTION_TIMING_VOICE), bold = true, size = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            io.github.alalkipgen.autoflow.ui.components.AfTextField(
                startText, { startText = it }, tr(UiText.TIME_START),
                modifier = androidx.compose.ui.Modifier.weight(1f)
            )
            AfText("→", size = 13.sp)
            io.github.alalkipgen.autoflow.ui.components.AfTextField(
                endText, { endText = it }, tr(UiText.TIME_END),
                modifier = androidx.compose.ui.Modifier.weight(1f)
            )
        }
        WrapRow {
            AfButton(tr(UiText.SAVE), {
                val s = fieldToMs(startText)
                val e = fieldToMs(endText)
                if (s != null && e != null && onSave(s, e)) editing = false
            }, kind = ButtonKind.Primary)
            AfButton(tr(UiText.CANCEL), { editing = false })
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
    recap: RecapV2,
    script: RecapScript,
    index: Int,
    idle: Boolean,
    seg: io.github.alalkipgen.autoflow.core.recap.RecapSegment,
    take: io.github.alalkipgen.autoflow.core.recap.BlockTake?,
    stale: Boolean,
    playing: Boolean,
    showPlay: Boolean,
    onPlay: () -> Unit,
) {
    // Teleprompter: current line big, next muted
    val next = script.segments.getOrNull(index + 1)
    TeleprompterDisplay(seg.textMy, next?.textMy)

    WrapRow {
        if (state.recording == index) {
            AfButton(tr(UiText.STOP), { model.stopRecording() }, kind = ButtonKind.Danger, icon = AfIcons.Fill.Square)
            AfButton(tr(UiText.CANCEL), { model.cancelRecording() })
        } else {
            if (model.canRecord) {
                AfButton(tr(UiText.RECORD), { model.startRecording(index) }, kind = ButtonKind.Primary,
                    enabled = idle && seg.textMy.isNotBlank(), icon = AfIcons.Regular.Dub)
            }
            AfButton(tr(UiText.IMPORT_FILE), { model.importTake(index) }, enabled = idle && seg.textMy.isNotBlank())
        }
        val file = state.takeFiles[index]
        if (showPlay && take != null && file != null) AudioPlayButton(file, playing, onPlay)
    }
    if (stale) AfText(tr(UiText.TAKE_TEXT_CHANGED, "STALE" to (tr(UiText.STALE))), color = AutoFlowColors.Amber, size = 12.sp)
}

@Composable
private fun CleanTab(
    model: RecapModel,
    state: RecapUiState,
    index: Int,
    idle: Boolean,
    take: io.github.alalkipgen.autoflow.core.recap.BlockTake?,
) {
    if (take == null) {
        AfText(tr(UiText.TAKE_FIRST), muted = true)
        return
    }
    AfText(tr(UiText.CLEAN_HINT), muted = true, size = 12.sp)
    // Polish / trim silence via ffmpeg silenceremove (available after render, D-54).
    AfButton(tr(UiText.CLEAN_TAKE), { model.cleanTake(index) }, enabled = idle, kind = ButtonKind.Primary, icon = AfIcons.Regular.Scissors)
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
            .background(AutoFlowColors.Well)
            .border(AutoFlowShapes.Border, AutoFlowColors.Line, AutoFlowShapes.Card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Current segment — big
        AfText(current.ifBlank { tr(UiText.EMPTY_SCRIPT_FIRST) }, size = 20.sp,
            color = if (current.isBlank()) AutoFlowColors.Muted else Color.White, bold = true)
        // Next segment — muted preview
        next?.let {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(AutoFlowColors.Background).padding(8.dp)) {
                AfText(tr(UiText.TELEPROMPTER_NEXT, "it" to (it)), size = 13.sp, muted = true)
            }
        }
    }
}

/** Burmese Voice & Audio tab name (5B-13, DESIGN.md §7 "Voice"). */
internal fun voiceTabLabel(tab: VoiceTab, language: UiLanguage = UiLanguage.ENGLISH): String = when (tab) {
    VoiceTab.SPEAK -> UiText.VOICE_TAB_SPEAK.text(language)
    VoiceTab.RECORD -> UiText.VOICE_TAB_RECORD.text(language)
    VoiceTab.CLEAN -> UiText.VOICE_TAB_CLEAN.text(language)
    VoiceTab.CLONE -> UiText.VOICE_TAB_CLONE.text(language)
}
