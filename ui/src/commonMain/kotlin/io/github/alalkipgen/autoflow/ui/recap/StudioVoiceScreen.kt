package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.theme.AfSize
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
    val job = recapSpeakAllJob(model, state)
    val idle = !state.busy && state.recording == null && job?.active != true
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
        if (job != null && !job.active) RecapSpeakAllCard(model, job, pin = false)
        else if (job == null && state.job != null && state.scriptRunId == null) {
            StatusChip(StudioStatus.RUNNING)
            state.job?.let { AfText(state.jobNotice?.text(LocalUiLanguage.current) ?: it, color = AutoFlowColors.Cyan) }
        }
        state.message?.let { AfText(state.notice?.text(LocalUiLanguage.current) ?: it,
            color = if (state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber) }
    }
    val lockCard: @Composable () -> Unit = {
        TileCard(tr(UiText.VOICE), RecapTiles.Voice, Modifier.testTag("voice-lock")) {
            VoiceLockControls(model, state, recap, idle, compact = true, glass = true)
        }
    }
    val blockList: @Composable () -> Unit = {
        val labels = listOf(tr(UiText.V3_ALL_COUNT, "n" to rows.size),
            tr(UiText.V3_ATTENTION_COUNT, "n" to rows.count { it.attention }),
            tr(UiText.V3_VOICED_COUNT, "n" to rows.count { it.voiced }))
        WrapRow { labels.forEachIndexed { i, label -> RecapChip(label, i == filter, { filter = i }) } }
        gate.blockers.firstOrNull()?.let { AfText(it.text(LocalUiLanguage.current), muted = true) }
        if (rows.isEmpty()) AfText(tr(UiText.WRITE_STORY_FIRST), muted = true)
        val link = LocalTopPreviewLink.current
        rows.filter { filter == 0 || (filter == 1 && it.attention) || (filter == 2 && it.voiced) }.forEach { row ->
            key(row.block.id) {
                BlockCard(row.index + 1,
                    row.block.clips.joinToString(" · ") { "${RecapModel.clock(it.inMs)}–${RecapModel.clock(it.outMs)}" },
                    row.block.textMy, row.status, { selected = VoiceSelection(row.block.id) },
                    Modifier.fillMaxWidth().testTag("block-select-${row.index}"), selected = wide && detail?.block?.id == row.block.id,
                    deltaMs = row.deltaMs, thumbnail = { row.block.clips.firstOrNull()?.let {
                        BlockPreviewThumb(model, state, row.index, row.block.id, it.inMs, it.outMs)
                    } }, playing = link?.attached == true && link.playing && link.blockId == row.block.id) {
                    TakeVoiceLabel(recap.takeFor(row.block.id), recap.voiceLock, glass = true)
                    WrapRow {
                        state.takeFiles[row.index]?.takeIf { row.voiced }?.let { file ->
                            AudioPlayButton(file, playing == row.block.id, { playing = if (playing == row.block.id) null else row.block.id },
                                glass = true)
                        }
                        if (row.status == StudioStatus.TIMING)
                            GlassButton(My.breakSyllables(tr(UiText.V3_FIX_TIMING)), { selected = VoiceSelection(row.block.id, showFit = true) },
                                Modifier.testTag("voice-fit-${row.index}"), icon = AfIcons.Regular.Wrench, small = true)
                        // Re-voice (a take exists or the script changed) is secondary; Generate voice is the block's primary action.
                        val revoice = row.voiced || state.isStale(row.index)
                        GlassButton(My.breakSyllables(tr(if (revoice) UiText.V3_REVOICE else UiText.V3_GENERATE_VOICE)),
                            { selected = VoiceSelection(row.block.id) }, Modifier.testTag("voice-open-${row.index}"),
                            kind = if (revoice) ButtonKind.Secondary else ButtonKind.Primary,
                            icon = if (revoice) AfIcons.Regular.Redo else AfIcons.Regular.Wave, small = true)
                    }
                }
            }
        }
    }
    val diagnostics = remember(state.projectDir) { mutableStateOf(false) }
    val diagnosticsAction = diagnosticsAction(model, state, diagnostics)
    Column(Modifier.fillMaxSize().background(AutoFlowColors.Background), horizontalAlignment = Alignment.CenterHorizontally) {
        navigation?.let {
            ProjectTopBar(open?.project?.name.orEmpty(), 3, tr(UiText.VOICE), back, steps = recapStepLabels(), options = listOfNotNull(
                StudioAction(tr(UiText.CLASSIC_RECAP), it.classic, tag = "classic-recap", icon = AfIcons.Regular.Swap),
                diagnosticsAction.takeIf { !wide }))
        }
        RecapForegroundCards(model, state)
        job?.takeIf { it.active }?.let { RecapSpeakAllCard(model, it, pin = true) }
        if (wide) Box(Modifier.weight(1f).fillMaxWidth()) {
            // Concept 11: blocks · real preview · inspector (selected block, else the voice lock).
            ProjectPanes(windowWidthDp, list = {
                // The glass chips' 48 dp targets overflow their 34 dp pill: the padding sits inside the scroll viewport.
                GlassScrollColumn(Modifier.fillMaxSize().testTag("voice-list"), PaddingValues(vertical = (AfSize.Touch - AfSize.FilterChip) / 2),
                    Arrangement.spacedBy(12.dp)) { blockList() }
            }, editor = {
                Column(Modifier.fillMaxSize().paneScroll(scroll).padding(14.dp).testTag("voice-content"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    status()
                    if (detail == null) {
                        lockCard()
                        AfText(tr(UiText.V3_SELECT_BLOCK), muted = true)
                    } else key(detail.block.id) {
                        if (navigation == null) AfButton(tr(UiText.BACK), back, icon = AfIcons.Regular.Back)
                        StudioVoiceDetail(model, state, detail, rows, selected?.showFit == true, showPreview = false) {
                            selected = VoiceSelection(it)
                        }
                    }
                }
            }, preview = {
                Column(Modifier.fillMaxSize().paneScroll(rememberScrollState()).testTag("voice-preview"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecapTimelinePreview(model, blockId = detail?.block?.id, studio = true)
                    ExactFrameCard(model)
                    AfText(My.breakSyllables(tr(UiText.V3_SHORTCUTS_HINT)), Modifier.testTag("shortcuts-hint"), muted = true)
                }
            })
        } else TopPreviewHost(model, state, true, Modifier.weight(1f).widthIn(max = 900.dp).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().paneScroll(scroll).padding(14.dp).testTag("voice-content"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Phone / narrow window: the top preview (A §1), at the open block; Voice had no phone preview before.
                RecapTopPreview(model, diagnostics, blockId = detail?.block?.id)
                status()
                if (detail == null) {
                    lockCard()
                    blockList()
                } else {
                    if (navigation == null) AfButton(tr(UiText.BACK), back, icon = AfIcons.Regular.Back)
                    key(detail.block.id) {
                        StudioVoiceDetail(model, state, detail, rows, selected?.showFit == true, showPreview = false) {
                            selected = VoiceSelection(it)
                        }
                    }
                }
            }
        }
        if (detail == null) StickyActionBar(
            StudioAction(tr(UiText.V3_VOICE_REMAINING, "n" to remaining), { model.speakAll() },
                idle && gate.allowed && remaining > 0, "voice-remaining", AfIcons.Regular.Microphone),
            navigation?.let { StudioAction(tr(UiText.NEXT_STEP, "step" to tr(UiText.STYLE)), it.next, idle, "recap-next",
                trailingIcon = AfIcons.Regular.ArrowRight) },
            tr(UiText.V3_VOICE_COUNT, "n" to remaining), glass = true)
        else key(detail.block.id) { StudioBlockVoiceAction(model, state, detail) }
    }
    state.speakAllEta?.let { confirmation ->
        // Group 2 C 13e: the glass dialog (microphone tile), the hint as an info row, Cancel | Start (play).
        GlassDialog(model::cancelSpeakAll, AfIcons.Fill.Microphone, GlassColors.RecapPink, tr(UiText.V3_VOICE_REMAINING, "n" to confirmation.count)) {
            AfText(tr(UiText.V3_VOICE_REQUESTS, "n" to confirmation.count))
            GlassFact(AfIcons.Regular.Info, tr(UiText.SPEAK_ALL_HINT), colour = AutoFlowColors.Muted, caption = true)
            GlassDialogButtons(GlassDialogAction(tr(UiText.CANCEL), model::cancelSpeakAll, tag = "voice-confirm-cancel"),
                GlassDialogAction(tr(UiText.START_PLAY), { model.speakAll(confirmed = true) }, ButtonKind.Primary, AfIcons.Fill.Play,
                    idle && gate.allowed, "voice-confirm"))
        }
    }
}
