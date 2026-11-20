package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.model.SourceVideo
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
 * The subtitle editor (specs/quick-tools.md §5): view and issues chips, undo/redo, Shift times, a list keyed by cue
 * id, and Save · Discard while there are unsaved changes. [header] is the screen's back row and notices. Tap a line =
 * seek + select; while the video plays the list follows the playhead unless the owner scrolled in the last 3 s.
 * Below [WIDE_DP] window width: the video above the list and the edit panel under its line. From [WIDE_DP]: the video
 * and the edit panel on the left, the list on the right (D-22: one tree, two panes). Keys on the editor surface
 * (never inside a text field): Space play/pause, ↑/↓ previous/next line, Enter edit, Ctrl+S save, Ctrl+Z undo.
 */
@Composable
internal fun SubtitleEditor(editor: SubtitleEditorModel, path: String, video: SourceVideo, player: SubtitlePlayer,
    header: @Composable ColumnScope.() -> Unit, onChangeSource: () -> Unit) {
    val s by editor.state.collectAsState()
    val draft = s.draft ?: return
    val cues = draft.doc.cues
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var editing by remember(s.dir) { mutableStateOf<String?>(null) }
    var shifting by remember(s.dir) { mutableStateOf(false) }
    val idle = s.offer == null
    val playhead = remember(s.dir) { Playhead() }
    val live = cues.indexOfFirst { playhead.positionMs >= it.startMs && playhead.positionMs < it.endMs }
    var scrolledAt by remember { mutableLongStateOf(0L) }
    var following by remember { mutableStateOf(false) }
    val wide = windowWidthDp() >= WIDE_DP
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress && !following) scrolledAt = nowMs() }
    LaunchedEffect(live, playhead.playing) {
        if (playhead.playing && live >= 0 && nowMs() - scrolledAt > FOLLOW_PAUSE_MS) {
            following = true
            try { list.animateScrollToItem(1 + live) } finally { following = false }
        }
    }
    fun goTo(i: Int) {
        val c = cues.getOrNull(i) ?: return
        editor.select(c.id); playhead.seek(c.startMs)
        scope.launch { list.animateScrollToItem(1 + i) }
    }
    val onKey: (KeyEvent) -> Boolean = onKey@{ e ->
        if (e.type != KeyEventType.KeyDown || !idle) return@onKey false
        val at = cues.indexOfFirst { it.id == s.selected }
        val ctrl = e.isCtrlPressed || e.isMetaPressed
        when {
            ctrl && !e.isShiftPressed && e.key == Key.S -> { if (draft.dirty) editor.save() }
            ctrl && !e.isShiftPressed && e.key == Key.Z -> editor.undo()
            ctrl || e.isAltPressed || e.isShiftPressed -> return@onKey false
            e.key == Key.Spacebar -> playhead.toggle()
            editing != null -> return@onKey false // the open panel keeps its typed text until Done / Previous / Next
            e.key == Key.DirectionDown -> goTo((at + 1).coerceAtMost(cues.lastIndex))
            e.key == Key.DirectionUp -> goTo((at - 1).coerceAtLeast(0))
            e.key == Key.Enter || e.key == Key.NumPadEnter -> { if (at >= 0) editing = cues[at].id }
            else -> return@onKey false
        }
        true
    }
    @Composable
    fun panel(i: Int, c: SubtitleCue) = EditPanel(c, editor, playhead,
        onPrev = cues.getOrNull(i - 1)?.let { p -> { editing = p.id; editor.select(p.id) } },
        onNext = cues.getOrNull(i + 1)?.let { n -> { editing = n.id; editor.select(n.id) } },
        onClose = { editing = null })
    val preview: @Composable () -> Unit = { SubtitlePreview(path, video, cues, s.view, playhead, player, if (wide) 0.45f else 0.34f) }
    val cueList: @Composable (Modifier) -> Unit = { m ->
        // One list: the header scrolls away with the lines, so a phone at font 2.0 still shows them (D-22).
        LazyColumn(m.testTag("subtitles-cues"), state = list,
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
                        AfButton(My.breakSyllables(tr(UiText.SUB_SHIFT)), { shifting = !shifting }, Modifier.testTag("subtitles-shift"), enabled = idle)
                    }
                    if (shifting && idle) ShiftCard(editor, cues.indexOfFirst { it.id == s.selected }.takeIf { it >= 0 })
                }
            }
            itemsIndexed(cues, key = { _, c -> c.id }) { i, c ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CueRow(i + 1, c, s.view, s.issues[c.id], s.selected == c.id || i == live, idle, editor.config,
                        onTap = { editor.select(c.id); playhead.seek(c.startMs) },
                        onEdit = { editing = if (editing == c.id) null else c.id; editor.select(c.id) })
                    if (!wide && editing == c.id && idle) panel(i, c)
                }
            }
        }
    }
    ProjectShortcutHost(onKey = onKey) {
        Column(Modifier.fillMaxSize().testTag("subtitles-editor")) {
            if (wide) Row(Modifier.weight(1f).fillMaxWidth().testTag("subtitles-wide")) {
                Column(Modifier.weight(0.46f).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(start = screenPadding, top = screenPadding, bottom = screenPadding).testTag("subtitles-left"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    preview()
                    val i = cues.indexOfFirst { it.id == editing }
                    if (i >= 0 && idle) panel(i, cues[i])
                }
                cueList(Modifier.weight(0.54f).fillMaxHeight())
            } else {
                Box(Modifier.fillMaxWidth().padding(start = screenPadding, end = screenPadding, top = 8.dp)) { preview() }
                cueList(Modifier.weight(1f).fillMaxWidth())
            }
            if (draft.dirty) StickyActionBar(
                primary = StudioAction(tr(UiText.SAVE), { editor.save() }, enabled = idle, tag = "subtitles-save"),
                secondary = StudioAction(tr(UiText.DISCARD), editor::discard, tag = "subtitles-discard"),
                caption = My.breakSyllables(tr(UiText.SUB_NOT_SAVED)),
            )
        }
    }
}

/** The window's width in dp (the 840 dp breakpoint is the window, not the pane left after the rail). */
@Composable
private fun windowWidthDp(): Float = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value }

/**
 * Shift times (spec §5): All lines, or From the selected line to the end; each amount moves at once (Undo undoes it).
 * A shift before 0:00 is refused by `SubtitleOps.shift` with the largest allowed amount.
 */
@Composable
private fun ShiftCard(editor: SubtitleEditorModel, selected: Int?) {
    var fromLine by remember { mutableStateOf(false) }
    val from = fromLine && selected != null
    AfCard(My.breakSyllables(tr(UiText.SUB_SHIFT)), Modifier.testTag("subtitles-shift-card")) {
        WrapRow {
            AfChip(My.breakSyllables(tr(UiText.SUB_SHIFT_ALL)), !from, { fromLine = false }, Modifier.testTag("subtitles-shift-all"))
            AfChip(My.breakSyllables(tr(UiText.SUB_SHIFT_FROM, "n" to (selected ?: 0) + 1)), from, { fromLine = true },
                Modifier.testTag("subtitles-shift-from"), enabled = selected != null)
        }
        WrapRow {
            SHIFT_STEPS_MS.forEach { d ->
                val label = (if (d < 0) "−" else "+") + kotlin.math.abs(d) / 1000.0 + " s"
                AfButton(label, { editor.shift(d, if (from) editor.state.value.draft?.doc?.cues?.getOrNull(selected!!)?.id else null) },
                    Modifier.testTag("subtitles-shift-${if (d < 0) "m" else "p"}${kotlin.math.abs(d)}"))
            }
        }
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
private fun CueRow(n: Int, c: SubtitleCue, view: TextChoice, issues: Set<CueIssue>?, selected: Boolean, idle: Boolean,
    config: ToolsConfig, onTap: () -> Unit, onEdit: () -> Unit) {
    val dur = ((c.endMs - c.startMs) / 100) / 10.0
    val title = "#$n · ${TimeText.show(c.startMs)} → ${TimeText.show(c.endMs)} · $dur s"
    val editLabel = tr(UiText.SUB_EDIT_LINE)
    AfCard(title, Modifier.clickable(onClick = onTap).testTag("subtitles-cue-${c.id}"), selected, headerAction = {
        AfButton("✎", onEdit, Modifier.semantics { contentDescription = editLabel }.testTag("subtitles-cue-edit-${c.id}"), enabled = idle)
    }) {
        val my = c.textMy?.takeIf { it.isNotBlank() }
        when (view) {
            TextChoice.ORIGINAL -> AfText(My.breakSyllables(c.text))
            TextChoice.BURMESE -> AfText(My.breakSyllables(my ?: c.text), muted = my == null)
            TextChoice.BOTH -> { AfText(My.breakSyllables(c.text)); my?.let { AfText(My.breakSyllables(it), color = AutoFlowColors.Cyan) } }
        }
        if (!issues.isNullOrEmpty()) WrapRow {
            issues.forEach { AfText(My.breakSyllables(issueText(it, config)), Modifier.testTag("subtitles-issue-${c.id}-${it.name}"), color = AutoFlowColors.Amber) }
        }
    }
}

@Composable
private fun EditPanel(c: SubtitleCue, editor: SubtitleEditorModel, playhead: Playhead, onPrev: (() -> Unit)?, onNext: (() -> Unit)?,
    onClose: () -> Unit) {
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
        TimeRow(UiText.SUB_START, start, { start = it }, "start", nudge,
            usePlayhead = { editor.edit { SubtitleOps.setTimes(it, c.id, playhead.positionMs, c.endMs) } }) { d ->
            editor.edit { SubtitleOps.setTimes(it, c.id, c.startMs + d, c.endMs) }
        }
        TimeRow(UiText.SUB_END, end, { end = it }, "end", nudge,
            usePlayhead = { editor.edit { SubtitleOps.setTimes(it, c.id, c.startMs, playhead.positionMs) } }) { d ->
            editor.edit { SubtitleOps.setTimes(it, c.id, c.startMs, c.endMs + d) }
        }
        WrapRow {
            AfButton(My.breakSyllables(tr(UiText.SUB_SPLIT)), { commit(); editor.splitAt(c.id, playhead.positionMs.takeIf { playhead.inside(c) }) }, Modifier.testTag("subtitles-split"))
            AfButton(My.breakSyllables(tr(UiText.SUB_MERGE_NEXT)), { commit(); editor.edit { SubtitleOps.merge(it, c.id) } }, Modifier.testTag("subtitles-merge"))
            AfButton(My.breakSyllables(tr(UiText.SUB_ADD_AFTER)), { commit(); editor.edit { SubtitleOps.insertAfter(it, c.id) } }, Modifier.testTag("subtitles-add"))
            AfButton(My.breakSyllables(tr(UiText.SUB_DELETE)), { editor.edit { SubtitleOps.delete(it, c.id) }; onClose() },
                Modifier.testTag("subtitles-delete"), kind = ButtonKind.Danger)
        }
        WrapRow {
            onPrev?.let { AfButton(My.breakSyllables(tr(UiText.SUB_PREV_LINE)), { commit(); it() }, Modifier.testTag("subtitles-prev")) }
            AfButton(My.breakSyllables(tr(UiText.SUB_PLAY_LINE)), { playhead.playLine(c) }, Modifier.testTag("subtitles-play-line"))
            onNext?.let { AfButton(My.breakSyllables(tr(UiText.SUB_NEXT_LINE)), { commit(); it() }, Modifier.testTag("subtitles-next")) }
            AfButton(My.breakSyllables(tr(UiText.SUB_DONE)), { commit(); onClose() }, Modifier.testTag("subtitles-done"), kind = ButtonKind.Primary)
        }
    }
}

/** Start or End: typed `m:ss.t`, or ± one nudge at once (`subtitles.nudgeMs` in `config/tools.json`). */
@Composable
private fun TimeRow(label: UiText, value: String, onChange: (String) -> Unit, key: String, step: Long, usePlayhead: () -> Unit,
    nudge: (Long) -> Unit) {
    val s = step / 1000.0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AfText(My.breakSyllables(tr(label)), bold = true)
        WrapRow {
            AfTextField(value, onChange, tr(label), Modifier.width(160.dp).testTag("subtitles-$key-time"))
            AfButton("−$s", { nudge(-step) }, Modifier.testTag("subtitles-$key-minus"))
            AfButton("+$s", { nudge(step) }, Modifier.testTag("subtitles-$key-plus"))
            AfButton(My.breakSyllables(tr(UiText.SUB_USE_PLAYHEAD)), usePlayhead, Modifier.testTag("subtitles-$key-playhead"))
        }
    }
}

private fun viewLabel(v: TextChoice) = when (v) {
    TextChoice.ORIGINAL -> UiText.SUB_VIEW_ORIGINAL
    TextChoice.BURMESE -> UiText.SUB_VIEW_BURMESE
    TextChoice.BOTH -> UiText.SUB_VIEW_BOTH
}

@Composable
private fun issueText(i: CueIssue, config: ToolsConfig): String {
    val cfg = config.subtitles
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

/** Two panes from this window width (spec §1.6). */
private const val WIDE_DP = 840f

/** Shift times amounts (spec §5). */
private val SHIFT_STEPS_MS = listOf(-1_000L, -100L, 100L, 1_000L)

/** The list follows the playhead unless the owner scrolled within this long (spec §5). */
private const val FOLLOW_PAUSE_MS = 3_000L

private fun nowMs() = kotlin.time.Clock.System.now().toEpochMilliseconds()
