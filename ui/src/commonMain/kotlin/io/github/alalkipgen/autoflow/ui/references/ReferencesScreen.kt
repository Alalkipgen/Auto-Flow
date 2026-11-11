package io.github.alalkipgen.autoflow.ui.references

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.input.NO_PROJECT_TEXT
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes
import kotlinx.coroutines.launch

/**
 * References screen (D-14): copy ImageDialect prompt → paste in AI Studio → import image → approve.
 */
@Composable
fun ReferencesScreen(
    model: ReferencesModel,
    readBytes: suspend (String) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState(model.current())
    val scope = rememberCoroutineScope()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 860.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AfText("References", size = 18.sp, bold = true)
            AfButton("Open AI Studio ↗", { model.openAiStudio() }, kind = ButtonKind.Secondary)
        }
        AfText("Copy an image prompt → generate in AI Studio → import the PNG here.", muted = true, size = 12.sp)

        when {
            state.projectName == null -> AfText(NO_PROJECT_TEXT, muted = true)
            state.rows.isEmpty() -> AfText("No shots yet — generate a storyboard first.", muted = true)
            else -> {
                state.message?.let { AfText(it, color = AutoFlowColors.Amber) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AfButton("✓ Approve All", { model.approveAll() }, kind = ButtonKind.Primary,
                        enabled = state.rows.any { it.chosenRef != null && !it.refApproved })
                }
                state.rows.forEach { row ->
                    RefCard(row, model, importRef = {
                        scope.launch { model.pickAndImportRef(row.number) { readBytes(it) } }
                    })
                }
            }
        }
    }
}

@Composable
private fun RefCard(
    row: RefRow,
    model: ReferencesModel,
    importRef: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AutoFlowShapes.Card)
            .border(AutoFlowShapes.Border,
                when {
                    row.refApproved -> AutoFlowColors.Green
                    row.chosenRef != null -> AutoFlowColors.Cyan
                    else -> AutoFlowColors.Line
                },
                AutoFlowShapes.Card)
            .background(AutoFlowColors.Card)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText("Shot ${row.number}", bold = true, size = 14.sp)
            AfText(row.timecode, muted = true, size = 12.sp)
            if (row.chosenRef != null) {
                val color = if (row.refApproved) AutoFlowColors.Green else AutoFlowColors.Cyan
                val label = if (row.refApproved) "✓ Ref Approved" else "Ref Imported"
                Box(Modifier.clip(AutoFlowShapes.Card)
                    .background(color.copy(alpha = 0.18f))
                    .border(AutoFlowShapes.Border, color.copy(alpha = 0.5f), AutoFlowShapes.Card)
                    .padding(horizontal = 7.dp, vertical = 3.dp)) {
                    AfText(label, color = color, size = 11.sp)
                }
            }
            if (row.refCount > 1) AfText("${row.refCount} refs", muted = true, size = 11.sp)
        }
        row.lyric.takeIf { it.isNotBlank() }?.let { AfText(it, size = 12.sp) }

        row.copyGateMsg?.let { AfText("⚠ $it", color = AutoFlowColors.Amber, size = 12.sp) }

        row.imagePrompt?.let { prompt ->
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfButton("📋 Copy Image Prompt", { model.copyImagePrompt(row.number) },
                kind = ButtonKind.Secondary, enabled = row.imagePrompt != null && row.copyGateMsg == null)
            AfButton("📂 Import Image", importRef, kind = ButtonKind.Secondary)
            if (row.chosenRef != null) {
                val approveLabel = if (row.refApproved) "✓ Approved" else "✓ Approve"
                AfButton(approveLabel,
                    { model.approveRef(row.number, !row.refApproved) },
                    kind = if (row.refApproved) ButtonKind.Primary else ButtonKind.Secondary)
            }
        }
    }
}
