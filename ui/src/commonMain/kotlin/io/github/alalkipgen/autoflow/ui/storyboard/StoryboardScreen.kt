package io.github.alalkipgen.autoflow.ui.storyboard

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
import io.github.alalkipgen.autoflow.ui.input.NO_PROJECT_TEXT
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes

/** DESIGN.md §6 copy. */
const val APPROVE = "✓ အတည်ပြုမယ်"
const val EDIT = "✎ ပြင်မယ်"
const val REGENERATE = "↻ ပြန်ထုတ်မယ်"
const val STALE = "⚠ ပြန်ထုတ်ရန် လိုသည်"
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
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 860.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AfText("Storyboard", size = 18.sp, bold = true)
        when {
            state.projectName == null -> AfText(NO_PROJECT_TEXT, muted = true)
            state.cards.isEmpty() -> {
                AfText(NO_STORYBOARD_TEXT, muted = true)
                AfButton("Go to Input", onGoToInput, kind = ButtonKind.Primary)
            }
            else -> {
                Header(state, model, busy)
                job.running?.let { running ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AfText("$running…", color = AutoFlowColors.Cyan)
                        AfButton("Cancel", jobs::cancel, kind = ButtonKind.Danger)
                    }
                }
                job.last?.takeIf { it != StoryboardResult.Done }?.message()?.let { AfText(it, color = AutoFlowColors.Amber) }
                message?.let { AfText(it, color = AutoFlowColors.Amber) }
                state.cards.forEach { card -> ShotCard(card, model, busy) }
            }
        }
    }
}

@Composable
private fun Header(state: StoryboardUiState, model: StoryboardModel, busy: Boolean) {
    var confirming by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AfText("${state.approvedCount}/${state.cards.size} approved", muted = true)
        Spacer(Modifier.weight(1f))
        AfText("Version", muted = true)
        state.versions.forEach { v -> AfChip(v.label, v.current, { if (!busy && !v.current) model.switchVersion(v.id) }) }
        AfButton("Approve all", { model.approveAll() }, kind = ButtonKind.Primary, enabled = !busy && state.approvedCount < state.cards.size)
        AfButton("↻ Regenerate all", { confirming = true }, enabled = !busy)
    }
    if (confirming && !busy) {
        val downstream = state.cards.count { it.hasDownstream }
        Confirm(
            title = "Make a new storyboard version?",
            text = "Writes a new storyboard for the whole song (2 AI requests, one more for each retry). " +
                "${state.versions.firstOrNull { it.current }?.label ?: "The current version"} stays in the version list. " +
                "Every shot needs approval again" +
                if (downstream > 0) "; $downstream shots have prompts, reference images or videos that become stale." else ".",
            action = "Regenerate all",
            onConfirm = { confirming = false; model.regenerateAll() },
            onBack = { confirming = false },
        )
    }
}

@Composable
private fun ShotCard(card: StoryCard, model: StoryboardModel, busy: Boolean) {
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AfText(number, bold = true)
            AfText(card.timecode, color = AutoFlowColors.Cyan, mono = true)
            if (card.approved) AfText("✓ Approved", color = AutoFlowColors.Green, size = 12.sp)
            if (card.stale) AfText(STALE, color = AutoFlowColors.Amber, size = 12.sp)
            Spacer(Modifier.weight(1f))
            AfText(card.tag, muted = true, size = 12.sp, mono = true)
        }
        if (card.lyric.isNotBlank()) AfText(card.lyric, muted = true)
        val spec = card.spec
        when {
            spec == null -> AfText("This shot has no scene yet.", color = AutoFlowColors.Amber)
            mode == CardMode.EDIT -> EditSpec(spec, onSave = { edited -> if (model.edit(card.number) { edited }) mode = CardMode.VIEW }, onBack = { mode = CardMode.VIEW })
            else -> {
                AfText(spec.sceneMy, size = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(spec.cameraEn, spec.moodEn).filter { it.isNotBlank() }.forEach { Tag(it) }
                }
            }
        }
        if (mode == CardMode.REGENERATE && !busy) {
            var keep by remember { mutableStateOf(card.tag.endsWith("edited")) }
            Confirm(
                title = "New take for shot $number?",
                text = "Uses 1 AI request. The shot needs approval again" +
                    if (card.hasDownstream) ", and its prompt, reference image and video become stale." else ".",
                action = "Regenerate",
                onConfirm = { mode = CardMode.VIEW; model.regenerate(card.number, keepScene = keep) },
                onBack = { mode = CardMode.VIEW },
            ) { AfSwitch("Keep my Burmese scene (only rewrite the English details)", keep, { keep = it }) }
        }
        if (mode == CardMode.VIEW && spec != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfButton(EDIT, { mode = CardMode.EDIT }, enabled = !busy)
                AfButton(REGENERATE, { mode = CardMode.REGENERATE }, enabled = !busy)
                if (card.approved) {
                    AfButton("Undo approval", { model.approve(card.number, approved = false) }, enabled = !busy)
                } else {
                    AfButton(APPROVE, { model.approve(card.number) }, kind = ButtonKind.Primary, enabled = !busy)
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
    AfTextField(draft.sceneMy, { draft = draft.copy(sceneMy = it) }, "Burmese scene", lines = 3, mono = false)
    AfText("English details (used in the Flow prompt)", muted = true, size = 12.sp)
    AfTextField(draft.subjectEn, { draft = draft.copy(subjectEn = it) }, "Subject", mono = false)
    AfTextField(draft.actionEn, { draft = draft.copy(actionEn = it) }, "Action", mono = false)
    AfTextField(draft.settingEn, { draft = draft.copy(settingEn = it) }, "Setting", mono = false)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AfTextField(draft.cameraEn, { draft = draft.copy(cameraEn = it) }, "Camera", Modifier.weight(1f), mono = false)
        AfTextField(draft.moodEn, { draft = draft.copy(moodEn = it) }, "Mood", Modifier.weight(1f), mono = false)
        AfTextField(draft.lightingEn, { draft = draft.copy(lightingEn = it) }, "Lighting", Modifier.weight(1f), mono = false)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AfButton("Save", { onSave(draft) }, kind = ButtonKind.Primary, enabled = draft.sceneMy.isNotBlank())
        AfButton("Back", onBack)
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
            AfButton("Back", onBack)
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
