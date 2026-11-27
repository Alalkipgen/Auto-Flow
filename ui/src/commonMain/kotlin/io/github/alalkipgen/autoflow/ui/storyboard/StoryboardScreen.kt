package io.github.alalkipgen.autoflow.ui.storyboard

import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.model.ShotSpec
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfChip
import io.github.alalkipgen.autoflow.ui.components.AfSwitch
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.AfTextField
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.input.NO_PROJECT_TEXT
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes

/** DESIGN.md §6 copy. */
const val APPROVE = "✓ အတည်ပြုမယ်"
const val EDIT = "✎ ပြင်မယ်"
const val REGENERATE = "↻ ပြန်ထုတ်မယ်"
const val STALE = "⚠ ပြန်ထုတ်ရန် လိုသည်"
const val SNAP_TO_BEAT = "♩ Snap cuts to beat"
const val NO_STORYBOARD_TEXT = "Storyboard မရှိသေးပါ။ Input မှာ \"Storyboard ထုတ်မယ်\" ကို နှိပ်ပါ။"

/**
 * Storyboard (DESIGN.md §3): one card per shot with ✎ edit inline, ↻ regenerate (confirm, optional keep scene) and
 * ✓ approve; header with progress, Approve all, versions and regenerate all (confirm states what becomes stale).
 */
@Composable
fun StoryboardScreen(model: StoryboardModel, jobs: StoryboardJobs, onGoToInput: () -> Unit, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState(model.current())
    val message by model.message.collectAsState()
    val job by jobs.state.collectAsState()
    val busy = job.running != null
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 860.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AfText(tr(UiText.STORYBOARD), size = 18.sp, bold = true)
        when {
            state.projectName == null -> AfText(tr(UiText.OPEN_PROJECT_FIRST), muted = true)
            state.cards.isEmpty() -> {
                AfText(tr(UiText.STORYBOARD_EMPTY), muted = true)
                AfButton(tr(UiText.GO_INPUT), onGoToInput, kind = ButtonKind.Primary)
            }
            else -> {
                Header(state, model, busy)
                job.last?.takeIf { it != StoryboardResult.Done }?.message(LocalUiLanguage.current)?.let { AfText(it, color = AutoFlowColors.Amber) }
                message?.let { AfText(it, color = AutoFlowColors.Amber) }
                state.cards.forEach { card -> ShotCard(card, model, busy) }
            }
        }
    }
}

/** Approved count, versions, Approve all / Regenerate all, snap to beat; also on the Studio v2 shot sheet (5B-12). */
@Composable
internal fun Header(state: StoryboardUiState, model: StoryboardModel, busy: Boolean) {
    var confirming by remember { mutableStateOf(false) }
    val count: @Composable () -> Unit = { AfText(tr(UiText.STORYBOARD_APPROVED_COUNT, "approvedCount" to (state.approvedCount), "size" to (state.cards.size)), muted = true) }
    val versions: @Composable () -> Unit = {
        AfText(tr(UiText.VERSION), muted = true)
        state.versions.forEach { v -> AfChip(v.label, v.current, { if (!busy && !v.current) model.switchVersion(v.id) }) }
    }
    val actions: @Composable () -> Unit = {
        AfButton(tr(UiText.APPROVE_ALL), { model.approveAll() }, kind = ButtonKind.Primary, enabled = !busy && state.approvedCount < state.cards.size)
        AfButton("↻ ${tr(UiText.REGENERATE_ALL)}", { confirming = true }, enabled = !busy)
        AfSwitch(tr(UiText.SNAP_TO_BEAT), state.snapToBeat, { if (!busy) model.setSnapToBeat(it) })
    }
    if (LocalCompact.current) {
        count()
        WrapRow { versions() }
        WrapRow { actions() }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            count()
            Spacer(Modifier.weight(1f))
            versions()
            actions()
        }
    }
    if (confirming && !busy) {
        val downstream = state.cards.count { it.hasDownstream }
        Confirm(
            title = tr(UiText.STORYBOARD_NEW_VERSION),
            text = tr(UiText.STORYBOARD_REGENERATE_HINT, "version" to (state.versions.firstOrNull { it.current }?.label ?: tr(UiText.CURRENT_VERSION))) +
                if (downstream > 0) tr(UiText.STORYBOARD_DOWNSTREAM, "n" to downstream) else ".",
            action = tr(UiText.REGENERATE_ALL),
            onConfirm = { confirming = false; model.regenerateAll() },
            onBack = { confirming = false },
        )
    }
}

/** One storyboard card; [showHeader] = false on the shot sheet (5B-12), which shows the shot number itself. */
@Composable
internal fun ShotCard(card: StoryCard, model: StoryboardModel, busy: Boolean, showHeader: Boolean = true) {
    var mode by remember(card.number) { mutableStateOf(CardMode.VIEW) }
    val number = "#${card.number.toString().padStart(2, '0')}"
    Column(
        Modifier
            .fillMaxWidth()
            .background(AutoFlowColors.Card, AutoFlowShapes.Card)
            .border(AutoFlowShapes.Border, if (card.approved) AutoFlowColors.Green else AutoFlowColors.Line, AutoFlowShapes.Card)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val badges: @Composable () -> Unit = {
            AfText(number, bold = true)
            AfText(card.timecode, color = AutoFlowColors.Cyan, mono = true)
            if (card.approved) AfText("✓ ${tr(UiText.APPROVED)}", color = AutoFlowColors.Green, size = 12.sp)
            if (card.stale) AfText("⚠ ${tr(UiText.STALE)}", color = AutoFlowColors.Amber, size = 12.sp)
        }
        if (!showHeader) {
            WrapRow(spacing = 10.dp) {
                if (card.approved) AfText("✓ ${tr(UiText.APPROVED)}", color = AutoFlowColors.Green, size = 12.sp)
                if (card.stale) AfText("⚠ ${tr(UiText.STALE)}", color = AutoFlowColors.Amber, size = 12.sp)
                AfText(card.tag, muted = true, size = 12.sp, mono = true)
            }
        } else if (LocalCompact.current) {
            WrapRow(spacing = 10.dp) {
                badges()
                AfText(card.tag, muted = true, size = 12.sp, mono = true)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                badges()
                Spacer(Modifier.weight(1f))
                AfText(card.tag, muted = true, size = 12.sp, mono = true)
            }
        }
        if (showHeader && card.lyric.isNotBlank()) AfText(card.lyric, muted = true)
        val spec = card.spec
        when {
            spec == null -> AfText(tr(UiText.STORYBOARD_NO_SCENE), color = AutoFlowColors.Amber)
            mode == CardMode.EDIT -> EditSpec(spec, onSave = { edited -> if (model.edit(card.number) { edited }) mode = CardMode.VIEW }, onBack = { mode = CardMode.VIEW })
            else -> {
                AfText(spec.sceneMy, size = 14.sp)
                WrapRow(spacing = 6.dp) {
                    listOf(spec.cameraEn, spec.moodEn).filter { it.isNotBlank() }.forEach { Tag(it) }
                }
            }
        }
        if (mode == CardMode.REGENERATE && !busy) {
            var keep by remember { mutableStateOf(card.tag.endsWith("edited")) }
            Confirm(
                title = tr(UiText.STORYBOARD_NEW_TAKE, "number" to (number)),
                text = tr(UiText.STORYBOARD_TAKE_HINT) +
                    if (card.hasDownstream) tr(UiText.STORYBOARD_TAKE_STALE) else ".",
                action = tr(UiText.REGENERATE),
                onConfirm = { mode = CardMode.VIEW; model.regenerate(card.number, keepScene = keep) },
                onBack = { mode = CardMode.VIEW },
            ) { AfSwitch(tr(UiText.KEEP_BURMESE_SCENE), keep, { keep = it }) }
        }
        if (mode == CardMode.VIEW && spec != null) {
            WrapRow {
                AfButton("✎ ${tr(UiText.EDIT)}", { mode = CardMode.EDIT }, enabled = !busy)
                AfButton("↻ ${tr(UiText.REGENERATE)}", { mode = CardMode.REGENERATE }, enabled = !busy)
                if (card.approved) {
                    AfButton(tr(UiText.UNDO_APPROVAL), { model.approve(card.number, approved = false) }, enabled = !busy)
                } else {
                    AfButton("✓ ${tr(UiText.APPROVE)}", { model.approve(card.number) }, kind = ButtonKind.Primary, enabled = !busy)
                }
            }
        }
    }
}

private enum class CardMode { VIEW, EDIT, REGENERATE }

/** ✎ ပြင်မယ်: the Burmese scene plus the English fields the prompt compiler uses. */
@Composable
private fun EditSpec(spec: ShotSpec, onSave: (ShotSpec) -> Unit, onBack: () -> Unit) {
    var draft by remember(spec) { mutableStateOf(spec) }
    AfTextField(draft.sceneMy, { draft = draft.copy(sceneMy = it) }, tr(UiText.BURMESE_SCENE), lines = 3, mono = false)
    AfText(tr(UiText.ENGLISH_PROMPT_DETAILS), muted = true, size = 12.sp)
    AfTextField(draft.subjectEn, { draft = draft.copy(subjectEn = it) }, tr(UiText.FIELD_SUBJECT), mono = false)
    AfTextField(draft.actionEn, { draft = draft.copy(actionEn = it) }, tr(UiText.FIELD_ACTION), mono = false)
    AfTextField(draft.settingEn, { draft = draft.copy(settingEn = it) }, tr(UiText.FIELD_SETTING), mono = false)
    if (LocalCompact.current) {
        AfTextField(draft.cameraEn, { draft = draft.copy(cameraEn = it) }, tr(UiText.FIELD_CAMERA), mono = false)
        AfTextField(draft.moodEn, { draft = draft.copy(moodEn = it) }, tr(UiText.FIELD_MOOD), mono = false)
        AfTextField(draft.lightingEn, { draft = draft.copy(lightingEn = it) }, tr(UiText.FIELD_LIGHTING), mono = false)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfTextField(draft.cameraEn, { draft = draft.copy(cameraEn = it) }, tr(UiText.FIELD_CAMERA), Modifier.weight(1f), mono = false)
            AfTextField(draft.moodEn, { draft = draft.copy(moodEn = it) }, tr(UiText.FIELD_MOOD), Modifier.weight(1f), mono = false)
            AfTextField(draft.lightingEn, { draft = draft.copy(lightingEn = it) }, tr(UiText.FIELD_LIGHTING), Modifier.weight(1f), mono = false)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AfButton(tr(UiText.SAVE), { onSave(draft) }, kind = ButtonKind.Primary, enabled = draft.sceneMy.isNotBlank())
        AfButton(tr(UiText.BACK_PLAIN), onBack)
    }
}

@Composable
private fun Confirm(
    title: String,
    text: String,
    action: String,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    AfCard(title) {
        AfText(text, muted = true)
        extra()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfButton(action, onConfirm, kind = ButtonKind.Primary)
            AfButton(tr(UiText.BACK_PLAIN), onBack)
        }
    }
}

@Composable
private fun Tag(text: String) {
    AfText(
        text,
        Modifier.border(AutoFlowShapes.Border, AutoFlowColors.Line, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        muted = true,
        size = 12.sp,
    )
}
