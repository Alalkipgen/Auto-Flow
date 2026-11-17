package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.alalkipgen.autoflow.core.recap.RecapBlock
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** Source cuts are edited independently from voice/caption timing. Nothing is persisted until Save. */
@Composable
internal fun BlockClipsCard(model: RecapModel, state: RecapUiState, block: RecapBlock) {
    val editor = model.blockEditor
    val edit by editor.state.collectAsState()
    val idle = !state.busy && state.recording == null
    DisposableEffect(state.projectDir, block.id) { onDispose { editor.cancel() } }
    AfCard(tr(UiText.V3_CLIPS)) {
        block.clips.forEach { AfText("${ClipRangeInput.seconds(it.inMs)}–${ClipRangeInput.seconds(it.outMs)} s", mono = true) }
        AfButton(tr(UiText.V3_EDIT_CLIPS), { editor.edit(block.id, clips = true) },
            Modifier.testTag("block-clips-edit"), enabled = idle && state.recap?.movie != null)
    }
    val draft = edit.draft?.takeIf { edit.editingClips && it.block.id == block.id } ?: return
    key(draft.token) {
        var rows by remember { mutableStateOf(draft.block.clips.map(ClipRangeInput::of)) }
        val clips = rows.mapNotNull { it.clip() }
        val valid = clips.size == rows.size && ClipRangeInput.valid(clips, draft.project.recap?.movie?.durationMs ?: 0)
        fun replace(i: Int, values: List<ClipRangeInput>) { rows = rows.take(i) + values + rows.drop(i + 1) }
        Dialog(onDismissRequest = editor::cancel) {
            AfCard(tr(UiText.V3_CLIPS), Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("block-clips-form"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AfText(tr(UiText.V3_CLIPS_HINT), muted = true)
                    rows.forEachIndexed { i, input ->
                        AfCard(tr(UiText.V3_CLIP_NUMBER, "n" to i + 1), Modifier.testTag("clip-range-$i")) {
                            AfText(tr(UiText.V3_CLIP_FROM), bold = true)
                            AfTextField(input.from, { replace(i, listOf(input.copy(from = it))) }, tr(UiText.V3_CLIP_FROM),
                                Modifier.fillMaxWidth().testTag("clip-from-$i"), enabled = idle)
                            AfText(tr(UiText.V3_CLIP_TO), bold = true)
                            AfTextField(input.to, { replace(i, listOf(input.copy(to = it))) }, tr(UiText.V3_CLIP_TO),
                                Modifier.fillMaxWidth().testTag("clip-to-$i"), enabled = idle)
                            SectionCard(tr(UiText.V3_MORE_OPTIONS), initiallyExpanded = false) {
                                val clip = input.clip()
                                var split by remember(input.from, input.to) { mutableStateOf(clip?.let {
                                    ClipRangeInput.seconds(it.inMs + it.durationMs / 2) }.orEmpty()) }
                                AfText(tr(UiText.V3_CLIP_SPLIT_AT), bold = true)
                                AfTextField(split, { split = it }, tr(UiText.V3_CLIP_SPLIT_AT),
                                    Modifier.fillMaxWidth().testTag("clip-split-at-$i"), enabled = idle)
                                val parts = clip?.let { c -> ClipRangeInput.parse(split)?.let { ClipRangeInput.split(c, it) } }
                                val merged = clip?.let { a -> rows.getOrNull(i + 1)?.clip()?.let { ClipRangeInput.merge(a, it) } }
                                WrapRow {
                                    AfButton(tr(UiText.V3_CLIP_SPLIT), { replace(i, parts!!.map(ClipRangeInput::of)) }, Modifier.testTag("clip-split-$i"), enabled = idle && parts != null)
                                    AfButton(tr(UiText.V3_CLIP_MERGE), {
                                        rows = rows.take(i) + ClipRangeInput.of(merged!!) + rows.drop(i + 2)
                                    }, Modifier.testTag("clip-merge-$i"), enabled = idle && merged != null)
                                    AfButton(tr(UiText.V3_EARLIER), { rows = rows.toMutableList().apply { add(i - 1, removeAt(i)) } }, Modifier.testTag("clip-earlier-$i"), enabled = idle && i > 0)
                                    AfButton(tr(UiText.V3_LATER), { rows = rows.toMutableList().apply { add(i + 1, removeAt(i)) } }, Modifier.testTag("clip-later-$i"), enabled = idle && i < rows.lastIndex)
                                    AfButton(tr(UiText.REMOVE), { replace(i, emptyList()) }, Modifier.testTag("clip-remove-$i"), enabled = idle && rows.size > 1)
                                }
                                AfText(tr(UiText.V3_CLIP_MERGE_HINT), muted = true)
                                AfText(tr(UiText.V3_CLIPS_NOTE), muted = true)
                            }
                        }
                    }
                    AfButton(tr(UiText.V3_CLIP_ADD), { rows = rows + ClipRangeInput() }, Modifier.testTag("clip-add"), enabled = idle)
                    if (!valid) AfText(tr(UiText.V3_CLIP_INVALID), color = AutoFlowColors.Amber)
                    edit.problem?.let { AfText(it.text(LocalUiLanguage.current), color = AutoFlowColors.Amber, modifier = Modifier.testTag("block-clips-problem")) }
                }
                StickyActionBar(StudioAction(tr(UiText.SAVE), { editor.saveClips(clips) }, idle && valid, "block-clips-save"),
                    StudioAction(tr(UiText.DISCARD), editor::cancel, tag = "block-clips-discard"))
            }
        }
    }
}