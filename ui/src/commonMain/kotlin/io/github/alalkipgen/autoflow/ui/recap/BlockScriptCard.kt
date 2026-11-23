package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.theme.GlassShapes
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip

/** The native block, never the first-clip v1 read projection. Reading/cancelling does not write. */
@Composable
internal fun BlockScriptCard(model: RecapModel, state: RecapUiState, block: RecapBlock, index: Int) {
    val editor = model.blockEditor
    val edit by editor.state.collectAsState()
    val idle = !state.busy && state.recording == null
    DisposableEffect(state.projectDir, block.id) { onDispose { editor.cancel() } }
    // Group 2 C 11: note-pencil tile, a small glass Edit in the header, the caption in an inner box, the spoken line as a speaker row.
    TileCard(tr(UiText.V3_SCRIPT), RecapTiles.Script, action = {
        GlassButton(tr(UiText.EDIT), { editor.edit(block.id) }, Modifier.testTag("block-script-edit"), enabled = idle,
            icon = AfIcons.Regular.Edit, small = true)
    }) {
        if (state.isStale(index)) StatusChip(StudioStatus.SCRIPT_CHANGED)
        Box(Modifier.fillMaxWidth().clip(GlassShapes.MenuRow).background(GlassColors.OptionFill)
            .border(1.dp, GlassColors.OptionBorder, GlassShapes.MenuRow).padding(12.dp)) {
            AfText(block.textMy.ifBlank { tr(UiText.EMPTY_SCRIPT_EDIT) }, Modifier.testTag("block-script-text"), type = GlassSectionType)
        }
        GlassFact(AfIcons.Regular.Volume, tr(UiText.V3_SPEAK_AS, "text" to SpokenText.of(block, state.lexicon)), colour = AutoFlowColors.Muted)
    }
    val draft = edit.draft?.takeIf { it.block.id == block.id && !edit.editingClips } ?: return
    key(draft.token) {
        var text by remember { mutableStateOf(draft.block.textMy) }
        var spoken by remember { mutableStateOf(draft.block.speakMy.orEmpty()) }
        Dialog(onDismissRequest = editor::cancel) {
            AfCard(tr(UiText.V3_SCRIPT), Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Column(Modifier.weight(1f, fill = false).paneScroll(rememberScrollState()).testTag("block-script-form"),
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
