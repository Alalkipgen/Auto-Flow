package io.github.alalkipgen.autoflow.ui.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import io.github.alalkipgen.autoflow.core.workflow.ShotStatus
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.VideoPreview
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.input.NO_PROJECT_TEXT
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes

/** Human-readable badge label + color for each [ShotStatus]. */
fun ShotStatus.badgeLabel(): String = when (this) {
    ShotStatus.DRAFT -> "Draft"
    ShotStatus.PROMPT_READY -> "✓ Prompt"
    ShotStatus.NEEDS_FLOW -> "→ Flow"
    ShotStatus.IMPORTED -> "Imported"
    ShotStatus.ACCEPTED -> "✓ Accepted"
    ShotStatus.STALE -> "⚠ Stale"
}

fun ShotStatus.badgeColor(): Color = when (this) {
    ShotStatus.DRAFT -> AutoFlowColors.Muted
    ShotStatus.PROMPT_READY -> AutoFlowColors.Cyan
    ShotStatus.NEEDS_FLOW -> AutoFlowColors.Purple
    ShotStatus.IMPORTED -> AutoFlowColors.Amber
    ShotStatus.ACCEPTED -> AutoFlowColors.Green
    ShotStatus.STALE -> Color(0xFFEF4444)
}

/**
 * Shots screen: grid of shot rows with status badges + detail panel for the selected shot.
 * Compile Prompt is deterministic (no AI call); approve locks the prompt for Flow Handoff.
 */
@Composable
fun ShotsScreen(model: ShotsModel, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState(model.current())
    val compact = LocalCompact.current

    // Phone: show detail as a separate "page" (replaces the grid)
    if (compact && state.detail != null) {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.actionError?.let { AfText(it, color = AutoFlowColors.Amber) }
            ShotDetailPanel(state.detail!!, model)
            AfButton("← Back", { model.select(null) })
        }
        return
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 860.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AfText("Shots", size = 18.sp, bold = true)
        when {
            state.projectName == null -> AfText(NO_PROJECT_TEXT, muted = true)
            state.rows.isEmpty() -> AfText("No shots yet — generate a storyboard first.", muted = true)
            else -> {
                state.actionError?.let { AfText(it, color = AutoFlowColors.Amber) }
                if (!compact && state.detail != null) {
                    // Desktop: side-by-side
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.widthIn(min = 160.dp, max = 220.dp)) { ShotGrid(state, model) }
                        Column(Modifier.weight(1f)) { ShotDetailPanel(state.detail!!, model) }
                    }
                } else {
                    ShotGrid(state, model)
                }
            }
        }
    }
}

@Composable
private fun ShotGrid(state: ShotsUiState, model: ShotsModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.rows.forEach { row ->
            ShotRowItem(row, selected = row.number == state.selected) { model.select(row.number) }
        }
    }
}

@Composable
private fun ShotRowItem(row: ShotRow, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) AutoFlowColors.Card else AutoFlowColors.Panel
    val border = if (selected) AutoFlowColors.Purple else AutoFlowColors.Line
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AutoFlowShapes.Card)
            .border(AutoFlowShapes.Border, border, AutoFlowShapes.Card)
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AfText("Shot ${row.number}", bold = true, size = 13.sp)
        AfText(row.timecode, muted = true, size = 12.sp)
        Box(Modifier.weight(1f)) {
            AfText(row.lyric.ifBlank { "—" }, muted = row.lyric.isBlank(), size = 12.sp)
        }
        StatusBadge(row.status)
    }
}

@Composable
private fun StatusBadge(status: ShotStatus) {
    Box(
        Modifier
            .clip(AutoFlowShapes.Card)
            .background(status.badgeColor().copy(alpha = 0.18f))
            .border(AutoFlowShapes.Border, status.badgeColor().copy(alpha = 0.5f), AutoFlowShapes.Card)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        AfText(status.badgeLabel(), color = status.badgeColor(), size = 11.sp)
    }
}

@Composable
private fun ShotDetailPanel(detail: ShotDetail, model: ShotsModel) {
    var confirmCompile by remember(detail.number) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText("Shot ${detail.number}", size = 16.sp, bold = true)
            AfText(detail.timecode, muted = true, size = 13.sp)
            StatusBadge(detail.status)
        }
        detail.lyric.takeIf { it.isNotBlank() }?.let { AfText(it, size = 13.sp) }

        // Accepted video preview
        detail.acceptedVideoPath?.let { VideoPreview(it) }

        // Storyboard spec summary
        if (detail.spec != null) {
            SectionLabel("Storyboard spec")
            SpecField("Scene (My)", detail.spec.sceneMy)
            SpecField("Subject", detail.spec.subjectEn)
            SpecField("Action", detail.spec.actionEn)
            SpecField("Setting", detail.spec.settingEn)
            SpecField("Camera", detail.spec.cameraEn)
            SpecField("Mood", detail.spec.moodEn)
            SpecField("Lighting", detail.spec.lightingEn)
        } else {
            AfText("No storyboard yet — generate it on the Storyboard screen.", muted = true, size = 13.sp)
        }

        // Prompt section
        SectionLabel("Prompt (Veo / Flow)")
        when {
            detail.promptText != null -> {
                Box(
                    Modifier.fillMaxWidth()
                        .clip(AutoFlowShapes.Card)
                        .background(AutoFlowColors.Background)
                        .border(AutoFlowShapes.Border, AutoFlowColors.Line, AutoFlowShapes.Card)
                        .padding(10.dp),
                ) {
                    AfText(detail.promptText, size = 12.sp)
                }
                val approveLabel = if (detail.approvedPrompt) "✓ Approved" else "✓ Approve Prompt"
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AfButton(approveLabel,
                        { model.approvePrompt(detail.number, !detail.approvedPrompt) },
                        kind = if (detail.approvedPrompt) ButtonKind.Primary else ButtonKind.Secondary,
                    )
                    if (confirmCompile) {
                        AfButton("Recompile? Yes", {
                            confirmCompile = false
                            model.compilePrompt(detail.number)
                        }, kind = ButtonKind.Danger)
                        AfButton("Cancel", { confirmCompile = false })
                    } else {
                        AfButton("↻ Recompile", { confirmCompile = true }, kind = ButtonKind.Secondary)
                    }
                }
            }
            detail.compileError != null -> {
                AfText("Cannot compile: ${detail.compileError}", color = AutoFlowColors.Amber, size = 12.sp)
            }
            detail.spec != null -> {
                AfText("Not compiled yet.", muted = true, size = 12.sp)
                AfButton("Compile Prompt", { model.compilePrompt(detail.number) }, kind = ButtonKind.Primary)
            }
            else -> AfText("Generate storyboard first.", muted = true, size = 12.sp)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    AfText(text, size = 11.sp, muted = true)
}

@Composable
private fun SpecField(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AfText("$label:", muted = true, size = 12.sp)
        AfText(value, size = 12.sp)
    }
}
