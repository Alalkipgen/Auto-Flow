package io.github.alalkipgen.autoflow.ui.shotsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.BesideOrUnder
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.CardTile
import io.github.alalkipgen.autoflow.ui.components.GlassButton
import io.github.alalkipgen.autoflow.ui.components.GlassDialog
import io.github.alalkipgen.autoflow.ui.components.GlassDialogAction
import io.github.alalkipgen.autoflow.ui.components.GlassDialogButtons
import io.github.alalkipgen.autoflow.ui.components.GlassFact
import io.github.alalkipgen.autoflow.ui.components.GlassIconButton
import io.github.alalkipgen.autoflow.ui.components.GlassProgress
import io.github.alalkipgen.autoflow.ui.components.GlassSectionType
import io.github.alalkipgen.autoflow.ui.components.GlassSwitch
import io.github.alalkipgen.autoflow.ui.components.SoftTile
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.glassDashedBox
import io.github.alalkipgen.autoflow.ui.components.interactionOverlay
import io.github.alalkipgen.autoflow.ui.handoff.FlowHandoffModel
import io.github.alalkipgen.autoflow.ui.handoff.FlowHandoffUiState
import io.github.alalkipgen.autoflow.ui.handoff.batchSummary
import io.github.alalkipgen.autoflow.ui.handoff.creditSummary
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.recap.InnerGlassCard
import io.github.alalkipgen.autoflow.ui.recap.RecapChip
import io.github.alalkipgen.autoflow.ui.shots.ShotRow
import io.github.alalkipgen.autoflow.ui.shots.ShotStatusPill
import io.github.alalkipgen.autoflow.ui.shots.pillColour
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardModel
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardUiState
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.theme.GlassShapes

// Group 3 C (samples 05, 06, 09a): the shot list in the glass look. The sheet's own copies of the Storyboard header,
// the credit counter and the batch panel — same model calls; the classic Storyboard / Flow Handoff screens keep theirs.

/** The list head (sample 05): SHOTS as the card title and V3_MV_SUMMARY as one Muted caption after the film-slate icon. */
@Composable
internal fun ShotListHead(summary: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AfText(My.breakSyllables(tr(UiText.SHOTS)), type = AfType.CardTitle)
        GlassFact(AfIcons.Regular.Film, summary, colour = AutoFlowColors.Muted, caption = true,
            textModifier = Modifier.testTag("shot-sheet-summary"))
    }
}

/**
 * One shot in the list (sample 05 `.srow`): the 40 dp number tile in the status tone, the mono timecode and the status
 * pill (under it when both do not fit), the lyric's first line (one Muted caption line; the whole lyric stays readable
 * as its description), the caret. Selected = Purple .12 fill, .55 line and the primary glow.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SheetShotItem(row: ShotRow, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (selected) Modifier.shadow(6.dp, shape, ambientColor = GlassColors.PrimaryGlow, spotColor = GlassColors.PrimaryGlow) else Modifier)
            .clip(shape)
            .background(if (selected) GlassColors.ShotRowSelectedFill else GlassColors.ShotRowFill)
            .border(1.dp, if (selected) GlassColors.ShotRowSelectedBorder else GlassColors.ShotRowBorder, shape)
            .clickable(interaction, null, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .interactionOverlay(interaction, shape)
            .padding(8.dp)
            .testTag("shot-sheet-select-${row.number}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NumberTile(row)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                AfText(if (row.endTimecode.isEmpty()) row.timecode else "${row.timecode}–${row.endTimecode}", Modifier.padding(end = 6.dp), mono = true, size = 12.5.sp)
                ShotStatusPill(row.status)
            }
            if (row.lyric.isNotBlank()) {
                val lyric = My.breakSyllables(row.lyric)
                AfText(lyric, Modifier.semantics { contentDescription = lyric }, color = AutoFlowColors.Muted, type = AfType.Caption,
                    maxLines = 1, ellipsis = true)
            }
        }
        AfIcon(AfIcons.Regular.Chevron, tint = AutoFlowColors.Muted, size = 16.dp)
    }
}

/** The 40 dp number tile (sample 05 `.nt9`): radius 10, the status tone at .16 fill and .40 line, "01" mono 13 in the tone. */
@Composable
private fun NumberTile(row: ShotRow) {
    val tone = row.status.pillColour()
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.defaultMinSize(40.dp, 40.dp).clip(shape).background(tone.copy(alpha = .16f)).border(1.dp, tone.copy(alpha = .40f), shape)
        .padding(horizontal = 4.dp).testTag("shot-number-${row.number}"), contentAlignment = Alignment.Center) {
        AfText(row.number.toString().padStart(2, '0'), color = tone, mono = true, size = 13.sp)
    }
}

/** No shot matches the filter (sample 06): the dashed box with the Duotone funnel and V3_MV_FILTER_EMPTY. */
@Composable
internal fun FilterEmptyBox() = DashedNote(AfIcons.Duotone.Funnel, AutoFlowColors.Muted, tr(UiText.V3_MV_FILTER_EMPTY), "shot-filter-empty")

/** No storyboard yet (sample 06): the Duotone film slate, STORYBOARD_EMPTY and the primary Go to Input (pencil-simple-line). */
@Composable
internal fun NoStoryboardBox(onGoToInput: () -> Unit) =
    DashedNote(AfIcons.Duotone.Film, AutoFlowColors.Purple, tr(UiText.STORYBOARD_EMPTY), "shot-sheet-empty") {
        GlassButton(tr(UiText.GO_INPUT), onGoToInput, kind = ButtonKind.Primary, icon = AfIcons.Regular.PencilLine)
    }

/** No project open (as Input, sample 02): the Duotone folder and OPEN_PROJECT_FIRST. */
@Composable
internal fun NoProjectNote() = DashedNote(AfIcons.Duotone.Folder, AutoFlowColors.Purple, tr(UiText.OPEN_PROJECT_FIRST), "shot-sheet-no-project")

@Composable
private fun DashedNote(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, text: String, tag: String,
    action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().glassDashedBox().padding(horizontal = 16.dp, vertical = 22.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AfIcon(icon, tint = tint, size = 32.dp)
        AfText(My.breakSyllables(text), color = AutoFlowColors.Muted, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

/**
 * The All shots row (sample 05 / 06 `.sub`): radius 14, White .03 + .10 line; a 48 dp header (stack icon, ALL_SHOTS, the
 * caret in a 36 dp glass box) that opens [content] under it. Tag `shot-sheet-all` (kept) on the header, expanded / collapsed.
 */
@Composable
internal fun AllShotsRow(open: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val interaction = remember { MutableInteractionSource() }
    val label = tr(UiText.ALL_SHOTS)
    Column(Modifier.fillMaxWidth().clip(shape).background(GlassColors.AllShotsFill).border(1.dp, GlassColors.OptionBorder, shape)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(interaction, null, role = Role.Button, onClick = onToggle).interactionOverlay(interaction, shape)
            .semantics { contentDescription = label; stateDescription = if (open) "expanded" else "collapsed" }
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).testTag("shot-sheet-all"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AfIcon(AfIcons.Regular.Stack, tint = AutoFlowColors.Muted, size = 18.dp)
            AfText(My.breakSyllables(label), Modifier.weight(1f), type = AfType.Button)
            Box(Modifier.size(GlassShapes.IconButtonCard).clip(GlassShapes.IconButton).background(GlassColors.SecondaryFill)
                .border(1.dp, GlassColors.SecondaryBorder, GlassShapes.IconButton), contentAlignment = Alignment.Center) {
                AfIcon(if (open) AfIcons.Regular.CaretUp else AfIcons.Regular.CaretDown, tint = AutoFlowColors.Text, size = 18.dp)
            }
        }
        if (open) Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/**
 * The storyboard box (sample 06, plan C inner 1): film-slate tile, STORYBOARD and the approved count (PrimaryText); the
 * approved / shots bar; the version chips; Approve all (checks) and Regenerate all (arrow-clockwise, no "↻ "); the snap
 * switch line with the metronome. Regenerate all asks in a glass dialog (🆕-5, sample 09a). Same calls as the classic header.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StoryboardBox(state: StoryboardUiState, model: StoryboardModel, busy: Boolean) {
    var confirming by remember { mutableStateOf(false) }
    InnerGlassCard(null, null, Modifier.testTag("all-shots-storyboard")) {
        BesideOrUnder(
            lead = { SoftTile(AfIcons.Fill.Film, AutoFlowColors.Purple, size = 26.dp) },
            text = { AfText(My.breakSyllables(tr(UiText.STORYBOARD)), type = GlassSectionType, weight = FontWeight.Bold) },
            trailing = {
                AfText(tr(UiText.STORYBOARD_APPROVED_COUNT, "approvedCount" to state.approvedCount, "size" to state.cards.size),
                    color = AutoFlowColors.PrimaryText, type = AfType.Micro)
            },
            end = null, modifier = Modifier.fillMaxWidth(), gap = 8.dp,
        )
        if (state.cards.isNotEmpty()) GlassProgress(state.approvedCount.toFloat() / state.cards.size)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            AfText(My.breakSyllables(tr(UiText.VERSION)), color = AutoFlowColors.Muted, type = AfType.Micro, weight = FontWeight.Normal)
            state.versions.forEach { v -> RecapChip(v.label, v.current, { if (!busy && !v.current) model.switchVersion(v.id) }) }
        }
        WrapRow {
            GlassButton(My.breakSyllables(tr(UiText.APPROVE_ALL)), { model.approveAll() }, kind = ButtonKind.Primary,
                enabled = !busy && state.approvedCount < state.cards.size, icon = AfIcons.Regular.Checks, small = true)
            GlassButton(My.breakSyllables(tr(UiText.REGENERATE_ALL)), { confirming = true }, enabled = !busy, icon = AfIcons.Regular.Redo, small = true)
        }
        SnapSwitch(state.snapToBeat) { if (!busy) model.setSnapToBeat(it) }
    }
    if (confirming && !busy) {
        val downstream = state.cards.count { it.hasDownstream }
        val text = tr(UiText.STORYBOARD_REGENERATE_HINT, "version" to (state.versions.firstOrNull { it.current }?.label ?: tr(UiText.CURRENT_VERSION))) +
            if (downstream > 0) tr(UiText.STORYBOARD_DOWNSTREAM, "n" to downstream) else "."
        RegenerateAllDialog(text, onBack = { confirming = false }, onConfirm = { confirming = false; model.regenerateAll() })
    }
}

/** 🆕-5 (sample 09a): the Regenerate all question in a glass dialog — arrow-clockwise Purple, the hint, Back / Regenerate all. */
@Composable
internal fun RegenerateAllDialog(text: String, onBack: () -> Unit, onConfirm: () -> Unit) {
    GlassDialog(onBack, AfIcons.Fill.Redo, AutoFlowColors.Purple, My.breakSyllables(tr(UiText.STORYBOARD_NEW_VERSION)),
        Modifier.testTag("confirm-regenerate-all")) {
        AfText(My.breakSyllables(text))
        GlassDialogButtons(GlassDialogAction(My.breakSyllables(tr(UiText.BACK_PLAIN)), onBack, tag = "confirm-regenerate-all-back"),
            GlassDialogAction(My.breakSyllables(tr(UiText.REGENERATE_ALL)), onConfirm, ButtonKind.Primary, AfIcons.Regular.Redo,
                tag = "confirm-regenerate-all-ok"))
    }
}

/** "Snap cuts to beat" as a switch line (sample 06): metronome 18 Muted, the label (body) and the glass switch; ≥ 48 dp. */
@Composable
private fun SnapSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    // The switch goes under the label when the list pane is narrow (840 dp windows at a large font).
    BesideOrUnder(
        lead = { AfIcon(AfIcons.Regular.Metronome, tint = AutoFlowColors.Muted, size = 18.dp) },
        text = { AfText(My.breakSyllables(tr(UiText.SNAP_TO_BEAT)), type = AfType.Body) },
        trailing = { GlassSwitch(checked) },
        end = null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, interaction, null, role = Role.Switch, onValueChange = onChange)
            .semantics { stateDescription = if (checked) "on" else "off" }
            .interactionOverlay(interaction, RectangleShape).padding(vertical = 4.dp).testTag("snap-to-beat"),
        gap = 10.dp,
    )
}

/**
 * The Flow credits box (sample 06, plan C inner 2): coins tile (Amber) and FLOW_CREDIT_SUMMARY; the log button (list /
 * caret-up); the open log as one glass list of mono lines, each with a danger trash button (replaces "✕"). Same calls.
 */
@Composable
internal fun CreditsBox(state: FlowHandoffUiState, model: FlowHandoffModel) {
    var open by remember { mutableStateOf(false) }
    val language = LocalUiLanguage.current
    InnerGlassCard(My.breakSyllables(creditSummary(state.creditsToday, state.creditsMonth, language)), CardTile(AfIcons.Fill.Coins, AutoFlowColors.Amber),
        Modifier.testTag("all-shots-credits")) {
        if (state.creditLog.isNotEmpty()) {
            GlassButton(My.breakSyllables(if (open) tr(UiText.CLOSE_LOG) else tr(UiText.CREDIT_LOG_COUNT, "size" to state.creditLog.size)), { open = !open },
                icon = if (open) AfIcons.Regular.CaretUp else AfIcons.Regular.ListLines, small = true)
        }
        if (open && state.creditLog.isNotEmpty()) {
            val shape = RoundedCornerShape(14.dp)
            Column(Modifier.fillMaxWidth().clip(shape).background(GlassColors.ShotRowFill).border(1.dp, GlassColors.ShotRowBorder, shape)) {
                state.creditLog.forEachIndexed { i, e ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(GlassColors.Divider))
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AfText("${e.date} · ${e.shot?.let { "Shot $it" } ?: "—"} · ${e.credits}", Modifier.weight(1f), mono = true, size = 12.5.sp)
                        GlassIconButton(AfIcons.Regular.Trash, tr(UiText.REMOVE), { model.removeCredit(e.index) }, Modifier.testTag("credit-remove-${e.index}"),
                            size = 40.dp, tone = AutoFlowColors.Failed)
                    }
                }
            }
        }
    }
}

/**
 * The batch sheet box (sample 06, plan C inner 3): file-html tile (teal) and BATCH_COUNT / BATCH_EMPTY; Export (primary,
 * file-html), then — after an export — Open sheet (arrow-square-out, no " ↗") and Share sheet (share-network). Same calls.
 */
@Composable
internal fun BatchBox(state: FlowHandoffUiState, model: FlowHandoffModel) {
    val pending = state.rows.count { it.batchable }
    InnerGlassCard(My.breakSyllables(batchSummary(state.batchCount, pending, LocalUiLanguage.current)), CardTile(AfIcons.Fill.FileHtml, GlassColors.MusicVideo),
        Modifier.testTag("all-shots-batch")) {
        WrapRow {
            GlassButton(My.breakSyllables(tr(UiText.EXPORT_BATCH)), { model.exportBatch() }, kind = ButtonKind.Primary, enabled = state.batchCount > 0,
                icon = AfIcons.Regular.FileHtml, small = true)
            if (state.lastBatch != null) {
                GlassButton(My.breakSyllables(tr(UiText.OPEN_BATCH)), { model.openBatch() }, icon = AfIcons.Regular.ArrowSquareOut, small = true)
                GlassButton(My.breakSyllables(tr(UiText.SHARE_BATCH)), { model.shareBatch() }, icon = AfIcons.Regular.Share, small = true)
            }
        }
    }
}
