package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import io.github.alalkipgen.autoflow.core.recap.RecapBlock
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import androidx.compose.ui.Alignment

/** Source cuts are edited independently from voice/caption timing. Nothing is persisted until Save. */
@Composable
internal fun BlockClipsCard(model: RecapModel, state: RecapUiState, block: RecapBlock) {
    val editor = model.blockEditor
    val edit by editor.state.collectAsState()
    val idle = !state.busy && state.recording == null
    DisposableEffect(state.projectDir, block.id) { onDispose { editor.cancel() } }
    // Group 2 C 11: scissors tile, each range a film-strip row in mono (never broken), Edit = glass pencil button.
    TileCard(tr(UiText.V3_CLIPS), RecapTiles.Clips) {
        block.clips.forEach {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfIcon(AfIcons.Regular.FilmStrip, tint = AutoFlowColors.Muted, size = 17.dp)
                AfText("${ClipRangeInput.seconds(it.inMs)}–${ClipRangeInput.seconds(it.outMs)} s", mono = true, maxLines = 1, muted = true)
            }
        }
        WrapRow { GlassButton(tr(UiText.V3_EDIT_CLIPS), { editor.edit(block.id, clips = true) },
            Modifier.testTag("block-clips-edit"), enabled = idle && state.recap?.movie != null, icon = AfIcons.Regular.Edit) }
    }
    val draft = edit.draft?.takeIf { edit.editingClips && it.block.id == block.id } ?: return
    key(draft.token) {
        var rows by remember { mutableStateOf(draft.block.clips.map(ClipRangeInput::of)) }
        val clips = rows.mapNotNull { it.clip() }
        val valid = clips.size == rows.size && ClipRangeInput.valid(clips, draft.project.recap?.movie?.durationMs ?: 0)
        fun replace(i: Int, values: List<ClipRangeInput>) { rows = rows.take(i) + values + rows.drop(i + 1) }
        // Group 2 C 13b: the glass dialog (scissors tile, the hint under the title), each range a film-strip card with Start | End
        // side by side (stacked above font 1.3; an invalid range = amber rings), tools with icons, problems as amber callouts.
        GlassDialog(editor::cancel, RecapTiles.Clips.icon, RecapTiles.Clips.colour, tr(UiText.V3_CLIPS), subtitle = tr(UiText.V3_CLIPS_HINT), footer = {
            GlassDialogButtons(GlassDialogAction(tr(UiText.DISCARD), editor::cancel, tag = "block-clips-discard"),
                GlassDialogAction(tr(UiText.SAVE), { editor.saveClips(clips) }, ButtonKind.Primary, AfIcons.Regular.Check, idle && valid, "block-clips-save"))
        }) {
            Column(Modifier.testTag("block-clips-form"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val side = LocalDensity.current.fontScale <= 1.3f
                rows.forEachIndexed { i, input ->
                    TileCard(tr(UiText.V3_CLIP_NUMBER, "n" to i + 1), CardTile(AfIcons.Fill.FilmStrip, RecapTiles.Clips.colour), Modifier.testTag("clip-range-$i")) {
                        val bad = input.clip() == null
                        @Composable fun Field(label: String, value: String, change: (String) -> Unit, tag: String, modifier: Modifier) =
                            Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                AfText(label, bold = true)
                                AfTextField(value, change, label, Modifier.fillMaxWidth().testTag(tag), enabled = idle, glass = true, error = bad)
                            }
                        if (side) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field(tr(UiText.V3_CLIP_FROM), input.from, { replace(i, listOf(input.copy(from = it))) }, "clip-from-$i", Modifier.weight(1f))
                            Field(tr(UiText.V3_CLIP_TO), input.to, { replace(i, listOf(input.copy(to = it))) }, "clip-to-$i", Modifier.weight(1f))
                        } else {
                            Field(tr(UiText.V3_CLIP_FROM), input.from, { replace(i, listOf(input.copy(from = it))) }, "clip-from-$i", Modifier.fillMaxWidth())
                            Field(tr(UiText.V3_CLIP_TO), input.to, { replace(i, listOf(input.copy(to = it))) }, "clip-to-$i", Modifier.fillMaxWidth())
                        }
                        SectionCard(tr(UiText.V3_MORE_OPTIONS), initiallyExpanded = false, tile = RecapTiles.MoreOptions) {
                            val clip = input.clip()
                            var split by remember(input.from, input.to) { mutableStateOf(clip?.let {
                                ClipRangeInput.seconds(it.inMs + it.durationMs / 2) }.orEmpty()) }
                            AfText(tr(UiText.V3_CLIP_SPLIT_AT), bold = true)
                            AfTextField(split, { split = it }, tr(UiText.V3_CLIP_SPLIT_AT),
                                Modifier.fillMaxWidth().testTag("clip-split-at-$i"), enabled = idle, glass = true)
                            val parts = clip?.let { c -> ClipRangeInput.parse(split)?.let { ClipRangeInput.split(c, it) } }
                            val merged = clip?.let { a -> rows.getOrNull(i + 1)?.clip()?.let { ClipRangeInput.merge(a, it) } }
                            WrapRow(spacing = 8.dp) {
                                GlassButton(tr(UiText.V3_CLIP_SPLIT), { replace(i, parts!!.map(ClipRangeInput::of)) }, Modifier.testTag("clip-split-$i"),
                                    enabled = idle && parts != null, icon = AfIcons.Regular.ArrowsSplit, small = true)
                                GlassButton(tr(UiText.V3_CLIP_MERGE), {
                                    rows = rows.take(i) + ClipRangeInput.of(merged!!) + rows.drop(i + 2)
                                }, Modifier.testTag("clip-merge-$i"), enabled = idle && merged != null, icon = AfIcons.Regular.ArrowsMerge, small = true)
                                GlassButton(tr(UiText.V3_EARLIER), { rows = rows.toMutableList().apply { add(i - 1, removeAt(i)) } }, Modifier.testTag("clip-earlier-$i"),
                                    enabled = idle && i > 0, icon = AfIcons.Regular.ArrowUp, small = true)
                                GlassButton(tr(UiText.V3_LATER), { rows = rows.toMutableList().apply { add(i + 1, removeAt(i)) } }, Modifier.testTag("clip-later-$i"),
                                    enabled = idle && i < rows.lastIndex, icon = AfIcons.Regular.ArrowDown, small = true)
                                GlassButton(tr(UiText.REMOVE), { replace(i, emptyList()) }, Modifier.testTag("clip-remove-$i"), ButtonKind.Danger,
                                    enabled = idle && rows.size > 1, icon = AfIcons.Regular.Trash, small = true)
                            }
                            GlassFact(AfIcons.Regular.Info, tr(UiText.V3_CLIP_MERGE_HINT), colour = AutoFlowColors.Muted, caption = true)
                            GlassFact(AfIcons.Regular.Info, tr(UiText.V3_CLIPS_NOTE), colour = AutoFlowColors.Muted, caption = true)
                        }
                    }
                }
                WrapRow { GlassButton(tr(UiText.V3_CLIP_ADD), { rows = rows + ClipRangeInput() }, Modifier.testTag("clip-add"), enabled = idle,
                    icon = AfIcons.Regular.Plus) }
                if (!valid) GlassCallout(AfIcons.Fill.Warn, tr(UiText.V3_CLIP_INVALID), AutoFlowColors.Amber)
                edit.problem?.let { GlassCallout(AfIcons.Fill.Warn, it.text(LocalUiLanguage.current), AutoFlowColors.Amber,
                    textModifier = Modifier.testTag("block-clips-problem")) }
            }
        }
    }
}