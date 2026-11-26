package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.afTooltip
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
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.theme.*
import kotlin.time.Instant
import kotlinx.coroutines.launch

/**
 * The subtitle editor (specs/quick-tools.md §5): view and issues chips, undo/redo, Shift times, a list keyed by cue
 * id, Save · Discard while there are unsaved changes, else Export (S-4a). [header] is the screen's back row and notices. Tap a line =
 * seek + select; while the video plays the list follows the playhead unless the owner scrolled in the last 3 s.
 * Below [WIDE_DP] window width: the video and its cue strip pinned above the list when the list keeps its room
 * ([AfSize.SubtitlesPinMin] × font scale; else at the top of the list), the edit panel under its line. From [WIDE_DP]: the video
 * and the edit panel on the left, the list on the right (D-22: one tree, two panes). Keys on the editor surface
 * (never inside a text field): Space play/pause, ↑/↓ previous/next line, Enter edit, Ctrl+S save, Ctrl+Z undo.
 */
@Composable
internal fun SubtitleEditor(editor: SubtitleEditorModel, path: String, video: SourceVideo, player: SubtitlePlayer,
    header: @Composable ColumnScope.() -> Unit, onChangeSource: () -> Unit, exporter: SubtitleExportModel? = null,
    burner: SubtitleBurnModel? = null, translate: SubtitleTranslateModel? = null) {
    val s by editor.state.collectAsState()
    val draft = s.draft ?: return
    val cues = draft.doc.cues
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var editing by remember(s.dir) { mutableStateOf<String?>(null) }
    var shifting by remember(s.dir) { mutableStateOf(false) }
    var exporting by remember(s.dir) { mutableStateOf(false) }
    val translating = translate?.jobOf(s.dir, translate.jobs.collectAsState().value) // S-6b: read-only while it runs
    val idle = s.offer == null && translating == null
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
    fun panel(i: Int, c: SubtitleCue) = EditPanel(i + 1, c, editor, playhead,
        onPrev = cues.getOrNull(i - 1)?.let { p -> { editing = p.id; editor.select(p.id) } },
        onNext = cues.getOrNull(i + 1)?.let { n -> { editing = n.id; editor.select(n.id) } },
        wide = wide, onClose = { editing = null })
    val preview: @Composable () -> Unit = {
        // Group 5 G: the preview card; 🆕-2 its seek bar = the cue strip (tap / drag seeks, never retimes); the desktop has
        // the time in the controls row (H 1), a phone on the video.
        SubtitlePreview(path, video, cues, s.view, playhead, player, if (wide) 0.45f else 0.34f, timeOnVideo = !wide,
            seekBar = { m, full -> CueStrip(cues, video.durationMs, playhead.positionMs, s.selected, s.issues, m, onSeek = playhead::seek,
                tag = if (full) "subtitles-fullscreen" else "subtitles") })
    }
    val cueList: @Composable (Modifier, Boolean) -> Unit = { m, withPreview ->
        // One list: the header scrolls away with the lines, so a phone at font 2.0 still shows them (D-22) — with the video
        // when there is no room to pin it.
        LazyColumn(m.testTag("subtitles-cues"), state = list,
            contentPadding = PaddingValues(screenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (withPreview) preview()
                    header()
                    s.offer?.let { RestoreCard(it, editor) }
                    s.notice?.let { // Group 5 A 8 / B: a callout — green `check-circle` done, amber `warning` refused
                        GlassCallout(if (s.ok) AfIcons.Fill.CheckCircle else AfIcons.Fill.Warn, My.breakSyllables(it.text(LocalUiLanguage.current)),
                            if (s.ok) AutoFlowColors.Green else AutoFlowColors.Amber, textModifier = Modifier.testTag("subtitles-editor-notice"))
                    }
                    // Group 5 A 5: the glass view switch (as wide as its labels) + the amber "3 to check" chip; then two small glass
                    // buttons (Shift times = the purple look while its card is open). 🆕-8: Undo / Redo are in the top bar (SubtitleUndoRedo).
                    WrapRow(spacing = 10.dp) {
                        GlassSegmented(TextChoice.entries.map { GlassSegment(tr(viewLabel(it)), "subtitles-view-${it.name}") },
                            TextChoice.entries.indexOf(s.view), { editor.setView(TextChoice.entries[it]) }, hug = true)
                        if (s.issues.isNotEmpty()) IssuesChip(My.breakSyllables(tr(UiText.SUB_TO_CHECK, "n" to s.issues.size))) {
                            editor.nextIssue()?.let { id -> scope.launch { list.animateScrollToItem(1 + cues.indexOfFirst { it.id == id }.coerceAtLeast(0)) } }
                        }
                    }
                    WrapRow(spacing = 10.dp) {
                        GlassButton(My.breakSyllables(tr(UiText.SUB_CHANGE_SOURCE)), onChangeSource, Modifier.testTag("subtitles-change-source"),
                            enabled = !draft.dirty && translating == null, icon = AfIcons.Regular.Swap, small = true)
                        GlassButton(My.breakSyllables(tr(UiText.SUB_SHIFT)), { shifting = !shifting }, Modifier.testTag("subtitles-shift"), enabled = idle,
                            icon = AfIcons.Regular.Clock, small = true, accent = AutoFlowColors.Purple.takeIf { shifting && idle })
                    }
                    if (shifting && idle) ShiftCard(editor, cues.indexOfFirst { it.id == s.selected }.takeIf { it >= 0 })
                }
            }
            itemsIndexed(cues, key = { _, c -> c.id }) { i, c ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CueRow(i + 1, c, s.view, s.issues[c.id], s.selected == c.id, i == live, editing == c.id, idle, editor.config,
                        onTap = { editor.select(c.id); playhead.seek(c.startMs) },
                        onEdit = { editing = if (editing == c.id) null else c.id; editor.select(c.id) })
                    if (!wide && editing == c.id && idle) panel(i, c)
                }
            }
        }
    }
    // D4: the keys [onKey] handles, on the desktop bar (never while a translation locks the editor).
    val keys: (@Composable () -> Unit)? = if (wide && idle) ({
        KeyHints(listOf("Space" to tr(UiText.SUB_KEY_PLAY), "↑ ↓" to tr(UiText.SUB_KEY_LINE), "Enter" to tr(UiText.SUB_EDIT_LINE),
            "Ctrl S" to tr(UiText.SAVE), "Ctrl Z" to tr(UiText.SUB_UNDO)), Modifier.testTag("subtitles-keys"))
    }) else null
    // The editor's video waits while the export's own preview plays (one sound at a time).
    LaunchedEffect(exporting) { if (exporting) playhead.playing = false }
    if (exporting && exporter != null && !draft.dirty) SubtitleExportDialog(exporter, draft.doc, draft.base, { exporting = false }, burner,
        cues.indexOfFirst { it.id == s.selected }.takeIf { it >= 0 }, BurnPreviewVideo(path, video, player))
    ProjectShortcutHost(onKey = onKey) {
        Column(Modifier.fillMaxSize().testTag("subtitles-editor")) {
            // D4: the video, its strip and the open line's panel left; the lines in a 460 dp pane right.
            if (wide) EditorPanes(windowWidthDp(), main = {
                Column(Modifier.fillMaxSize().paneScroll(rememberScrollState()).testTag("subtitles-left"),
                    verticalArrangement = Arrangement.spacedBy(AfSpace.M)) {
                    preview()
                    val i = cues.indexOfFirst { it.id == editing }
                    if (i >= 0 && idle) panel(i, cues[i])
                }
            }, side = {
                val pane = RoundedCornerShape(AfDesktop.PaneRadius)
                Box(Modifier.fillMaxSize().clip(pane).background(AutoFlowColors.Panel).border(AutoFlowShapes.Border, AutoFlowColors.Line, pane)
                    .testTag("subtitles-list-pane")) { cueList(Modifier.fillMaxSize(), false) }
            }, Modifier.weight(1f).padding(start = screenPadding, end = screenPadding, top = AfSpace.L, bottom = AfSpace.L).testTag("subtitles-wide"))
            else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val pin = maxHeight >= AfSize.SubtitlesPinMin * LocalDensity.current.fontScale
                Column(Modifier.fillMaxSize()) {
                    if (pin) Column(Modifier.fillMaxWidth().padding(start = screenPadding, end = screenPadding, top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(AfSpace.S)) { preview() }
                    cueList(Modifier.weight(1f).fillMaxWidth(), !pin)
                }
            }
            // Group 5 A 10: the glass sticky bar. Translating = Cancel job (danger) + the lock caption; dirty = Discard (hugs its
            // label) + Save (the room) + 🆕-6 "4 lines edited · not saved yet"; saved = Translate (purple outline, the room) + Export
            // (hugs), or Export alone; Dubbing (no exporter) = Translate primary only.
            if (translating != null) StickyActionBar(
                primary = StudioAction(tr(UiText.CANCEL_JOB), { translate.cancel(translating) }, tag = "subtitles-translate-cancel",
                    icon = AfIcons.Regular.Close, danger = true),
                caption = My.breakSyllables(tr(UiText.SUB_TRANSLATE_LOCKED)), glass = true, captionIcon = AfIcons.Regular.Lock,
            )
            else if (draft.dirty) StickyActionBar(
                primary = StudioAction(tr(UiText.SAVE), { editor.save() }, enabled = idle, tag = "subtitles-save", icon = AfIcons.Regular.Check),
                secondary = StudioAction(tr(UiText.DISCARD), editor::discard, tag = "subtitles-discard", icon = AfIcons.Regular.Trash, hug = true),
                caption = My.breakSyllables(tr(UiText.V3_SUB_EDITED_CAPTION, "n" to draft.changedLines)), keys = keys, glass = true,
                captionIcon = AfIcons.Regular.Edit, captionColour = AutoFlowColors.WarningText, captionTag = "subtitles-edited-caption",
            )
            else {
                val toMy = translate?.takeIf { it.offers(draft.doc.lang) }?.let { m ->
                    StudioAction(My.breakSyllables(tr(UiText.SUB_TRANSLATE)), { m.ask() }, // 🆕-4: the confirm is a dialog, no jump to the top
                        enabled = idle, tag = "subtitles-translate", icon = AfIcons.Regular.Spark, accent = AutoFlowColors.Purple.takeIf { exporter != null })
                }
                val export = exporter?.let { StudioAction(tr(UiText.EXPORT), { exporting = true }, enabled = idle, tag = "subtitles-export-open",
                    icon = AfIcons.Regular.Export, hug = toMy != null) }
                if (export != null) StickyActionBar(primary = export, secondary = toMy, keys = keys, glass = true)
                else if (toMy != null) StickyActionBar(primary = toMy, keys = keys, glass = true)
            }
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
    // Group 5 A 6: a glass card with the cyan `clock` tile, the All / From line N filter chips and the four amounts.
    GlassCard(Modifier.testTag("subtitles-shift-card"), tint = AutoFlowColors.Cyan, tintAlpha = .10f, gap = 10.dp) {
        GlassCardHeader(My.breakSyllables(tr(UiText.SUB_SHIFT)), { SoftTile(AfIcons.Fill.Clock, AutoFlowColors.Cyan, size = 30.dp) })
        WrapRow(spacing = 8.dp) {
            GlassFilterChip(My.breakSyllables(tr(UiText.SUB_SHIFT_ALL)), !from, { fromLine = false }, Modifier.testTag("subtitles-shift-all"))
            GlassFilterChip(My.breakSyllables(tr(UiText.SUB_SHIFT_FROM, "n" to (selected ?: 0) + 1)), from, { fromLine = true },
                Modifier.testTag("subtitles-shift-from"), enabled = selected != null)
        }
        WrapRow(spacing = 8.dp) {
            SHIFT_STEPS_MS.forEach { d ->
                val label = (if (d < 0) "−" else "+") + kotlin.math.abs(d) / 1000.0 + " s"
                GlassButton(label, { editor.shift(d, if (from) editor.state.value.draft?.doc?.cues?.getOrNull(selected!!)?.id else null) },
                    Modifier.testTag("subtitles-shift-${if (d < 0) "m" else "p"}${kotlin.math.abs(d)}"), small = true)
            }
        }
    }
}

@Composable
private fun RestoreCard(offer: DraftFile, editor: SubtitleEditorModel) {
    val time = localStamp(Instant.fromEpochMilliseconds(offer.savedAtMs))
    // Group 5 A 7: an amber glass card, the `clock-counter-clockwise` tile; Restore (primary) + Discard (`trash`).
    GlassCard(Modifier.testTag("subtitles-restore"), tint = AutoFlowColors.Amber, tintAlpha = .12f, gap = 10.dp) {
        GlassCardHeader(My.breakSyllables(tr(UiText.SUB_RESTORE_DRAFT, "time" to time)),
            { SoftTile(AfIcons.Fill.ClockCounterClockwise, AutoFlowColors.Amber, size = 30.dp) })
        WrapRow(spacing = 8.dp) {
            GlassButton(My.breakSyllables(tr(UiText.SUB_RESTORE)), editor::restore, Modifier.testTag("subtitles-restore-yes"), kind = ButtonKind.Primary,
                icon = AfIcons.Regular.ClockCounterClockwise, small = true)
            GlassButton(My.breakSyllables(tr(UiText.DISCARD)), editor::dismissOffer, Modifier.testTag("subtitles-restore-no"), icon = AfIcons.Regular.Trash,
                small = true)
        }
    }
}

/**
 * One line (DESIGN.md §9.7, panel 5; Group 5 A 9): a glass card — "#n" (14 sp bold) and "start → end · length" (mono 12 sp
 * muted) with its checks as tone chips (red = not translated, amber = the rest; the card's left bar the same), the 40 dp
 * glass Edit button, then the text — in Both, the original (smaller, muted) above the Burmese line, in the export's order.
 * 🆕-3: [selected] (tapped) = purple tint .10 + purple outline; [live] (under the playhead) = a cyan .5 outline; purple wins.
 */
@Composable
private fun CueRow(n: Int, c: SubtitleCue, view: TextChoice, issues: Set<CueIssue>?, selected: Boolean, live: Boolean, editing: Boolean, idle: Boolean,
    config: ToolsConfig, onTap: () -> Unit, onEdit: () -> Unit) {
    val dur = ((c.endMs - c.startMs) / 100) / 10.0
    val tone = when { issues.isNullOrEmpty() -> null; CueIssue.NOT_TRANSLATED in issues -> Tone.Error; else -> Tone.Warning }
    GlassCard(Modifier.testTag("subtitles-cue-${c.id}"), attention = tone, onClick = onTap,
        tint = AutoFlowColors.Purple.takeIf { selected }, tintAlpha = .10f,
        padding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 12.dp), gap = 4.dp,
        outline = when { selected -> AutoFlowColors.Purple; live -> AutoFlowColors.Cyan.copy(alpha = .5f); else -> null }) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(AfSpace.S)) {
            WrapRow(Modifier.weight(1f).padding(top = 6.dp), spacing = 8.dp) {
                AfText("#$n", color = if (selected) AutoFlowColors.PrimaryText else AutoFlowColors.Text, type = AfType.Button, weight = FontWeight.Bold)
                AfText("${TimeText.show(c.startMs)} → ${TimeText.show(c.endMs)} · $dur s", color = AutoFlowColors.Muted, mono = true, size = 12.sp)
                issues?.forEach {
                    val untranslated = it == CueIssue.NOT_TRANSLATED
                    ToneChip(issueText(it, config), if (untranslated) Tone.Error else Tone.Warning, Modifier.testTag("subtitles-issue-${c.id}-${it.name}"),
                        AfIcons.Fill.Warn.takeIf { !untranslated })
                }
            }
            GlassIconButton(AfIcons.Regular.Edit, tr(UiText.SUB_EDIT_LINE), onEdit, Modifier.testTag("subtitles-cue-edit-${c.id}"),
                size = GlassShapes.IconButtonHeader, selected = editing, enabled = idle)
        }
        val my = c.textMy?.takeIf { it.isNotBlank() }
        when {
            view == TextChoice.ORIGINAL -> AfText(My.breakSyllables(c.text), type = AfType.Body)
            view == TextChoice.BURMESE || my == null -> AfText(My.breakSyllables(my ?: c.text), muted = my == null, type = AfType.Body)
            else -> { AfText(My.breakSyllables(c.text), muted = true, type = AfType.Caption); AfText(My.breakSyllables(my), type = AfType.Body) }
        }
    }
}

/** The amber "3 to check" chip (Group 5 A 5): `warning` Fill 15 dp, amber .12 fill and .45 border; tap = the next line with a check. */
@Composable
private fun IssuesChip(text: String, onClick: () -> Unit) {
    val shape = GlassShapes.Pill
    Row(Modifier.touchOverflow(vertical = (AfSize.Touch - AfSize.FilterChip) / 2).testTag("subtitles-issues")
        .heightIn(min = AfSize.FilterChip).clip(shape).background(AutoFlowColors.Amber.copy(alpha = .12f))
        .border(1.dp, AutoFlowColors.Amber.copy(alpha = .45f), shape).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AfIcon(AfIcons.Fill.Warn, tint = AutoFlowColors.Amber, size = 15.dp)
        AfText(text, color = AutoFlowColors.WarningText, type = AfType.ButtonSmall)
    }
}

/**
 * The open line (Group 5 B): a purple glass card — the `pencil` tile + SUB_EDIT_LINE ([wide]: the "#n" pill at the
 * right of the head); one small label above each field (§8.3: no label inside); Start / End = label + a stepper
 * [ − | time | + ] + Use playhead ([wide]: side by side); a divider, the line tools, then the move row with Done at the
 * end. Same edits, commit-on-move and Done rules as before.
 */
@Composable
private fun EditPanel(n: Int, c: SubtitleCue, editor: SubtitleEditorModel, playhead: Playhead, onPrev: (() -> Unit)?, onNext: (() -> Unit)?,
    wide: Boolean, onClose: () -> Unit) {
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
    val title = My.breakSyllables(tr(UiText.SUB_EDIT_LINE))
    GlassCard(Modifier.testTag("subtitles-edit-panel"), tint = AutoFlowColors.Purple, tintAlpha = .10f, gap = 10.dp) {
        GlassCardHeader(title, { SoftTile(AfIcons.Fill.Edit, AutoFlowColors.Purple, Modifier.testTag("card-icon"), size = 30.dp) },
            trailing = if (wide) ({ GlassPill("#$n") }) else null)
        FieldLabel(UiText.SUB_VIEW_ORIGINAL)
        AfTextField(text, { text = it }, tr(UiText.SUB_VIEW_ORIGINAL), Modifier.fillMaxWidth().testTag("subtitles-edit-text"),
            lines = if (wide) 1 else 2, mono = false, glass = true, showPlaceholder = false, multiline = true)
        FieldLabel(UiText.SUB_VIEW_BURMESE)
        AfTextField(my, { my = it }, tr(UiText.SUB_VIEW_BURMESE), Modifier.fillMaxWidth().testTag("subtitles-edit-my"),
            lines = if (wide) 1 else 2, mono = false, glass = true, showPlaceholder = false, multiline = true)
        val startRow: @Composable (Modifier) -> Unit = { m ->
            TimeRow(UiText.SUB_START, start, { start = it }, "start", nudge, m,
                usePlayhead = { editor.edit { SubtitleOps.setTimes(it, c.id, playhead.positionMs, c.endMs) } }) { d ->
                editor.edit { SubtitleOps.setTimes(it, c.id, c.startMs + d, c.endMs) }
            }
        }
        val endRow: @Composable (Modifier) -> Unit = { m ->
            TimeRow(UiText.SUB_END, end, { end = it }, "end", nudge, m,
                usePlayhead = { editor.edit { SubtitleOps.setTimes(it, c.id, c.startMs, playhead.positionMs) } }) { d ->
                editor.edit { SubtitleOps.setTimes(it, c.id, c.startMs, c.endMs + d) }
            }
        }
        // Desktop: Start and End side by side while each half still holds its stepper; else one under the other.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (wide && (maxWidth - 16.dp) / 2 >= stepperWidth()) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                startRow(Modifier.weight(1f)); endRow(Modifier.weight(1f))
            } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { startRow(Modifier); endRow(Modifier) }
        }
        GlassHairline()
        WrapRow(spacing = 8.dp) {
            GlassButton(My.breakSyllables(tr(UiText.SUB_SPLIT)), { commit(); editor.splitAt(c.id, playhead.positionMs.takeIf { playhead.inside(c) }) },
                Modifier.testTag("subtitles-split"), icon = AfIcons.Regular.Scissors, small = true)
            GlassButton(My.breakSyllables(tr(UiText.SUB_MERGE_NEXT)), { commit(); editor.edit { SubtitleOps.merge(it, c.id) } },
                Modifier.testTag("subtitles-merge"), icon = AfIcons.Regular.ArrowsMerge, small = true)
            GlassButton(My.breakSyllables(tr(UiText.SUB_ADD_AFTER)), { commit(); editor.edit { SubtitleOps.insertAfter(it, c.id) } },
                Modifier.testTag("subtitles-add"), icon = AfIcons.Regular.Plus, small = true)
            GlassButton(My.breakSyllables(tr(UiText.SUB_DELETE)), { editor.edit { SubtitleOps.delete(it, c.id) }; onClose() },
                Modifier.testTag("subtitles-delete"), kind = ButtonKind.Danger, icon = AfIcons.Regular.Trash, small = true)
        }
        // The move row: Previous · Play line · Next, then Done at the end of the row (on its own row, right, when it does not fit).
        WrapRow(spacing = 8.dp) {
            onPrev?.let { GlassButton(My.breakSyllables(tr(UiText.SUB_PREV_LINE)), { commit(); it() }, Modifier.testTag("subtitles-prev"),
                icon = AfIcons.Regular.CaretLeft, small = true) }
            GlassButton(My.breakSyllables(tr(UiText.SUB_PLAY_LINE)), { playhead.playLine(c) }, Modifier.testTag("subtitles-play-line"),
                icon = AfIcons.Fill.Play, small = true)
            onNext?.let { GlassButton(My.breakSyllables(tr(UiText.SUB_NEXT_LINE)), { commit(); it() }, Modifier.testTag("subtitles-next"),
                trailingIcon = AfIcons.Regular.CaretRight, small = true) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                GlassButton(My.breakSyllables(tr(UiText.SUB_DONE)), { commit(); onClose() }, Modifier.testTag("subtitles-done"), kind = ButtonKind.Primary,
                    icon = AfIcons.Regular.Check, small = true)
            }
        }
    }
}

/** One small muted label above a field (Group 5 B 2, 11.5 sp). */
@Composable
private fun FieldLabel(text: UiText) = AfText(My.breakSyllables(tr(text)), color = AutoFlowColors.Muted, size = 11.5.sp)

/**
 * Start or End (Group 5 B 3): a small label, then one glass pill [ − | typed `m:ss.t` (mono) | + ] (± one nudge at once,
 * `subtitles.nudgeMs` in `config/tools.json`; the − / + descriptions say the amount) and the small Use playhead button.
 */
@Composable
private fun TimeRow(label: UiText, value: String, onChange: (String) -> Unit, key: String, step: Long, modifier: Modifier, usePlayhead: () -> Unit,
    nudge: (Long) -> Unit) {
    val s = step / 1000.0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        WrapRow(spacing = 8.dp) {
            TimeStepper(value, onChange, tr(label), key, "−$s s", "+$s s", { nudge(-step) }, { nudge(step) })
            GlassButton(My.breakSyllables(tr(UiText.SUB_USE_PLAYHEAD)), usePlayhead, Modifier.testTag("subtitles-$key-playhead"),
                icon = AfIcons.Regular.Crosshair, small = true)
        }
    }
}

/** [ − | field | + ]: one glass pill (radius 12, 48 dp), 40 dp icon cells and hairlines between; the field mono, centred, cyan ring on focus. */
@Composable
private fun TimeStepper(value: String, onChange: (String) -> Unit, description: String, key: String, minus: String, plus: String,
    onMinus: () -> Unit, onPlus: () -> Unit) {
    val shape = GlassShapes.Field
    var focused by remember { mutableStateOf(false) }
    val fieldW = stepFieldWidth()
    Row(Modifier.height(AfSize.Touch).clip(shape).background(GlassColors.FieldFill).border(1.dp, GlassColors.FieldBorder, shape),
        verticalAlignment = Alignment.CenterVertically) {
        StepCell(AfIcons.Regular.Minus, minus, onMinus, Modifier.testTag("subtitles-$key-minus"))
        Box(Modifier.width(1.dp).fillMaxHeight().background(GlassColors.FieldBorder))
        Box(Modifier.width(fieldW).fillMaxHeight().then(if (focused) Modifier.border(1.5.dp, AutoFlowColors.Cyan, RoundedCornerShape(8.dp)) else Modifier)
            .onFocusChanged { focused = it.hasFocus }.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
            BasicTextField(value, onChange, Modifier.fillMaxWidth().testTag("subtitles-$key-time").semantics { contentDescription = description },
                singleLine = true, textStyle = afTextStyle(AutoFlowColors.Text, 14.sp, FontWeight.Normal, FontFamily.Monospace).copy(textAlign = TextAlign.Center),
                cursorBrush = SolidColor(AutoFlowColors.Cyan))
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(GlassColors.FieldBorder))
        StepCell(AfIcons.Regular.Plus, plus, onPlus, Modifier.testTag("subtitles-$key-plus"))
    }
}

/** The stepper's field: 96 dp × the font scale, at most 160 dp; the whole stepper = the field + two 40 dp cells + hairlines. */
@Composable
private fun stepFieldWidth() = (96.dp * LocalDensity.current.fontScale).coerceAtMost(160.dp)

@Composable
private fun stepperWidth() = stepFieldWidth() + 82.dp

@Composable
private fun StepCell(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.width(40.dp).fillMaxHeight().afTooltip(label).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        AfIcon(icon, tint = AutoFlowColors.Text, size = AfSize.IconSmall)
    }
}

internal fun viewLabel(v: TextChoice) = when (v) {
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
