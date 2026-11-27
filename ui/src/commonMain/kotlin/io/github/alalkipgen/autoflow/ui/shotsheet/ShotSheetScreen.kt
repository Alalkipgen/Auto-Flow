package io.github.alalkipgen.autoflow.ui.shotsheet

import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfChip
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.decodeImage
import io.github.alalkipgen.autoflow.ui.handoff.BatchPanel
import io.github.alalkipgen.autoflow.ui.handoff.CreditCounter
import io.github.alalkipgen.autoflow.ui.handoff.FlowHandoffModel
import io.github.alalkipgen.autoflow.ui.handoff.HandoffCard
import io.github.alalkipgen.autoflow.ui.handoff.RefThumb
import io.github.alalkipgen.autoflow.ui.handoff.THUMB_H
import io.github.alalkipgen.autoflow.ui.handoff.THUMB_W
import io.github.alalkipgen.autoflow.ui.handoff.VideoThumb
import io.github.alalkipgen.autoflow.ui.input.NO_PROJECT_TEXT
import io.github.alalkipgen.autoflow.ui.recap.ProjectPanes
import io.github.alalkipgen.autoflow.ui.references.ReferencesModel
import io.github.alalkipgen.autoflow.ui.references.RefRow
import io.github.alalkipgen.autoflow.ui.shots.PromptSection
import io.github.alalkipgen.autoflow.ui.shots.ShotRow
import io.github.alalkipgen.autoflow.ui.shots.ShotsModel
import io.github.alalkipgen.autoflow.ui.shots.StatusBadge
import io.github.alalkipgen.autoflow.ui.shots.VersionBrowser
import io.github.alalkipgen.autoflow.ui.storyboard.Header
import io.github.alalkipgen.autoflow.ui.storyboard.NO_STORYBOARD_TEXT
import io.github.alalkipgen.autoflow.ui.storyboard.ShotCard
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardModel
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardResult
import io.github.alalkipgen.autoflow.ui.storyboard.message
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes
import kotlinx.coroutines.launch

internal const val SHOT_SHEET_TITLE = "Shot စာရွက်"
internal const val SHEET_STORYBOARD = "1 · Storyboard"
internal const val SHEET_PROMPT = "2 · Prompt"
internal const val SHEET_REFS = "3 · Reference ပုံများ (Ref A / B)"
internal const val SHEET_FLOW = "4 · Flow သို့ ပို့ရန်"
internal const val SHEET_VIDEOS = "5 · ဗွီဒီယိုများ · လက်ခံရန်"
internal const val ALL_SHOTS = "Shot အားလုံး"
internal const val NO_VIDEO_YET = "ဗွီဒီယို မရှိသေးပါ — အဆင့် 4 မှာ တစ်ခု ထည့်ပါ။"
internal const val NO_REF_YET = "Reference ပုံ မရှိသေးပါ — ပုံ prompt ကို ကူးပြီး AI Studio သို့မဟုတ် Flow မှာ ဆွဲပြီး ပြန်ထည့်ပါ။"

/**
 * The classic screens' messages name the tab to go to ("(Shots screen)"); on the sheet the same work is a step of
 * this sheet, so the hint names the step instead. Unknown text is shown as it is.
 */
internal fun forSheet(text: String, language: UiLanguage = UiLanguage.ENGLISH): String = text
    .replace(" (Ref A/B on the References screen)", UiText.SHEET_STEP_REFS_HINT.text(language))
    .replace(" — compile it on the Shots screen first", UiText.SHEET_COMPILE_HINT.text(language))
    .replace(" (Shots screen)", UiText.SHEET_STEP_PROMPT_HINT.text(language))

/**
 * Music Video shot sheet (5B-12, D-40, DESIGN.md §7 "Music Video"): one sheet per shot instead of the Storyboard /
 * Shots / References / Flow Handoff tabs under `studioV2` — storyboard text (edit / approve / new take), prompt
 * (compile / approve), Ref A / B (copy image prompt, import, pick, approve), Flow handoff (open Flow, copy prompt,
 * pass, import video, credits) and the video versions with accept. Every action is the existing model action of
 * the classic screens (same gates, same saves into the open project); nothing here keeps its own copy of the shot.
 * ≥ 840 dp window: shots list · sheet · media preview; narrower: the sheet with shot chips and "All shots".
 */
@Composable
fun ShotSheetScreen(
    storyboard: StoryboardModel,
    jobs: StoryboardJobs,
    shots: ShotsModel,
    refs: ReferencesModel,
    handoff: FlowHandoffModel,
    readBytes: suspend (String) -> ByteArray?,
    windowWidthDp: Float,
    onGoToInput: () -> Unit,
) {
    val sb by storyboard.state.collectAsState(storyboard.current())
    val sbMessage by storyboard.message.collectAsState()
    val job by jobs.state.collectAsState()
    val shotsState by shots.state.collectAsState(shots.current())
    val refState by refs.state.collectAsState(refs.current())
    val flow by handoff.state.collectAsState(handoff.current())
    val scope = rememberCoroutineScope()
    val uiLanguage = LocalUiLanguage.current
    val busy = job.running != null
    val wide = windowWidthDp >= 840f

    if (shotsState.projectName == null) { AfText(tr(UiText.OPEN_PROJECT_FIRST), Modifier.padding(16.dp), muted = true); return }
    val rows = shotsState.rows
    if (rows.isEmpty()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AfText(tr(UiText.SHOT_SHEET), size = 18.sp, bold = true)
            AfText(tr(UiText.STORYBOARD_EMPTY), muted = true)
            AfButton(tr(UiText.GO_INPUT), onGoToInput, Modifier.heightIn(min = 48.dp), kind = ButtonKind.Primary)
        }
        return
    }
    val selected = shotsState.selected?.takeIf { n -> rows.any { it.number == n } } ?: rows.first().number
    LaunchedEffect(selected, shotsState.selected) { if (shotsState.selected != selected) shots.select(selected) }
    val index = rows.indexOfFirst { it.number == selected }
    val row = rows[index]
    val card = sb.cards.firstOrNull { it.number == selected }
    val detail = shotsState.detail?.takeIf { it.number == selected }
    val refRow = refState.rows.firstOrNull { it.number == selected }
    val flowRow = flow.rows.firstOrNull { it.number == selected }

    // Always the wrapping (phone) arrangement: the list pane is narrow, and a large font must not push buttons out.
    val allShots: @Composable () -> Unit = {
        CompositionLocalProvider(LocalCompact provides true) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Header(sb, storyboard, busy)
                CreditCounter(flow, handoff)
                BatchPanel(flow, handoff)
            }
        }
    }

    val list: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AfText(tr(UiText.SHOTS), bold = true)
            rows.forEach { r -> SheetShotItem(r, r.number == selected) { shots.select(r.number) } }
            AfText(tr(UiText.ALL_SHOTS), bold = true)
            allShots()
        }
    }

    val preview: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText(tr(UiText.SHEET_MEDIA_SHOT, "number" to (row.number)), muted = true)
            flowRow?.let { f ->
                RefThumb(f, handoff)
                VideoThumb(f, handoff)
            }
        }
    }

    val sheet: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp).testTag("shot-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!wide) {
                WrapRow {
                    rows.forEach { r ->
                        AfChip("#${r.number}", r.number == selected, { shots.select(r.number) },
                            Modifier.heightIn(min = 48.dp).testTag("shot-sheet-chip-${r.number}"))
                    }
                }
            }
            // Header: which shot, its badge, previous / next.
            WrapRow {
                AfText("Shot ${row.number} · ${row.timecode}", Modifier.testTag("shot-sheet-title"), size = 16.sp, bold = true)
                StatusBadge(row.status)
            }
            card?.lyric?.takeIf { it.isNotBlank() }?.let { AfText(My.breakSyllables(it), muted = true) }
            WrapRow {
                rows.getOrNull(index - 1)?.let { p ->
                    AfButton("← Shot ${p.number}", { shots.select(p.number) }, Modifier.heightIn(min = 48.dp).testTag("shot-sheet-prev"))
                }
                rows.getOrNull(index + 1)?.let { n ->
                    AfButton("Shot ${n.number} →", { shots.select(n.number) }, Modifier.heightIn(min = 48.dp).testTag("shot-sheet-next"))
                }
            }

            AfCard(tr(UiText.SHEET_STORYBOARD)) {
                job.last?.takeIf { it != StoryboardResult.Done }?.message()?.let { AfText(it, color = AutoFlowColors.Amber) }
                sbMessage?.let { AfText(it, color = AutoFlowColors.Amber) }
                if (card != null) ShotCard(card, storyboard, busy, showHeader = false)
                else AfText(tr(UiText.SHEET_NO_STORY_CARD), color = AutoFlowColors.Amber)
            }

            AfCard(tr(UiText.SHEET_PROMPT)) {
                shotsState.actionError?.let { AfText(it, color = AutoFlowColors.Amber) }
                if (detail != null) PromptSection(detail, shots) else AfText("…", muted = true)
            }

            AfCard(tr(UiText.SHEET_REFS)) {
                refState.message?.let { AfText(refState.notice?.text(uiLanguage) ?: forSheet(it, uiLanguage), color = AutoFlowColors.Amber) }
                if (refRow != null) RefSection(refRow, refs, importRef = {
                    scope.launch { refs.pickAndImportRef(refRow.number) { readBytes(it) } }
                })
            }

            AfCard(tr(UiText.SHEET_FLOW)) {
                flow.message?.let { AfText(flow.notice?.text(uiLanguage) ?: forSheet(it, uiLanguage), color = AutoFlowColors.Amber) }
                AfButton(tr(UiText.OPEN_FLOW), { handoff.openFlow() }, Modifier.heightIn(min = 48.dp), kind = ButtonKind.Flow)
                if (flowRow != null) HandoffCard(flowRow.copy(gateMsg = flowRow.gateMsg?.let { forSheet(it, uiLanguage) }), LocalCompact.current, handoff, importVideo = {
                    scope.launch { handoff.pickAndImportVideo(flowRow.number) { readBytes(it) } }
                }, fast = flow.creditHintFast, quality = flow.creditHintQuality, showHeader = false,
                    noPromptText = tr(UiText.SHEET_COMPILE_FIRST))
            }

            AfCard(tr(UiText.SHEET_VIDEOS)) {
                if (detail != null && detail.versions.isNotEmpty()) VersionBrowser(detail, shots)
                else AfText(tr(UiText.SHEET_NO_VIDEO), muted = true)
            }

            if (!wide) {
                var open by remember { mutableStateOf(false) }
                AfButton((if (open) "▼ " else "▶ ") + tr(UiText.ALL_SHOTS), { open = !open }, Modifier.heightIn(min = 48.dp).testTag("shot-sheet-all"))
                if (open) allShots()
            }
        }
    }

    ProjectPanes(windowWidthDp, list = list, editor = sheet, preview = preview)
}

/** Ref A / B for one shot: the image prompt (copy), AI Studio, import, a tile per image to pick it, approve. */
@Composable
private fun RefSection(row: RefRow, model: ReferencesModel, importRef: () -> Unit) {
    val uiLanguage = LocalUiLanguage.current
    row.copyGateMsg?.let { AfText("⚠ ${row.copyBlocker?.text(uiLanguage) ?: forSheet(it, uiLanguage)}", color = AutoFlowColors.Amber, size = 12.sp) }
    WrapRow {
        AfButton(tr(UiText.COPY_IMAGE_PROMPT), { model.copyImagePrompt(row.number) }, Modifier.heightIn(min = 48.dp),
            kind = ButtonKind.Secondary, enabled = row.imagePrompt != null && row.copyGateMsg == null)
        AfButton(tr(UiText.OPEN_AI_STUDIO), { model.openAiStudio() }, Modifier.heightIn(min = 48.dp), kind = ButtonKind.Secondary)
        AfButton(tr(UiText.IMPORT_IMAGE), importRef, Modifier.heightIn(min = 48.dp).testTag("ref-import"), kind = ButtonKind.Secondary)
    }
    if (row.refs.isEmpty()) {
        AfText(tr(UiText.SHEET_NO_REF), muted = true, size = 12.sp)
        return
    }
    WrapRow(spacing = 12.dp) {
        row.refs.forEachIndexed { i, id -> RefTile(ReferencesModel.refLabel(i), id, id == row.chosenRef, model) { model.setChosenRef(row.number, id) } }
    }
    val approveLabel = if (row.refApproved) "✓ Reference ${tr(UiText.APPROVED)}" else "✓ ${row.refs.indexOf(row.chosenRef).takeIf { it >= 0 }?.let(ReferencesModel::refLabel) ?: "Reference"} ${tr(UiText.APPROVE)}"
    AfButton(approveLabel, { model.approveRef(row.number, !row.refApproved) }, Modifier.heightIn(min = 48.dp).testTag("ref-approve"),
        kind = if (row.refApproved) ButtonKind.Primary else ButtonKind.Secondary, enabled = row.chosenRef != null)
}

/** One shot in the list pane: number, time, badge, the lyric's first line (DESIGN.md §3 cards and badges). */
@Composable
private fun SheetShotItem(row: ShotRow, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(AutoFlowShapes.Card)
            .border(AutoFlowShapes.Border, if (selected) AutoFlowColors.Purple else AutoFlowColors.Line, AutoFlowShapes.Card)
            .background(if (selected) AutoFlowColors.Card else AutoFlowColors.Panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("shot-sheet-select-${row.number}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        WrapRow(spacing = 6.dp) {
            AfText("#${row.number.toString().padStart(2, '0')} · ${row.timecode}", bold = true, size = 13.sp)
            StatusBadge(row.status)
        }
        if (row.lyric.isNotBlank()) AfText(My.breakSyllables(row.lyric), muted = true, size = 12.sp)
    }
}

@Composable
private fun RefTile(label: String, id: ArtifactId, chosen: Boolean, model: ReferencesModel, onPick: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, id) { value = model.refImage(id)?.let(::decodeImage) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.size(width = THUMB_W.dp, height = THUMB_H.dp)
                .clip(AutoFlowShapes.Card)
                .background(AutoFlowColors.Background)
                .border(if (chosen) 2.dp else 1.dp, if (chosen) AutoFlowColors.Purple else AutoFlowColors.Line, AutoFlowShapes.Card)
                .clickable(onClick = onPick),
            contentAlignment = Alignment.Center,
        ) {
            val img = image
            if (img != null) Image(img, contentDescription = label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else AfText(tr(UiText.IMAGE), muted = true, size = 11.sp)
        }
        AfChip(if (chosen) "$label ✓" else label, chosen, onPick, Modifier.heightIn(min = 48.dp).testTag("ref-pick-${label.removePrefix("Ref ")}"))
    }
}
