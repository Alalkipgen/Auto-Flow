package io.github.alalkipgen.autoflow.ui.references

import io.github.alalkipgen.autoflow.ui.components.paneScroll
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.i18n.*
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
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.screenPadding
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
        modifier.fillMaxSize().paneScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 860.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WrapRow(spacing = 10.dp) {
            AfText(tr(UiText.REFERENCES), size = 18.sp, bold = true)
            AfButton(tr(UiText.OPEN_AI_STUDIO), { model.openAiStudio() }, kind = ButtonKind.Secondary)
        }
        AfText(tr(UiText.REFERENCES_MANUAL_HELP), muted = true, size = 12.sp)

        when {
            state.projectName == null -> AfText(tr(UiText.OPEN_PROJECT_FIRST), muted = true)
            state.rows.isEmpty() -> AfText(tr(UiText.NO_SHOTS_STORYBOARD), muted = true)
            else -> {
                state.message?.let { AfText(state.notice?.text(LocalUiLanguage.current) ?: it, color = AutoFlowColors.Amber) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AfButton(tr(UiText.APPROVE_ALL), { model.approveAll() }, kind = ButtonKind.Primary,
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
                val label = if (row.refApproved) tr(UiText.REF_APPROVED) else tr(UiText.REF_IMPORTED)
                Box(Modifier.clip(AutoFlowShapes.Card)
                    .background(color.copy(alpha = 0.18f))
                    .border(AutoFlowShapes.Border, color.copy(alpha = 0.5f), AutoFlowShapes.Card)
                    .padding(horizontal = 7.dp, vertical = 3.dp)) {
                    AfText(label, color = color, size = 11.sp)
                }
            }
            if (row.refCount > 1) AfText(tr(UiText.REF_COUNT, "refCount" to (row.refCount)), muted = true, size = 11.sp)
        }
        row.lyric.takeIf { it.isNotBlank() }?.let { AfText(it, size = 12.sp) }

        row.copyGateMsg?.let { AfText("⚠ ${row.copyBlocker?.text(LocalUiLanguage.current) ?: it}", color = AutoFlowColors.Amber, size = 12.sp) }

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
        } ?: AfText(tr(UiText.FLOW_COMPILE_FIRST), muted = true, size = 12.sp)

        // Wraps: three buttons do not fit a phone row, and a squeezed last button grew tall and invisible.
        WrapRow {
            AfButton(tr(UiText.COPY_IMAGE_PROMPT), { model.copyImagePrompt(row.number) },
                kind = ButtonKind.Secondary, enabled = row.imagePrompt != null && row.copyGateMsg == null)
            AfButton(tr(UiText.IMPORT_IMAGE), importRef, kind = ButtonKind.Secondary)
            if (row.chosenRef != null) {
                val approveLabel = if (row.refApproved) tr(UiText.REF_APPROVED_BUTTON) else tr(UiText.REF_APPROVE_BUTTON)
                AfButton(approveLabel,
                    { model.approveRef(row.number, !row.refApproved) },
                    kind = if (row.refApproved) ButtonKind.Primary else ButtonKind.Secondary)
            }
        }
    }
}
