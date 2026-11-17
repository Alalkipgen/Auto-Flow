package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native ID-bound presentation; all synthesis/approval/save behaviour remains in the existing model/core. */
@Composable
internal fun StudioVoiceDetail(model: RecapModel, state: RecapUiState, row: VoiceBlockView,
    rows: List<VoiceBlockView>, showFit: Boolean, onSelect: (String) -> Unit,
) {
    val recap = state.recap ?: return
    val open by model.previewSource.projects.collectAsState()
    val source = remember(state, open) { model.previewSource.resolve(state) }
    val clips = source?.timeline?.clips.orEmpty().filter { it.blockId == row.block.id }
    val previousLabel = tr(UiText.V3_PREVIOUS_BLOCK)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AfButton("←", { rows.getOrNull(row.index - 1)?.let { onSelect(it.block.id) } },
            Modifier.testTag("voice-detail-previous").semantics { contentDescription = previousLabel }, enabled = row.index > 0 && state.recording == null)
        AfText(tr(UiText.V3_BLOCK_OF, "n" to row.index + 1, "total" to rows.size), Modifier.weight(1f).testTag("voice-detail-number"), bold = true)
        val nextLabel = tr(UiText.V3_NEXT_BLOCK)
        AfButton("→", { rows.getOrNull(row.index + 1)?.let { onSelect(it.block.id) } },
            Modifier.testTag("voice-detail-next").semantics { contentDescription = nextLabel }, enabled = row.index < rows.lastIndex && state.recording == null)
    }
    StatusChip(row.status, deltaMs = row.deltaMs)
    AfText(tr(UiText.V3_SOURCE_RANGES, "ranges" to row.block.clips.joinToString(" · ") {
        "${RecapModel.clock(it.inMs)}–${RecapModel.clock(it.outMs)}" }), Modifier.testTag("voice-detail-source"), muted = true)
    if (clips.isNotEmpty()) AfText(tr(UiText.V3_OUTPUT_RANGE, "range" to
        "${RecapModel.clock(clips.first().outputFromMs)}–${RecapModel.clock(clips.last().outputToMs)}"), Modifier.testTag("voice-detail-output"), muted = true)
    RecapTimelinePreview(model, blockId = row.block.id, studio = true)
    ExactFrameCard(model)
    BlockScriptCard(model, state, row.block, row.index)
    BlockClipsCard(model, state, row.block)
    AfCard(tr(UiText.VOICE)) { VoiceLockControls(model, state, recap, !state.busy && state.recording == null, compact = true) }
    SectionCard(tr(UiText.V3_FIX_TIMING), initiallyExpanded = showFit || row.status == StudioStatus.TIMING) {
        SmartFitChoices(model, recap, row.block.id, row.fit, !state.busy && state.recording == null, showOverrunHint = false)
    }
    StudioTakeHistory(model, state, row.block)
    state.script?.let { VoiceBlockCard(model, state, recap, it, row.index, {}, showBack = false, showScript = false, studio = true) }
}

@Composable
private fun StudioTakeHistory(model: RecapModel, state: RecapUiState, block: RecapBlock) {
    val open by model.previewSource.projects.collectAsState()
    var entries by remember(state.projectDir, block.id) { mutableStateOf<List<TakeHistoryEntry>?>(null) }
    var loading by remember(state.projectDir, block.id) { mutableStateOf(true) }
    var playing by remember(state.projectDir, block.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(open?.project, state.projectDir, state.recap, block.id) {
        entries = null; playing = null; loading = true
        val captured = open
        entries = withContext(Dispatchers.Default) { model.voiceInspector.begin(block.id)
            ?.takeIf { it.dir == state.projectDir && it.project == captured?.project }
            ?.let(model.voiceInspector::history) }
        loading = false
    }
    val rows = entries
    AfCard(tr(UiText.V3_TAKES_COUNT, "n" to state.recap?.takes.orEmpty().count { it.block == block.id }), Modifier.testTag("voice-takes")) {
        if (rows == null) AfText(tr(if (loading) UiText.V3_LOADING_TAKES else UiText.TAKE_UNREADABLE), muted = true)
        else if (rows.isEmpty()) StatusChip(StudioStatus.NO_VOICE)
        else {
            val maxMs = rows.maxOf { it.take.durationMs }.coerceAtLeast(1).toFloat()
            val takeRow: @Composable (TakeHistoryEntry) -> Unit = { entry ->
                val duration = RecapModel.seconds(entry.take.durationMs)
                AfText(tr(UiText.V3_TAKE_DURATION, "n" to entry.number, "duration" to duration), Modifier.testTag("voice-take-${entry.number}"), bold = true)
                if (entry.selected) AfText(tr(UiText.V3_SELECTED), Modifier.semantics { selected = true }, color = AutoFlowColors.Purple)
                if (SpokenText.isStale(entry.take, block, state.lexicon)) StatusChip(StudioStatus.SCRIPT_CHANGED)
                TakeVoiceLabel(entry.take, state.recap?.voiceLock)
                Canvas(Modifier.fillMaxWidth().height(6.dp).semantics {
                    contentDescription = duration
                    progressBarRangeInfo = ProgressBarRangeInfo(entry.take.durationMs.toFloat().coerceIn(0f, maxMs), 0f..maxMs)
                }) {
                    drawRect(AutoFlowColors.Line)
                    drawRect(if (entry.selected) AutoFlowColors.Purple else AutoFlowColors.Muted,
                        size = size.copy(width = size.width * (entry.take.durationMs / maxMs).coerceIn(0f, 1f)))
                }
                entry.file?.let { AudioPlayButton(it, playing == entry.number, { playing = if (playing == entry.number) null else entry.number }) }
                    ?: AfText(tr(UiText.TAKE_MISSING), color = AutoFlowColors.Amber)
            }
            rows.singleOrNull { it.selected }?.let { takeRow(it) }
            val others = rows.filterNot { it.selected }
            if (others.isNotEmpty()) SectionCard(tr(UiText.V3_TAKE_HISTORY, "n" to others.size), initiallyExpanded = false) { others.reversed().forEach { takeRow(it) } }
        }
    }
}

@Composable
internal fun StudioBlockVoiceAction(model: RecapModel, state: RecapUiState, row: VoiceBlockView) {
    val open by model.previewSource.projects.collectAsState()
    var pending by remember(state.projectDir, row.block.id) { mutableStateOf<BlockVoiceRequest?>(null) }
    var rejected by remember(state.projectDir, row.block.id) { mutableStateOf(false) }
    val idle = !state.busy && state.recording == null
    val allowed = idle && state.recap?.let { RecapRulesV2.canVoice(it, state.story).allowed } == true && SpokenText.of(row.block, state.lexicon).isNotBlank()
    val label = tr(if (row.voiced || state.isStale(row.index)) UiText.V3_REVOICE_BLOCK else UiText.V3_GENERATE_VOICE)
    StickyActionBar(StudioAction(label, {
        rejected = false
        pending = model.voiceInspector.begin(row.block.id)?.takeIf { it.dir == state.projectDir && it.project == open?.project }
    }, allowed, "voice-block-request"),
        caption = if (rejected) tr(UiText.V3_BLOCK_REVISION_CHANGED) else tr(UiText.V3_ONLY_BLOCK, "n" to row.index + 1))
    val request = pending?.takeIf { it.project == open?.project && it.dir == state.projectDir &&
        it.spoken == SpokenText.of(row.block, state.lexicon) } ?: return
    Dialog(onDismissRequest = { pending = null }) {
        AfCard(label, Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AfText(tr(UiText.V3_ONLY_BLOCK, "n" to row.index + 1))
                AfText(request.spoken, Modifier.testTag("voice-block-confirm-text"))
                AfText(tr(UiText.V3_ONE_VOICE_REQUEST), muted = true)
                AfText(tr(UiText.V3_NEW_TAKE_EFFECT), muted = true)
            }
            StickyActionBar(StudioAction(tr(UiText.START_PLAY), {
                rejected = !model.voiceInspector.confirm(request); pending = null
                if (rejected) model.refresh()
            }, allowed, "voice-block-confirm"),
                StudioAction(tr(UiText.CANCEL), { pending = null }, tag = "voice-block-cancel"))
        }
    }
}
