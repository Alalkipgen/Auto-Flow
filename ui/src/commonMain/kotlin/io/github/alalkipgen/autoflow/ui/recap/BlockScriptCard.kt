package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** The native block, never the first-clip v1 read projection. Reading/cancelling does not write. */
@Composable
internal fun BlockScriptCard(model: RecapModel, state: RecapUiState, block: RecapBlock, index: Int) {
    val editor = model.blockEditor
    val edit by editor.state.collectAsState()
    val idle = !state.busy && state.recording == null
    DisposableEffect(state.projectDir, block.id) { onDispose { editor.cancel() } }
    AfCard(tr(UiText.V3_SCRIPT)) {
        if (state.isStale(index)) StatusChip(StudioStatus.SCRIPT_CHANGED)
        AfText(block.textMy.ifBlank { tr(UiText.EMPTY_SCRIPT_EDIT) }, Modifier.testTag("block-script-text"))
        AfText(tr(UiText.V3_SPEAK_AS, "text" to SpokenText.of(block, state.lexicon)), muted = true)
        AfButton(tr(UiText.EDIT), { editor.edit(block.id) }, Modifier.testTag("block-script-edit"), enabled = idle)
    }
    val draft = edit.draft?.takeIf { it.block.id == block.id } ?: return
    key(draft.token) {
        var text by remember { mutableStateOf(draft.block.textMy) }
        var spoken by remember { mutableStateOf(draft.block.speakMy.orEmpty()) }
        Dialog(onDismissRequest = editor::cancel) {
            AfCard(tr(UiText.V3_SCRIPT), Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("block-script-form"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AfText(tr(UiText.V3_CAPTION_TEXT), bold = true)
                    AfTextField(text, { text = it }, tr(UiText.V3_CAPTION_TEXT), Modifier.fillMaxWidth().testTag("block-script-input"), lines = 4, mono = false, enabled = idle)
                    AfText(tr(UiText.V3_SPOKEN_OVERRIDE), bold = true)
                    AfTextField(spoken, { spoken = it }, tr(UiText.V3_SPOKEN_OVERRIDE), Modifier.fillMaxWidth().testTag("block-spoken-input"), lines = 3, mono = false, enabled = idle)
                    AfText(tr(UiText.V3_SPOKEN_HELP), muted = true)
                    SectionCard(tr(UiText.V3_MORE_OPTIONS), initiallyExpanded = false) { AfText(tr(UiText.V3_SPOKEN_HINT), muted = true) }
                    edit.problem?.let { AfText(it.text(LocalUiLanguage.current), color = AutoFlowColors.Amber, modifier = Modifier.testTag("block-script-problem")) }
                }
                StickyActionBar(StudioAction(tr(UiText.SAVE), { editor.save(text, spoken) }, idle, "block-script-save"),
                    StudioAction(tr(UiText.CANCEL), editor::cancel, tag = "block-script-cancel"))
            }
        }
    }
}
