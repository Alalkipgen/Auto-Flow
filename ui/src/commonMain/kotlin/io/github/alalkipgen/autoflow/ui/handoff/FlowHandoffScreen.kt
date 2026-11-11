package io.github.alalkipgen.autoflow.ui.handoff

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.model.HandoffState
import io.github.alalkipgen.autoflow.core.model.Pass
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.input.NO_PROJECT_TEXT
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes
import kotlinx.coroutines.launch

/**
 * Flow Handoff screen (specs/flow-handoff.md): shot list → copy prompt → open Flow → import video → accept.
 * File bytes are read by [readBytes] (platform-provided).
 */
@Composable
fun FlowHandoffScreen(
    model: FlowHandoffModel,
    readBytes: suspend (String) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState(model.current())
    val compact = LocalCompact.current
    val scope = rememberCoroutineScope()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 860.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AfText("Flow Handoff", size = 18.sp, bold = true)
            AfButton("Open Flow ↗", { model.openFlow() }, kind = ButtonKind.Flow)
        }
        AfText("Copy a prompt → paste in Flow → generate video → import it here.", muted = true, size = 12.sp)

        when {
            state.projectName == null -> AfText(NO_PROJECT_TEXT, muted = true)
            state.rows.isEmpty() -> AfText("No shots yet — generate a storyboard first.", muted = true)
            else -> {
                state.message?.let { AfText(it, color = AutoFlowColors.Amber) }
                state.rows.forEach { row ->
                    HandoffCard(row, compact, model, importVideo = {
                        scope.launch { model.pickAndImportVideo(row.number) { readBytes(it) } }
                    })
                }
            }
        }
    }
}

@Composable
private fun HandoffCard(
    row: HandoffRow,
    compact: Boolean,
    model: FlowHandoffModel,
    importVideo: () -> Unit,
) {
    var showCredits by remember(row.number) { mutableStateOf(false) }
    var creditInput by remember(row.number) { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(AutoFlowShapes.Card)
            .border(AutoFlowShapes.Border,
                when {
                    row.accepted -> AutoFlowColors.Green
                    row.state == HandoffState.IMPORTED -> AutoFlowColors.Cyan
                    row.state == HandoffState.SENT_TO_FLOW -> AutoFlowColors.Purple
                    else -> AutoFlowColors.Line
                },
                AutoFlowShapes.Card)
            .background(AutoFlowColors.Card)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText("Shot ${row.number}", bold = true, size = 14.sp)
            AfText(row.timecode, muted = true, size = 12.sp)
            handoffBadges(row.state, row.accepted).forEach { StatePill(it) }
        }
        row.lyric.takeIf { it.isNotBlank() }?.let { AfText(it, size = 12.sp) }

        // Gate warning
        row.gateMsg?.let {
            AfText("⚠ $it", color = AutoFlowColors.Amber, size = 12.sp)
        }

        // Prompt preview
        row.promptText?.let { prompt ->
            Box(
                Modifier.fillMaxWidth()
                    .clip(AutoFlowShapes.Card)
                    .background(AutoFlowColors.Background)
                    .border(AutoFlowShapes.Border, AutoFlowColors.Line, AutoFlowShapes.Card)
                    .padding(8.dp),
            ) {
                AfText(prompt.take(200) + if (prompt.length > 200) "…" else "", size = 11.sp)
            }
        } ?: AfText("No compiled prompt — compile it on the Shots screen.", muted = true, size = 12.sp)

        // Pass toggle
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            AfText("Pass:", muted = true, size = 12.sp)
            Pass.entries.forEach { p ->
                val on = row.pass == p
                val label = if (p == Pass.DRAFT) "Draft (Fast)" else "Final (Quality)"
                Box(
                    Modifier
                        .clip(AutoFlowShapes.Card)
                        .border(AutoFlowShapes.Border, if (on) AutoFlowColors.Purple else AutoFlowColors.Line, AutoFlowShapes.Card)
                        .background(if (on) AutoFlowColors.Purple.copy(alpha = 0.18f) else AutoFlowColors.Panel)
                        .clickable { model.setPass(row.number, p) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    AfText(label, color = if (on) AutoFlowColors.Purple else AutoFlowColors.Muted, size = 11.sp)
                }
            }
        }

        // Actions
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HandoffActions(row, model, importVideo, showCredits, creditInput,
                    onShowCredits = { showCredits = true },
                    onCreditChange = { creditInput = it },
                    onCreditSave = { credits ->
                        credits.toIntOrNull()?.let { model.logCredits(row.number, it) }
                        showCredits = false
                        creditInput = ""
                    },
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HandoffActions(row, model, importVideo, showCredits, creditInput,
                    onShowCredits = { showCredits = true },
                    onCreditChange = { creditInput = it },
                    onCreditSave = { credits ->
                        credits.toIntOrNull()?.let { model.logCredits(row.number, it) }
                        showCredits = false
                        creditInput = ""
                    },
                )
            }
        }
    }
}

@Composable
private fun HandoffActions(
    row: HandoffRow,
    model: FlowHandoffModel,
    importVideo: () -> Unit,
    showCredits: Boolean,
    creditInput: String,
    onShowCredits: () -> Unit,
    onCreditChange: (String) -> Unit,
    onCreditSave: (String) -> Unit,
) {
    val hasPrompt = row.promptText != null
    AfButton("📋 Copy Prompt", { model.copyPrompt(row.number) },
        kind = ButtonKind.Primary,
        enabled = hasPrompt && row.gateMsg == null,
    )
    AfButton("📂 Import Video", importVideo, kind = ButtonKind.Secondary, enabled = !row.accepted)
    if (row.videoCount > 0 && !row.accepted) {
        AfButton("✓ Accept Video", { model.acceptVideo(row.number) }, kind = ButtonKind.Secondary)
    }
    if (row.state == HandoffState.IMPORTED && !showCredits) {
        AfButton("Log Credits…", onShowCredits, kind = ButtonKind.Secondary)
    }
    if (showCredits) {
        io.github.alalkipgen.autoflow.ui.components.AfTextField(
            value = creditInput,
            onChange = onCreditChange,
            placeholder = "Credits used",
            modifier = Modifier.widthIn(max = 100.dp),
        )
        AfButton("Save", { onCreditSave(creditInput) }, kind = ButtonKind.Primary)
    }
}

/** Badges for a shot row: its handoff state, plus "✓ Accepted" once — never twice. */
internal fun handoffBadges(state: HandoffState, accepted: Boolean): List<HandoffState> = buildList {
    if (state != HandoffState.NONE) add(state)
    if (accepted && state != HandoffState.ACCEPTED) add(HandoffState.ACCEPTED)
}

@Composable
private fun StatePill(state: HandoffState) {
    val (label, color) = when (state) {
        HandoffState.NONE -> return
        HandoffState.SENT_TO_FLOW -> "→ Flow" to AutoFlowColors.Purple
        HandoffState.IMPORTED -> "Imported" to AutoFlowColors.Cyan
        HandoffState.ACCEPTED -> "✓ Accepted" to AutoFlowColors.Green
    }
    Box(
        Modifier
            .clip(AutoFlowShapes.Card)
            .background(color.copy(alpha = 0.18f))
            .border(AutoFlowShapes.Border, color.copy(alpha = 0.5f), AutoFlowShapes.Card)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        AfText(label, color = color, size = 11.sp)
    }
}
