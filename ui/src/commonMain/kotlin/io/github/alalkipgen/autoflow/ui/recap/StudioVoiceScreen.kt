package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import kotlin.math.abs

/** Route callbacks only; no new persisted state or workflow policy. */
class RecapNavigation(val back: () -> Unit, val next: () -> Unit, val classic: () -> Unit,
    val handleBack: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> })

private data class VoiceSelection(val id: String, val showFit: Boolean = false)

internal data class VoiceBlockView(val index: Int, val block: RecapBlock, val fit: FitRow,
    val status: StudioStatus, val deltaMs: Long?, val voiced: Boolean, val attention: Boolean)

/** Uses native block IDs, all clip ranges, selected takes and the live pronunciation snapshot. */
internal fun voiceBlockViews(state: RecapUiState): List<VoiceBlockView> {
    val recap = state.recap ?: return emptyList()
    val story = state.story ?: return emptyList()
    return RecapFit.rows(recap, story, state.lexicon).map { row ->
        val block = story.blocks[row.segment]
        val take = state.currentTake(row.segment)
        // Include the renderer's existing 400 ms tail and selected Fit speed, not a guessed source duration.
        val delta = take?.let { ((it.durationMs + RecapFit.TAIL_MS) / row.voiceSpeed).toLong() - row.sceneMs }
        val status = when {
            state.isStale(row.segment) -> StudioStatus.SCRIPT_CHANGED
            take == null -> StudioStatus.NO_VOICE
            delta != null && abs(delta) > 400 -> StudioStatus.TIMING
            else -> StudioStatus.FITS
        }
        val different = recap.takeFor(block.id)?.voiceStatus(recap.voiceLock) in setOf(TakeVoiceStatus.DIFFERENT, TakeVoiceStatus.UNKNOWN)
        VoiceBlockView(row.segment, block, row, status, delta, take != null, status != StudioStatus.FITS || different)
    }
}

@Composable
internal fun StudioVoiceScreen(model: RecapModel, navigation: RecapNavigation?, windowWidthDp: Float = 0f) {
    val state by model.state.collectAsState()
    val open by model.previewSource.projects.collectAsState()
    val recap = state.recap ?: return
    if (open?.dir != state.projectDir || open?.project?.recap != recap) return
    val rows = remember(state) { voiceBlockViews(state) }
    var selected by remember(state.projectDir) { mutableStateOf<VoiceSelection?>(null) }
    var filter by remember(state.projectDir) { mutableIntStateOf(0) }
    var playing by remember(state.projectDir) { mutableStateOf<String?>(null) }
    val detail = rows.firstOrNull { it.block.id == selected?.id }
    val idle = !state.busy && state.recording == null
    val gate = RecapRulesV2.canVoice(recap, state.story)
    val remaining = rows.count { !it.voiced && SpokenText.of(it.block, state.lexicon).isNotBlank() }
    val scroll = key(state.projectDir, selected?.id) { rememberScrollState() }
    val back = { if (selected != null) selected = null else navigation?.back?.invoke(); Unit }
    navigation?.handleBack?.invoke(detail != null, back)
    val wide = windowWidthDp >= 840f
    // Same targets as the detail ← / → buttons; from the list, → opens the first block.
    BlockShortcuts(
        previous = detail?.let { d -> rows.getOrNull(d.index - 1) }?.takeIf { state.recording == null }
            ?.let { p -> { selected = VoiceSelection(p.block.id) } },
        next = (if (detail == null) rows.firstOrNull() else rows.getOrNull(detail.index + 1))?.takeIf { state.recording == null }
            ?.let { n -> { selected = VoiceSelection(n.block.id) } },
    )
    val status: @Composable () -> Unit = {
        if (state.busy) StatusChip(StudioStatus.RUNNING)
        state.job?.let { AfText(state.jobNotice?.text(LocalUiLanguage.current) ?: it, color = AutoFlowColors.Cyan) }
        state.message?.let { AfText(state.notice?.text(LocalUiLanguage.current) ?: it,
            color = if (state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber) }
    }
    val lockCard: @Composable () -> Unit = {
        AfCard(tr(UiText.VOICE), Modifier.testTag("voice-lock")) { VoiceLockControls(model, state, recap, idle, compact = true) }
    }
    val blockList: @Composable () -> Unit = {
        val labels = listOf(tr(UiText.V3_ALL_COUNT, "n" to rows.size),
            tr(UiText.V3_ATTENTION_COUNT, "n" to rows.count { it.attention }),
            tr(UiText.V3_VOICED_COUNT, "n" to rows.count { it.voiced }))
        OptionChips(labels, labels[filter], { filter = labels.indexOf(it) })
        gate.blockers.firstOrNull()?.let { AfText(it.text(LocalUiLanguage.current), muted = true) }
        if (rows.isEmpty()) AfText(tr(UiText.WRITE_STORY_FIRST), muted = true)
        rows.filter { filter == 0 || (filter == 1 && it.attention) || (filter == 2 && it.voiced) }.forEach { row ->
            key(row.block.id) {
                BlockCard(row.index + 1,
                    row.block.clips.joinToString(" · ") { "${RecapModel.clock(it.inMs)}–${RecapModel.clock(it.outMs)}" },
                    row.block.textMy, row.status, { selected = VoiceSelection(row.block.id) },
                    Modifier.fillMaxWidth().testTag("block-select-${row.index}"), selected = wide && detail?.block?.id == row.block.id,
                    deltaMs = row.deltaMs, thumbnail = { row.block.clips.firstOrNull()?.let {
                        RecapThumbnail(model, state, it.inMs, it.outMs, "block-thumbnail-${row.block.id}")
                    } }) {
                    TakeVoiceLabel(recap.takeFor(row.block.id), recap.voiceLock)
                    WrapRow {
                        state.takeFiles[row.index]?.takeIf { row.voiced }?.let { file ->
                            AudioPlayButton(file, playing == row.block.id, { playing = if (playing == row.block.id) null else row.block.id })
                        }
                        if (row.status == StudioStatus.TIMING)
                            AfButton(My.breakSyllables(tr(UiText.V3_FIX_TIMING)), { selected = VoiceSelection(row.block.id, showFit = true) },
                                Modifier.testTag("voice-fit-${row.index}"))
                        AfButton(My.breakSyllables(tr(if (row.voiced || state.isStale(row.index)) UiText.V3_REVOICE else UiText.V3_GENERATE_VOICE)),
                            { selected = VoiceSelection(row.block.id) }, Modifier.testTag("voice-open-${row.index}"))
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(AutoFlowColors.Background), horizontalAlignment = Alignment.CenterHorizontally) {
        navigation?.let {
            ProjectTopBar(open?.project?.name.orEmpty(), 3, tr(UiText.VOICE), back, options = listOf(
                StudioAction(tr(UiText.CLASSIC_RECAP), it.classic, tag = "classic-recap")))
        }
        if (wide) Box(Modifier.weight(1f).fillMaxWidth()) {
            // Concept 11: blocks · real preview · inspector (selected block, else the voice lock).
            ProjectPanes(windowWidthDp, list = {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("voice-list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) { blockList() }
            }, editor = {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp).testTag("voice-content"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    status()
                    if (detail == null) {
                        lockCard()
                        AfText(tr(UiText.V3_SELECT_BLOCK), muted = true)
                    } else key(detail.block.id) {
                        if (navigation == null) AfButton(tr(UiText.BACK), back)
                        StudioVoiceDetail(model, state, detail, rows, selected?.showFit == true, showPreview = false) {
                            selected = VoiceSelection(it)
                        }
                    }
                }
            }, preview = {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("voice-preview"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecapTimelinePreview(model, blockId = detail?.block?.id, studio = true)
                    ExactFrameCard(model)
                    AfText(My.breakSyllables(tr(UiText.V3_SHORTCUTS_HINT)), Modifier.testTag("shortcuts-hint"), muted = true)
                }
            })
        } else Column(Modifier.weight(1f).widthIn(max = 900.dp).fillMaxWidth().verticalScroll(scroll).padding(14.dp)
            .testTag("voice-content"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            status()
            if (detail == null) {
                lockCard()
                blockList()
            } else {
                if (navigation == null) AfButton(tr(UiText.BACK), back)
                key(detail.block.id) {
                    StudioVoiceDetail(model, state, detail, rows, selected?.showFit == true) {
                        selected = VoiceSelection(it)
                    }
                }
            }
        }
        if (detail == null) StickyActionBar(
            StudioAction(tr(UiText.V3_VOICE_REMAINING, "n" to remaining), { model.speakAll() },
                idle && gate.allowed && remaining > 0, "voice-remaining"),
            navigation?.let { StudioAction(tr(UiText.NEXT_STEP, "step" to tr(UiText.STYLE)), it.next, idle, "recap-next") },
            tr(UiText.V3_VOICE_COUNT, "n" to remaining))
        else key(detail.block.id) { StudioBlockVoiceAction(model, state, detail) }
    }
    state.speakAllEta?.let { confirmation ->
        Dialog(onDismissRequest = model::cancelSpeakAll) {
            AfCard(tr(UiText.V3_VOICE_REMAINING, "n" to confirmation.count)) {
                AfText(tr(UiText.V3_VOICE_REQUESTS, "n" to confirmation.count))
                AfText(tr(UiText.SPEAK_ALL_HINT), muted = true)
                WrapRow {
                    AfButton(tr(UiText.START_PLAY), { model.speakAll(confirmed = true) },
                        Modifier.testTag("voice-confirm"), kind = ButtonKind.Primary, enabled = idle && gate.allowed)
                    AfButton(tr(UiText.CANCEL), model::cancelSpeakAll, Modifier.testTag("voice-confirm-cancel"))
                }
            }
        }
    }
}
