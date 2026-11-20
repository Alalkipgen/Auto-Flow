package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.subtitles.CueIssue
import io.github.alalkipgen.autoflow.core.subtitles.DraftFile
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleOps
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.core.subtitles.TimeText
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import kotlin.time.Instant
import kotlinx.coroutines.launch

/**
 * The subtitle editor (specs/quick-tools.md §5): view and issues chips, undo/redo, a list keyed by cue id, the edit
 * panel under the line being edited, and Save · Discard while there are unsaved changes. [header] is the screen's
 * back row and notices. Preview, playhead and Shift times come with S-3d.
 */
@Composable
internal fun SubtitleEditor(editor: SubtitleEditorModel, header: @Composable ColumnScope.() -> Unit, onChangeSource: () -> Unit) {
    val s by editor.state.collectAsState()
    val draft = s.draft ?: return
    val cues = draft.doc.cues
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var editing by remember(s.dir) { mutableStateOf<String?>(null) }
    val idle = s.offer == null
    Column(Modifier.fillMaxSize().testTag("subtitles-editor")) {
        // One list: the header scrolls away with the lines, so a phone at font 2.0 still shows them (D-22).
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("subtitles-cues"), state = list,
            contentPadding = PaddingValues(screenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    header()
                    s.offer?.let { RestoreCard(it, editor) }
                    s.notice?.let {
                        AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("subtitles-editor-notice"),
                            color = if (s.ok) AutoFlowColors.Green else AutoFlowColors.Amber)
                    }
                    WrapRow {
                        TextChoice.entries.forEach { v ->
                            AfChip(My.breakSyllables(tr(viewLabel(v))), s.view == v, { editor.setView(v) }, Modifier.testTag("subtitles-view-${v.name}"))
                        }
                        if (s.issues.isNotEmpty()) AfChip(My.breakSyllables(tr(UiText.SUB_TO_CHECK, "n" to s.issues.size)), false, {
                            editor.nextIssue()?.let { id -> scope.launch { list.animateScrollToItem(1 + cues.indexOfFirst { it.id == id }.coerceAtLeast(0)) } }
                        }, Modifier.testTag("subtitles-issues"))
                    }
                    WrapRow {
                        AfButton(My.breakSyllables(tr(UiText.SUB_UNDO)), editor::undo, Modifier.testTag("subtitles-undo"), enabled = idle && draft.canUndo)
                        AfButton(My.breakSyllables(tr(UiText.SUB_REDO)), editor::redo, Modifier.testTag("subtitles-redo"), enabled = idle && draft.canRedo)
                        AfButton(My.breakSyllables(tr(UiText.SUB_CHANGE_SOURCE)), onChangeSource, Modifier.testTag("subtitles-change-source"), enabled = !draft.dirty)
                    }
                }
            }
            itemsIndexed(cues, key = { _, c -> c.id }) { i, c ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CueRow(i + 1, c, s.view, s.issues[c.id], s.selected == c.id, editor, idle,
                        onEdit = { editing = if (editing == c.id) null else c.id; editor.select(c.id) })
                    if (editing == c.id && idle) EditPanel(c, editor,
                        onPrev = cues.getOrNull(i - 1)?.let { p -> { editing = p.id; editor.select(p.id) } },
                        onNext = cues.getOrNull(i + 1)?.let { n -> { editing = n.id; editor.select(n.id) } },
                        onClose = { editing = null })
                }
            }
        }
        if (draft.dirty) StickyActionBar(
            primary = StudioAction(tr(UiText.SAVE), { editor.save() }, enabled = idle, tag = "subtitles-save"),
            secondary = StudioAction(tr(UiText.DISCARD), editor::discard, tag = "subtitles-discard"),
            caption = My.breakSyllables(tr(UiText.SUB_NOT_SAVED)),
        )
    }
}

@Composable
private fun RestoreCard(offer: DraftFile, editor: SubtitleEditorModel) {
    val time = localStamp(Instant.fromEpochMilliseconds(offer.savedAtMs))
    AfCard(My.breakSyllables(tr(UiText.SUB_RESTORE_DRAFT, "time" to time)), Modifier.testTag("subtitles-restore")) {
        WrapRow {
            AfButton(My.breakSyllables(tr(UiText.SUB_RESTORE)), editor::restore, Modifier.testTag("subtitles-restore-yes"), kind = ButtonKind.Primary)
            AfButton(My.breakSyllables(tr(UiText.DISCARD)), editor::dismissOffer, Modifier.testTag("subtitles-restore-no"))
        }
    }
}

@Composable
private fun CueRow(n: Int, c: SubtitleCue, view: TextChoice, issues: Set<CueIssue>?, selected: Boolean, editor: SubtitleEditorModel,
    idle: Boolean, onEdit: () -> Unit) {
    val dur = ((c.endMs - c.startMs) / 100) / 10.0
    val title = "#$n · ${TimeText.show(c.startMs)} → ${TimeText.show(c.endMs)} · $dur s"
    val editLabel = tr(UiText.SUB_EDIT_LINE)
    AfCard(title, Modifier.clickable { editor.select(c.id) }.testTag("subtitles-cue-${c.id}"), selected, headerAction = {
        AfButton("✎", onEdit, Modifier.semantics { contentDescription = editLabel }.testTag("subtitles-cue-edit-${c.id}"), enabled = idle)
    }) {
        val my = c.textMy?.takeIf { it.isNotBlank() }
        when (view) {
            TextChoice.ORIGINAL -> AfText(My.breakSyllables(c.text))
            TextChoice.BURMESE -> AfText(My.breakSyllables(my ?: c.text), muted = my == null)
            TextChoice.BOTH -> { AfText(My.breakSyllables(c.text)); my?.let { AfText(My.breakSyllables(it), color = AutoFlowColors.Cyan) } }
        }
        if (!issues.isNullOrEmpty()) WrapRow {
            issues.forEach { AfText(My.breakSyllables(issueText(it, editor)), Modifier.testTag("subtitles-issue-${c.id}-${it.name}"), color = AutoFlowColors.Amber) }
        }
    }
}

@Composable
private fun EditPanel(c: SubtitleCue, editor: SubtitleEditorModel, onPrev: (() -> Unit)?, onNext: (() -> Unit)?, onClose: () -> Unit) {
    var text by remember(c.id, c.text) { mutableStateOf(c.text) }
    var my by remember(c.id, c.textMy) { mutableStateOf(c.textMy.orEmpty()) }
    var start by remember(c.id, c.startMs) { mutableStateOf(TimeText.show(c.startMs)) }
    var end by remember(c.id, c.endMs) { mutableStateOf(TimeText.show(c.endMs)) }
    val nudge = editor.config.subtitles.nudgeMs
    fun commit() {
        if (text != c.text) editor.edit { SubtitleOps.setText(it, c.id, text) }
        if (my != c.textMy.orEmpty()) editor.edit { SubtitleOps.setTextMy(it, c.id, my) }
        if (start != TimeText.show(c.startMs) || end != TimeText.show(c.endMs)) {
            val a = TimeText.parse(start)
            val b = TimeText.parse(end)
            if (a == null || b == null) editor.say(UiNotice(UiText.SUB_TIME_FORMAT))
            else editor.edit { SubtitleOps.setTimes(it, c.id, a, b) }
        }
    }
    AfCard(My.breakSyllables(tr(UiText.SUB_EDIT_LINE)), Modifier.testTag("subtitles-edit-panel")) {
        AfText(My.breakSyllables(tr(UiText.SUB_VIEW_ORIGINAL)), bold = true)
        AfTextField(text, { text = it }, tr(UiText.SUB_VIEW_ORIGINAL), Modifier.fillMaxWidth().testTag("subtitles-edit-text"), lines = 2, mono = false)
        AfText(My.breakSyllables(tr(UiText.SUB_VIEW_BURMESE)), bold = true)
        AfTextField(my, { my = it }, tr(UiText.SUB_VIEW_BURMESE), Modifier.fillMaxWidth().testTag("subtitles-edit-my"), lines = 2, mono = false)
        TimeRow(UiText.SUB_START, start, { start = it }, "start", nudge) { d -> editor.edit { SubtitleOps.setTimes(it, c.id, c.startMs + d, c.endMs) } }
        TimeRow(UiText.SUB_END, end, { end = it }, "end", nudge) { d -> editor.edit { SubtitleOps.setTimes(it, c.id, c.startMs, c.endMs + d) } }
        WrapRow {
            AfButton(My.breakSyllables(tr(UiText.SUB_SPLIT)), { commit(); editor.splitAtBreak(c.id) }, Modifier.testTag("subtitles-split"))
            AfButton(My.breakSyllables(tr(UiText.SUB_MERGE_NEXT)), { commit(); editor.edit { SubtitleOps.merge(it, c.id) } }, Modifier.testTag("subtitles-merge"))
            AfButton(My.breakSyllables(tr(UiText.SUB_ADD_AFTER)), { commit(); editor.edit { SubtitleOps.insertAfter(it, c.id) } }, Modifier.testTag("subtitles-add"))
            AfButton(My.breakSyllables(tr(UiText.SUB_DELETE)), { editor.edit { SubtitleOps.delete(it, c.id) }; onClose() },
                Modifier.testTag("subtitles-delete"), kind = ButtonKind.Danger)
        }
        WrapRow {
            onPrev?.let { AfButton(My.breakSyllables(tr(UiText.SUB_PREV_LINE)), { commit(); it() }, Modifier.testTag("subtitles-prev")) }
            onNext?.let { AfButton(My.breakSyllables(tr(UiText.SUB_NEXT_LINE)), { commit(); it() }, Modifier.testTag("subtitles-next")) }
            AfButton(My.breakSyllables(tr(UiText.SUB_DONE)), { commit(); onClose() }, Modifier.testTag("subtitles-done"), kind = ButtonKind.Primary)
        }
    }
}

/** Start or End: typed `m:ss.t`, or ± one nudge at once (`subtitles.nudgeMs` in `config/tools.json`). */
@Composable
private fun TimeRow(label: UiText, value: String, onChange: (String) -> Unit, key: String, step: Long, nudge: (Long) -> Unit) {
    val s = step / 1000.0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AfText(My.breakSyllables(tr(label)), bold = true)
        FieldRow({ m -> AfTextField(value, onChange, tr(label), m.widthIn(min = 120.dp).testTag("subtitles-$key-time")) }) {
            AfButton("−$s", { nudge(-step) }, Modifier.testTag("subtitles-$key-minus"))
            AfButton("+$s", { nudge(step) }, Modifier.testTag("subtitles-$key-plus"))
        }
    }
}

private fun viewLabel(v: TextChoice) = when (v) {
    TextChoice.ORIGINAL -> UiText.SUB_VIEW_ORIGINAL
    TextChoice.BURMESE -> UiText.SUB_VIEW_BURMESE
    TextChoice.BOTH -> UiText.SUB_VIEW_BOTH
}

@Composable
private fun issueText(i: CueIssue, editor: SubtitleEditorModel): String {
    val cfg = editor.config.subtitles
    return when (i) {
        CueIssue.OVERLAP -> tr(UiText.SUB_OVERLAP)
        CueIssue.TOO_SHORT -> tr(UiText.SUB_TOO_SHORT, "s" to cfg.minCueMs / 1000.0)
        CueIssue.LINE_TOO_LONG -> tr(UiText.SUB_LINE_TOO_LONG)
        CueIssue.TOO_MANY_LINES -> tr(UiText.SUB_TOO_MANY_LINES, "n" to cfg.maxLines)
        CueIssue.EMPTY -> tr(UiText.SUB_EMPTY)
        CueIssue.NOT_TRANSLATED -> tr(UiText.SUB_NOT_TRANSLATED)
        CueIssue.ORIGINAL_CHANGED -> tr(UiText.SUB_ORIGINAL_CHANGED)
    }
}
