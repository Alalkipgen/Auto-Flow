package io.github.alalkipgen.autoflow.ui.shotsheet

import io.github.alalkipgen.autoflow.ui.components.paneScroll
import io.github.alalkipgen.autoflow.ui.components.GlassScrollColumn
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.recap.RecapChip
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import io.github.alalkipgen.autoflow.ui.components.GlassButton
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.components.AfChip
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.StickyActionBar
import io.github.alalkipgen.autoflow.ui.components.StudioAction
import io.github.alalkipgen.autoflow.ui.components.ProjectShortcutHost
import io.github.alalkipgen.autoflow.ui.components.BlockShortcuts
import io.github.alalkipgen.autoflow.core.workflow.ShotStatus
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
import io.github.alalkipgen.autoflow.ui.recap.ProjectPanes
import io.github.alalkipgen.autoflow.ui.references.ReferencesModel
import io.github.alalkipgen.autoflow.ui.references.RefRow
import io.github.alalkipgen.autoflow.ui.shots.PromptSection
import io.github.alalkipgen.autoflow.ui.shots.ShotRow
import io.github.alalkipgen.autoflow.ui.shots.ShotsModel
import io.github.alalkipgen.autoflow.ui.shots.VersionBrowser
import io.github.alalkipgen.autoflow.ui.storyboard.Header
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
 * ≥ 840 dp window: shots list · sheet · media preview; narrower: the shot list, then one shot's sheet (Back → list).
 * UI v3 (PR-7): filters and navigator are presentation only; Copy prompt / Open Flow sit once in the sticky bar.
 * Group 3 D outer: the glass back button, navigator (5-dot step strip, 🆕-15), step-card headers and glass sticky bar.
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
    projectKey: String? = null,
    handleBack: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
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
    val identity = projectKey ?: shotsState.projectName
    var detailOpen by remember(identity) { mutableStateOf(false) }
    var filter by remember(identity) { mutableStateOf(ShotFilter.ALL) }

    // Group 3 C: no project / no storyboard = the dashed glass notes (samples 02 / 06), no background of their own.
    if (shotsState.projectName == null) { Box(Modifier.padding(screenPadding)) { NoProjectNote() }; return }
    val rows = shotsState.rows
    if (rows.isEmpty()) {
        Column(Modifier.fillMaxWidth().paneScroll(rememberScrollState()).padding(screenPadding)) { NoStoryboardBox(onGoToInput) }
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
    // Narrow: shot list → shot detail; Back closes the detail first (specs/ui-v3.md §1). Transient, per project.
    val sheetOpen = wide || detailOpen
    handleBack(!wide && detailOpen) { detailOpen = false }
    val counts = ShotFilter.entries.associateWith { f -> rows.count(f::matches) }
    val openShot: (Int) -> Unit = { n -> shots.select(n); if (!wide) detailOpen = true }

    // Group 3 C (sample 06): the sheet's glass storyboard / credits / batch boxes — the same model calls as the
    // classic Header / CreditCounter / BatchPanel, which the classic screens keep.
    val allShots: @Composable ColumnScope.() -> Unit = {
        StoryboardBox(sb, storyboard, busy)
        CreditsBox(flow, handoff)
        BatchBox(flow, handoff)
    }

    // Real counts from the existing ShotStatus badges; the filter only changes which rows are listed (sample 05).
    val shotList: @Composable () -> Unit = {
        ShotListHead(My.breakSyllables(tr(UiText.V3_MV_SUMMARY, "n" to rows.size, "accepted" to counts.getValue(ShotFilter.ACCEPTED))))
        WrapRow {
            ShotFilter.entries.forEach { f ->
                RecapChip(My.breakSyllables(tr(f.label, "n" to counts.getValue(f))), f == filter, { filter = f },
                    Modifier.testTag("shot-filter-${f.name}"))
            }
        }
        val visible = rows.filter(filter::matches)
        if (visible.isEmpty()) FilterEmptyBox()
        else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visible.forEach { r -> SheetShotItem(r, r.number == selected) { openShot(r.number) } }
        }
    }

    // ≥ 840 dp: the list pane opens with All shots open (the boxes were always shown there).
    var allOpen by remember(wide) { mutableStateOf(wide) }
    val allShotsRow: @Composable () -> Unit = { AllShotsRow(allOpen, { allOpen = !allOpen }, allShots) }

    val list: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().paneScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            shotList()
            allShotsRow()
        }
    }

    val listPage: @Composable () -> Unit = {
        GlassScrollColumn(Modifier.fillMaxSize().testTag("shot-sheet-list"), PaddingValues(screenPadding), Arrangement.spacedBy(12.dp)) {
            shotList()
            allShotsRow()
        }
    }

    val preview: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().paneScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText(tr(UiText.SHEET_MEDIA_SHOT, "number" to (row.number)), muted = true)
            flowRow?.let { f ->
                RefThumb(f, handoff)
                VideoThumb(f, handoff)
            }
            AfText(My.breakSyllables(tr(UiText.V3_MV_SHORTCUTS_HINT)), Modifier.testTag("shortcuts-hint"), muted = true)
        }
    }

    // 🆕-15: step n done = the flag its card already reads (storyboard approved · prompt approved · ref approved · a video
    // imported · a video accepted); the navigator dots and the card badges show the same five.
    val steps = listOf(
        card?.approved == true,
        detail?.approvedPrompt == true,
        refRow?.refApproved == true,
        (flowRow?.videoCount ?: 0) > 0 || detail?.versions?.isNotEmpty() == true,
        flowRow?.accepted == true || detail?.acceptedVideoPath != null,
    )

    val sheet: @Composable () -> Unit = {
        val scroll = key(identity, selected) { rememberScrollState() }
        // The scroll on the outer box and the padding inside it, so the glass buttons' 48 dp targets overflow into the padding.
        GlassScrollColumn(Modifier.fillMaxSize().testTag("shot-sheet"), PaddingValues(if (wide) 8.dp else screenPadding),
            Arrangement.spacedBy(12.dp), state = scroll) {
            // Group 3 D outer (sample 07): Back = a small glass button with arrow-left; the navigator hero card.
            if (!wide) GlassButton(My.breakSyllables(tr(UiText.V3_MV_SHOT_LIST)), { detailOpen = false }, Modifier.testTag("shot-sheet-back"),
                icon = AfIcons.Regular.Back, small = true)
            SheetNavigator(row, "${index + 1} / ${rows.size}", card?.lyric, steps, rows.getOrNull(index - 1), rows.getOrNull(index + 1)) {
                shots.select(it)
            }

            StepCard(SheetStep.STORYBOARD, steps[0]) {
                job.lastFor(storyboard.openProject)?.takeIf { it != StoryboardResult.Done }
                    ?.message(uiLanguage)?.let { AfText(it, color = AutoFlowColors.Amber) }
                sbMessage?.let { AfText(it, color = AutoFlowColors.Amber) }
                if (card != null) ShotCard(card, storyboard, busy, showHeader = false)
                else AfText(tr(UiText.SHEET_NO_STORY_CARD), color = AutoFlowColors.Amber)
            }

            StepCard(SheetStep.PROMPT, steps[1]) {
                shotsState.actionError?.let { AfText(it, color = AutoFlowColors.Amber) }
                if (detail != null) PromptSection(detail, shots) else AfText("…", muted = true)
            }

            StepCard(SheetStep.REFS, steps[2]) {
                refState.message?.let { AfText(refState.notice?.text(uiLanguage) ?: forSheet(it, uiLanguage), color = AutoFlowColors.Amber) }
                if (refRow != null) RefSection(refRow, refs, importRef = {
                    scope.launch { refs.pickAndImportRef(refRow.number) { readBytes(it) } }
                })
            }

            // Copy prompt and Open Flow live once, in the sticky bar; import and acceptance stay here and manual.
            StepCard(SheetStep.FLOW, steps[3]) {
                flow.message?.let { AfText(flow.notice?.text(uiLanguage) ?: forSheet(it, uiLanguage), color = AutoFlowColors.Amber) }
                if (flowRow != null) HandoffCard(flowRow.copy(gateMsg = flowRow.gateMsg?.let { forSheet(it, uiLanguage) }), LocalCompact.current, handoff, importVideo = {
                    scope.launch { handoff.pickAndImportVideo(flowRow.number) { readBytes(it) } }
                }, fast = flow.creditHintFast, quality = flow.creditHintQuality, showHeader = false,
                    noPromptText = tr(UiText.SHEET_COMPILE_FIRST), showCopy = false)
            }

            StepCard(SheetStep.VIDEOS, steps[4]) {
                if (detail != null && detail.versions.isNotEmpty()) VersionBrowser(detail, shots)
                else AfText(tr(UiText.SHEET_NO_VIDEO), muted = true)
            }
        }
    }

    ProjectShortcutHost { Column(Modifier.fillMaxSize()) {
        // ← / → = the navigator's previous / next shot buttons, only while a sheet is shown.
        BlockShortcuts(
            previous = rows.getOrNull(index - 1)?.takeIf { sheetOpen }?.let { p -> { shots.select(p.number) } },
            next = rows.getOrNull(index + 1)?.takeIf { sheetOpen }?.let { n -> { shots.select(n.number) } },
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            ProjectPanes(windowWidthDp, list = list, editor = if (sheetOpen) sheet else listPage, preview = preview)
        }
        if (sheetOpen) StickyActionBar(
            primary = StudioAction(tr(UiText.COPY_PROMPT), { handoff.copyPrompt(row.number) },
                enabled = flowRow != null && flowRow.promptText != null && flowRow.gateMsg == null, tag = "shot-sheet-copy-prompt",
                icon = AfIcons.Regular.Copy),
            secondary = StudioAction(tr(UiText.OPEN_FLOW), { handoff.openFlow() }, tag = "shot-sheet-open-flow", icon = AfIcons.Regular.ArrowSquareOut),
            caption = My.breakSyllables(tr(UiText.V3_MV_MANUAL_HINT)),
            glass = true,
        )
    } }
}

/** Shot-list filter over the existing badges; presentation state only, never saved. */
internal enum class ShotFilter(val label: UiText) {
    ALL(UiText.V3_ALL_COUNT), OPEN(UiText.V3_MV_OPEN_COUNT), ACCEPTED(UiText.V3_MV_ACCEPTED_COUNT);
    fun matches(row: ShotRow): Boolean = when (this) {
        ALL -> true
        OPEN -> row.status != ShotStatus.ACCEPTED
        ACCEPTED -> row.status == ShotStatus.ACCEPTED
    }
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
