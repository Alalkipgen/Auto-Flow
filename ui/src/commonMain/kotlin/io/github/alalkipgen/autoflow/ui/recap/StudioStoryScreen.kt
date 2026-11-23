package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.recap.RecapRulesV2
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** Story uses the same native block inspector as Voice; only its route/actions differ. */
@Composable
internal fun StudioStoryScreen(model: RecapModel, windowWidthDp: Float, navigation: RecapNavigation?) {
    val state by model.state.collectAsState()
    val open by model.previewSource.projects.collectAsState()
    val recap = state.recap ?: return
    if (open?.dir != state.projectDir || open?.project?.recap != recap) return
    val rows = remember(state) { voiceBlockViews(state) }
    var selectedId by remember(state.projectDir) { mutableStateOf<String?>(null) }
    var playing by remember(state.projectDir) { mutableStateOf<String?>(null) }
    val detail = rows.firstOrNull { it.block.id == selectedId }
    val wide = windowWidthDp >= 840f
    val idle = !state.busy && state.recording == null
    val gate = RecapRulesV2.canApproveScript(recap, state.story)
    val approved = recap.storyApproved && gate.allowed
    val back = { if (detail != null) selectedId = null else navigation?.back?.invoke(); Unit }
    navigation?.handleBack?.invoke(detail != null, back)
    // Same targets as the detail ← / → buttons; from the list, → opens the first block.
    BlockShortcuts(
        previous = detail?.let { d -> rows.getOrNull(d.index - 1) }?.takeIf { state.recording == null }
            ?.let { p -> { selectedId = p.block.id } },
        next = (if (detail == null) rows.firstOrNull() else rows.getOrNull(detail.index + 1))?.takeIf { state.recording == null }
            ?.let { n -> { selectedId = n.block.id } },
    )
    val blocks: @Composable () -> Unit = {
        AfText(tr(UiText.BLOCKS), bold = true)
        if (rows.isEmpty()) AfText(tr(UiText.WRITE_STORY_FIRST), muted = true)
        val link = LocalTopPreviewLink.current
        rows.forEach { row -> key(row.block.id) {
            BlockCard(row.index + 1, row.block.clips.joinToString(" · ") {
                "${RecapModel.clock(it.inMs)}–${RecapModel.clock(it.outMs)}"
            }, row.block.textMy, row.status, { selectedId = row.block.id },
                Modifier.testTag("block-select-${row.index}"), selected = detail?.block?.id == row.block.id, deltaMs = row.deltaMs,
                thumbnail = { row.block.clips.firstOrNull()?.let {
                    BlockPreviewThumb(model, state, row.index, row.block.id, it.inMs, it.outMs)
                } }, playing = link?.attached == true && link.playing && link.blockId == row.block.id) {
                TakeVoiceLabel(recap.takeFor(row.block.id), recap.voiceLock)
                state.takeFiles[row.index]?.takeIf { row.voiced }?.let { file ->
                    AudioPlayButton(file, playing == row.block.id, { playing = if (playing == row.block.id) null else row.block.id })
                }
            }
        } }
    }
    val diagnostics = remember(state.projectDir) { mutableStateOf(false) }
    val diagnosticsAction = diagnosticsAction(model, state, diagnostics)
    Column(Modifier.fillMaxSize().background(AutoFlowColors.Background)) {
        navigation?.let { ProjectTopBar(open?.project?.name.orEmpty(), 2, tr(UiText.STORY), back,
            steps = recapStepLabels(), options = listOfNotNull(StudioAction(tr(UiText.CLASSIC_RECAP), it.classic, tag = "classic-recap",
                icon = AfIcons.Regular.Swap), diagnosticsAction.takeIf { !wide })) }
        RecapForegroundCards(model, state)
        TopPreviewHost(model, state, !wide, Modifier.weight(1f)) {
            ProjectPanes(windowWidthDp, list = {
                Column(Modifier.fillMaxSize().paneScroll(rememberScrollState()).testTag("story-list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) { blocks() }
            }, editor = {
                val scroll = key(state.projectDir, detail?.block?.id) { rememberScrollState() }
                Column(Modifier.fillMaxSize().paneScroll(scroll).padding(14.dp).testTag("story-content"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Phone / narrow window: the top preview (A §1), at the open block, instead of a ▶ preview page.
                    if (!wide) RecapTopPreview(model, diagnostics, blockId = detail?.block?.id)
                    if (state.job != null && state.scriptRunId == null) {
                        StatusChip(StudioStatus.RUNNING)
                        state.job?.let { AfText(state.jobNotice?.text(LocalUiLanguage.current) ?: it, color = AutoFlowColors.Cyan) }
                    }
                    state.message?.let { AfText(state.notice?.text(LocalUiLanguage.current) ?: it,
                        color = if (state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber) }
                    gate.blockers.firstOrNull()?.let { AfText(it.text(LocalUiLanguage.current), muted = true) }
                    if (wide || (detail == null && rows.isEmpty())) ScriptCard(model, state, recap, studioOptions = true, pinnedRun = true)
                    if (detail == null) {
                        if (wide) AfText(tr(UiText.V3_SELECT_BLOCK), muted = true) else {
                            blocks()
                            if (rows.isNotEmpty()) ScriptCard(model, state, recap, studioOptions = true, pinnedRun = true)
                        }
                    } else key(detail.block.id) {
                        if (navigation == null) AfButton(tr(UiText.BACK), back)
                        StudioVoiceDetail(model, state, detail, rows, false, showPreview = false) { selectedId = it }
                        StudioBlockVoiceAction(model, state, detail, inline = true)
                    }
                }
            }, preview = {
                Column(Modifier.fillMaxSize().paneScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecapTimelinePreview(model, blockId = detail?.block?.id, studio = true)
                    ExactFrameCard(model)
                    AfText(My.breakSyllables(tr(UiText.V3_SHORTCUTS_HINT)), Modifier.testTag("shortcuts-hint"), muted = true)
                }
            }, previewPage = false)
        }
        StickyActionBar(
            if (approved && navigation != null) StudioAction(tr(UiText.NEXT_STEP, "step" to tr(UiText.VOICE)),
                navigation.next, idle, "recap-next")
            else StudioAction(tr(UiText.V3_APPROVE_STORY), { model.approveScript() },
                idle && gate.allowed && !approved, "story-approve"),
            navigation?.let { StudioAction(tr(if (detail != null) UiText.BACK else UiText.PREVIOUS_STEP), back, idle, "recap-back") },
            if (approved) tr(UiText.APPROVED) else null)
    }
}