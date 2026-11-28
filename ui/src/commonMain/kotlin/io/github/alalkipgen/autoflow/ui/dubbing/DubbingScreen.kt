package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.dubbing.DubBlock
import io.github.alalkipgen.autoflow.core.subtitles.TimeText
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.subtitles.*
import io.github.alalkipgen.autoflow.ui.theme.AfSize
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/**
 * The six Dubbing steps on the existing `ProjectTopBar`, whose step bar chooses them (specs/dubbing.md §2; D-105: Style
 * sits between Voice and Export, as in Movie Recap).
 */
enum class DubStep(val text: UiText) {
    VIDEO(UiText.DUB_STEP_VIDEO), TRANSLATE(UiText.DUB_STEP_TRANSLATE), SCRIPT(UiText.DUB_STEP_SCRIPT),
    VOICE(UiText.DUB_STEP_VOICE), STYLE(UiText.DUB_STEP_STYLE), EXPORT(UiText.DUB_STEP_EXPORT);

    companion object {
        /** Where an opened project lands: the first step that is not done yet (Voice once approved; Style and Export are chosen from there). */
        fun first(g: DubGates): DubStep = when {
            g.video != null || g.script == DubBlock.NO_LINES -> VIDEO
            g.script != null -> TRANSLATE
            g.voice != null -> SCRIPT
            else -> VOICE
        }
    }
}

/**
 * Dubbing (D-1b). Video and Translate are the Subtitles source chooser and editor (D-88); Dub script shows every line
 * with its spoken line and the sticky Approve (then Next: Voice); Voice (D-2b) makes and checks each line's take; Style
 * (D-105) chooses how the video looks (the Burmese subtitles in the picture for now); Export (D-3b) mixes and saves the
 * dubbed video. Each finished step's sticky bar names the next one. A locked step says why and where to go.
 */
@Composable
fun DubbingScreen(script: DubScriptModel, subtitles: SubtitlesModel, session: ProjectSession, editor: SubtitleEditorModel,
    onBack: () -> Unit, modifier: Modifier = Modifier, vlcDir: String? = null, player: SubtitlePlayer = moviePlayer(vlcDir),
    transcribe: SubtitleTranscribeModel? = null, translate: SubtitleTranslateModel? = null, voice: DubVoiceModel? = null,
    shorten: DubShortenModel? = null, export: DubExportModel? = null) {
    val open by session.state.collectAsState()
    val st by script.state.collectAsState()
    val ed by editor.state.collectAsState()
    val current = open?.project?.subtitles?.current
    LaunchedEffect(open?.dir, current) { script.open() }
    val doc = st.doc?.takeIf { st.dir == open?.dir && st.base == current }
    val gates = script.gates(open, doc)
    val dirty = ed.dir == open?.dir && ed.draft?.let { it.dirty && it.base == current } == true
    var step by remember(open?.dir) { mutableStateOf<DubStep?>(null) }
    val shown = step ?: DubStep.first(gates).takeIf { current == null || doc != null } ?: DubStep.VIDEO
    // Landing on Dub script or Voice stays put (Approve does not move the owner away); Video → Translate still follows the lines.
    LaunchedEffect(open?.dir, doc != null) { if (step == null && doc != null && shown >= DubStep.SCRIPT) step = shown }
    var leaving by remember { mutableStateOf(false) }
    // Group 5 🆕-8: whether the embedded Subtitles screen shows its editor (Undo / Redo then sit in this bar).
    var subtitlesEditor by remember(open?.dir) { mutableStateOf(false) }
    // 🆕-5: Voice's Edit line opens Dub script at that line with its field focused (then cleared).
    var focusLine by remember(open?.dir) { mutableStateOf<String?>(null) }
    // F-6 (D-95): one preview clock for Dub script, Voice and Export; a step change pauses it where it is.
    val playhead = remember(open?.dir) { Playhead() }
    LaunchedEffect(shown) { playhead.playing = false; playhead.seek(playhead.positionMs) }
    // Group 4 H: two panes from an 840 dp window (D3); Dub script's two fields side by side from 1200 dp.
    val window = dubWindowWidthDp()
    val wide = window >= DUB_WIDE_DP
    // 🆕-6: the desktop keys (↑ / ↓ / Enter) and the line chosen in Dub script and Voice.
    val keys = remember(open?.dir) { DubLineKeys() }
    val preview: @Composable (Boolean) -> Unit = { captions ->
        if (export != null && doc != null) DubPreview(export, open, doc, playhead, player, captions)
    }
    // Group 4 A 3: the ambient background (Group 1) behind every step, not the flat Background fill.
    Column(modifier.fillMaxSize().ambientBackground()) {
        // "Dubbing · <project>"; the step bar is the step chooser (panel 6): each labelled step a tab, no chip row under it.
        ProjectTopBar(listOfNotNull(tr(UiText.V3_DUBBING), open?.project?.name?.takeIf { it.isNotBlank() }).joinToString(" · "),
            shown.ordinal + 1, tr(shown.text), { if (dirty) leaving = true else onBack() },
            steps = DubStep.entries.map { tr(it.text) }, onStep = { step = DubStep.entries[it] },
            stepTags = DubStep.entries.map { "dub-step-${it.name}" }, glass = true, trailing = {
                // Group 5 🆕-8: step 2 (the Subtitles editor) has Undo / Redo in this bar.
                val embedded = when (shown) { // the steps that show SubtitlesScreen (not their locks)
                    DubStep.VIDEO -> gates.video != DubBlock.TOO_LONG
                    DubStep.TRANSLATE -> gates.video == null && gates.script != DubBlock.NO_LINES
                    else -> false
                }
                if (embedded && subtitlesEditor) SubtitleUndoRedo(editor, translate.takeIf { shown == DubStep.TRANSLATE })
            })
        val go: (DubStep) -> Unit = { step = it }
        val body = @Composable {
            when (shown) {
                DubStep.VIDEO -> if (gates.video == DubBlock.TOO_LONG) Lock(block(DubBlock.TOO_LONG, gates, script), tooLong = true) else
                    SubtitlesScreen(subtitles, session, editor, vlcDir = vlcDir, player = player, transcribe = transcribe,
                        onEditorShown = { subtitlesEditor = it })
                DubStep.TRANSLATE -> gates.video?.let { Lock(block(it, gates, script), DubStep.VIDEO, go) }
                    ?: if (gates.script == DubBlock.NO_LINES) Lock(block(DubBlock.NO_LINES, gates, script), DubStep.VIDEO, go)
                    else SubtitlesScreen(subtitles, session, editor, vlcDir = vlcDir, player = player, translate = translate,
                        onEditorShown = { subtitlesEditor = it })
                DubStep.SCRIPT -> when {
                    dirty -> Lock(tr(UiText.DUB_LOCK_UNSAVED), DubStep.TRANSLATE, go)
                    gates.script != null -> Lock(block(gates.script, gates, script), target(gates.script), go)
                    doc != null -> Script(script, open, doc, gates.approved, st.notice, st.ok, playhead, export, focusLine,
                        { focusLine = null }, wide, window >= DUB_COLUMNS_DP, voice, keys, { focusLine = it }, { step = DubStep.VOICE }) { preview(true) }
                }
                DubStep.VOICE -> when {
                    dirty -> Lock(tr(UiText.DUB_LOCK_UNSAVED), DubStep.TRANSLATE, go)
                    gates.voice != null -> Lock(block(gates.voice, gates, script), target(gates.voice), go)
                    voice != null && doc != null -> VoiceStep(voice, open, doc, { step = DubStep.STYLE }, shorten, script,
                        { step = DubStep.SCRIPT }, playhead, export, { focusLine = it; step = DubStep.SCRIPT }, wide, keys) { preview(true) }
                    else -> Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(screenPadding)) {
                        PlannedTile(tr(shown.text), null, Modifier.testTag("dub-planned-VOICE"))
                    }
                }
                DubStep.STYLE -> when {
                    dirty -> Lock(tr(UiText.DUB_LOCK_UNSAVED), DubStep.TRANSLATE, go)
                    gates.voice != null -> Lock(block(gates.voice, gates, script), target(gates.voice), go)
                    export != null && doc != null -> StyleStep(export, open, go, wide) { preview(open?.project?.dubbing?.burnSubtitles == true) }
                    else -> Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(screenPadding)) {
                        PlannedTile(tr(shown.text), null, Modifier.testTag("dub-planned-${shown.name}"))
                    }
                }
                DubStep.EXPORT -> when {
                    dirty -> Lock(tr(UiText.DUB_LOCK_UNSAVED), DubStep.TRANSLATE, go)
                    gates.voice != null -> Lock(block(gates.voice, gates, script), target(gates.voice), go)
                    export != null && doc != null -> ExportStep(export, open, doc, go, wide) { preview(open?.project?.dubbing?.burnSubtitles == true) }
                    else -> Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(screenPadding)) {
                        PlannedTile(tr(shown.text), null, Modifier.testTag("dub-planned-${shown.name}"))
                    }
                }
            }
        }
        // 🆕-6 (desktop only): Space / ↑ / ↓ / Enter on Dub script, Voice, Style and Export; Video and Translate keep the Subtitles editor's keys.
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            if (wide && shown >= DubStep.SCRIPT) ProjectShortcutHost(onKey = keys::handle) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { body() }
            } else body()
        }
    }
    if (leaving) LeaveDialog(
        save = { leaving = false; if (editor.save()) onBack() },
        discard = { leaving = false; editor.discard(); onBack() },
        stay = { leaving = false },
    )
}

private fun target(b: DubBlock) = when (b) {
    DubBlock.NO_VIDEO, DubBlock.TOO_LONG, DubBlock.NO_LINES -> DubStep.VIDEO
    DubBlock.NOT_TRANSLATED -> DubStep.TRANSLATE
    DubBlock.NOT_APPROVED -> DubStep.SCRIPT
}

@Composable
private fun block(b: DubBlock, g: DubGates, model: DubScriptModel): String = when (b) {
    DubBlock.NO_VIDEO -> tr(UiText.DUB_LOCK_NO_VIDEO)
    DubBlock.TOO_LONG -> tr(UiText.DUB_LIMIT, "minutes" to model.maxMinutes)
    DubBlock.NO_LINES -> tr(UiText.DUB_LOCK_NO_LINES)
    DubBlock.NOT_TRANSLATED -> tr(UiText.DUB_NOT_TRANSLATED, "n" to g.notTranslated)
    DubBlock.NOT_APPROVED -> tr(UiText.DUB_LOCK_NOT_APPROVED)
}

/** A step's icon (plan §3.3): the Lock buttons and the Export checklist's Go-to buttons. */
internal val DubStep.icon: ImageVector get() = when (this) {
    DubStep.VIDEO -> AfIcons.Regular.FilmStrip
    DubStep.TRANSLATE -> AfIcons.Regular.Translate
    DubStep.SCRIPT -> AfIcons.Regular.NotePencil
    DubStep.VOICE -> AfIcons.Regular.Dub
    DubStep.STYLE -> AfIcons.Regular.Palette
    DubStep.EXPORT -> AfIcons.Regular.Export
}

/**
 * A locked step (Group 4 B): a glass empty state centred in the step area (at most 420 dp wide) — a 56 dp Amber soft
 * tile (`lock-simple`; `clock` for a video over the limit, [tooLong]), why (one string, centred), then the primary
 * button to the step that unlocks it with that step's icon and a trailing arrow. It scrolls when taller than the area.
 */
@Composable
private fun Lock(why: String, to: DubStep? = null, go: (DubStep) -> Unit = {}, tooLong: Boolean = false) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()).padding(screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f)) // the card sits a third of the way down, as in the sample
            GlassCard(Modifier.widthIn(max = 420.dp).testTag("dub-lock"), padding = PaddingValues(horizontal = 20.dp, vertical = 24.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SoftTile(if (tooLong) AfIcons.Duotone.Clock else AfIcons.Duotone.Lock, AutoFlowColors.Amber, size = 56.dp)
                    AfText(My.breakSyllables(why), size = 15.sp, textAlign = TextAlign.Center)
                    to?.let { GlassButton(My.breakSyllables(tr(UiText.DUB_GO_TO, "step" to tr(it.text))), { go(it) }, Modifier.testTag("dub-go-${it.name}"),
                        kind = ButtonKind.Primary, icon = it.icon, trailingIcon = AfIcons.Regular.ArrowRight) }
                }
            }
            Spacer(Modifier.weight(2f))
        }
    }
}

@Composable
private fun Script(model: DubScriptModel, open: io.github.alalkipgen.autoflow.ui.projects.OpenProject?,
    doc: io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc, approved: Boolean, notice: UiNotice?, ok: Boolean,
    playhead: Playhead? = null, export: DubExportModel? = null, focus: String? = null, focused: () -> Unit = {},
    wide: Boolean = false, columns: Boolean = false, voice: DubVoiceModel? = null, keys: DubLineKeys = remember(open?.dir) { DubLineKeys() },
    edit: (String) -> Unit = {}, next: () -> Unit = {}, preview: @Composable () -> Unit = {}) {
    val rows = remember(open?.project?.subtitles?.video, doc) { model.rows(open, doc) }
    val list = rememberLazyListState()
    // 🆕-5: scroll the line asked for into view (item 0 is the preview on a phone); its row then focuses the field.
    LaunchedEffect(focus) { rows.indexOfFirst { it.id == focus }.takeIf { it >= 0 }?.let { list.scrollToItem(if (wide) it else it + 1) } }
    // Desktop (Group 4 H): the selected line's facts on the right (the line asked for, a tapped head, else the first line).
    var selected by keys::selected
    LaunchedEffect(focus) { focus?.let { selected = it } }
    // 🆕-2: the line under the preview's playhead (the chip's line) gets the playing outline; its head seeks there.
    val ticks = remember(open?.dir, open?.project, doc) { export?.previewTicks(open, doc) }
    val position = playhead?.positionMs
    val playingN = position?.let { pos -> ticks?.let { DubPreviewClock.line(it, pos) } ?: rows.lastOrNull { pos >= it.startMs && pos < it.endMs }?.n }
    Column(if (wide) Modifier.fillMaxSize() else Modifier.widthIn(max = 900.dp).fillMaxSize()) {
        // Group 4 E: the notice as a callout — green check-circle when it went well; amber warning when it failed or asks
        // for more (Shorten's lines need a new approval and new takes). Same text, same tag. D-105: "Dub script approved."
        // is said once, by the sticky bar's caption, not again up here.
        notice?.takeUnless { approved && it.key == UiText.DUB_APPROVED }?.let {
            val good = ok && it.key != UiText.DUB_SHORTEN_USED
            GlassCallout(if (good) AfIcons.Fill.CheckCircle else AfIcons.Fill.Warn, My.breakSyllables(it.text(LocalUiLanguage.current)),
                if (good) AutoFlowColors.Green else AutoFlowColors.Amber, Modifier.padding(start = screenPadding, end = screenPadding, top = 12.dp),
                textModifier = Modifier.testTag("dub-notice"))
        }
        val chosen = if (!wide) null else rows.firstOrNull { it.id == selected } ?: rows.firstOrNull()
        // 🆕-6: ↑ / ↓ move through these lines (the list has no preview item on desktop); Enter focuses the chosen line's field.
        if (wide) {
            BindLineKeys(keys, rows.map { it.id to it.startMs }, chosen?.id, playhead?.let { p -> { ms: Long -> p.seek(ms) } }, edit)
            FollowLineKeys(keys, list, rows.indexOfFirst { it.id == chosen?.id })
        }
        val lines = @Composable { modifier: Modifier -> LazyColumn(modifier.testTag("dub-rows"), state = list, contentPadding = PaddingValues(screenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!wide) item(key = "preview") { preview() } // F-6: scrolls with the lines (desktop: pinned on the right)
            items(rows, key = { it.id }) { r ->
                ScriptRow(r, { model.setSpoken(r.id, it) }, { model.useSubtitle(r.id) }, playing = r.n == playingN,
                    seek = playhead?.let { p -> { selected = r.id; p.seek(r.startMs) } } ?: if (wide) ({ selected = r.id }) else null,
                    focus = r.id == focus, focused = focused, columns = columns, selected = r.id == chosen?.id)
            }
        } }
        if (wide) DubPanes({ lines(Modifier.fillMaxSize()) }, {
            preview()
            chosen?.let { r ->
                val v = remember(open?.project, doc, voice, r.id) { voice?.rows(open, doc)?.firstOrNull { it.id == r.id } }
                SelectedLineCard(r, doc.cues.firstOrNull { it.id == r.id }?.text.orEmpty(), v)
            }
        }, Modifier.weight(1f))
        else lines(Modifier.fillMaxWidth().weight(1f))
        // D-105: once approved, the button is the way on (Next: Voice) instead of a disabled Approve.
        StickyActionBar(if (approved) StudioAction(My.breakSyllables(tr(UiText.NEXT_STEP, "step" to tr(UiText.DUB_STEP_VOICE))), next,
                tag = "dub-next-VOICE", trailingIcon = AfIcons.Regular.ArrowRight)
            else StudioAction(My.breakSyllables(tr(UiText.DUB_APPROVE)), { model.approve() }, tag = "dub-approve", icon = AfIcons.Regular.Check),
            caption = My.breakSyllables(tr(if (approved) UiText.DUB_APPROVED else UiText.DUB_NOT_APPROVED)), glass = true,
            captionIcon = if (approved) AfIcons.Regular.CheckCircle else AfIcons.Regular.Info,
            captionColour = if (approved) AutoFlowColors.Green else null, keys = if (wide) ({ DubShortcutsHint() }) else null)
    }
}

/**
 * One line as a glass cue card (Group 4 E, samples 01 / 02): "#n" bold + "start → end" mono (the head; 🆕-2 a tap seeks the
 * preview there, without playing), the room as a `timer` fact row, then the small muted labels over the subtitle (a read-only
 * inner box) and the spoken line (a glass field, no placeholder: the label names it). [playing] = the Cyan outline.
 */
@Composable
private fun ScriptRow(r: DubScriptRow, save: (String) -> Unit, useSubtitle: () -> Unit, playing: Boolean = false, seek: (() -> Unit)? = null,
    focus: Boolean = false, focused: () -> Unit = {}, columns: Boolean = false, selected: Boolean = false) {
    var text by remember(r.id, r.spoken) { mutableStateOf(r.spoken) }
    val field = remember { FocusRequester() }
    LaunchedEffect(focus) { if (focus) { runCatching { field.requestFocus() }; focused() } }
    val compact = LocalCompact.current
    // The head is a 48 dp target from the card's top edge (no top padding then), so it looks like the sample's 12 dp inset.
    GlassCard(Modifier.testTag("dub-row-${r.id}"), padding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp,
        top = if (seek == null) 12.dp else 0.dp), gap = 8.dp,
        selected = selected && !playing, outline = if (playing) io.github.alalkipgen.autoflow.ui.theme.GlassColors.PlayingOutline else null) {
        // The head keeps its texts as children (tests and screen readers read "#n" and the times); the row itself is the button.
        val head = Modifier.testTag("dub-row-head-${r.id}").fillMaxWidth().then(if (seek == null) Modifier else Modifier
            .heightIn(min = AfSize.Touch).pointerInput(seek) { detectTapGestures { seek() } }
            .semantics { role = Role.Button; onClick { seek(); true } })
        // Head + room with no gap between them when the head is the 48 dp target: it already leaves the sample's space.
        Column(verticalArrangement = Arrangement.spacedBy(if (seek == null) 8.dp else 0.dp)) {
            Box(head, contentAlignment = Alignment.CenterStart) {
                WrapRow {
                    AfText("#${r.n}", size = 14.sp, bold = true)
                    AfText("${TimeText.show(r.startMs)} → ${TimeText.show(r.endMs)}", muted = true, mono = true, size = 13.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AfIcon(AfIcons.Regular.Timer, tint = AutoFlowColors.Muted, size = 15.dp)
                AfText(My.breakSyllables(tr(UiText.DUB_SLOT, "s" to (r.slotMs / 100) / 10.0)), Modifier.testTag("dub-slot-${r.id}"), muted = true, size = 12.sp)
            }
        }
        val subtitle = @Composable {
            AfText(My.breakSyllables(tr(UiText.DUB_SUBTITLE)), muted = true, size = 12.sp)
            val box = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            Box(Modifier.fillMaxWidth().then(if (columns) Modifier.heightIn(min = 68.dp) else Modifier).clip(box).background(Color.White.copy(alpha = .04f))
                .border(1.dp, Color.White.copy(alpha = .08f), box).padding(horizontal = 14.dp, vertical = 10.dp)) {
                AfText(My.breakSyllables(r.textMy), Modifier.testTag("dub-subtitle-${r.id}"), size = 15.sp)
            }
        }
        val spoken = @Composable {
            AfText(My.breakSyllables(tr(UiText.DUB_SPOKEN)), muted = true, size = 12.sp)
            AfTextField(text, { text = it }, tr(UiText.DUB_SPOKEN), Modifier.fillMaxWidth().focusRequester(field).testTag("dub-spoken-${r.id}"), lines = 2, mono = false,
                glass = true, showPlaceholder = false)
        }
        // Desktop ≥ 1200 dp (Group 4 H): the subtitle and the spoken line side by side.
        if (columns) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { subtitle() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { spoken() }
        } else {
            subtitle()
            spoken()
        }
        val edited = text.trim() != r.spoken
        if (edited || r.own) Row(Modifier.fillMaxWidth(), horizontalArrangement = if (compact) Arrangement.Start else Arrangement.End) {
            WrapRow {
                if (edited) GlassButton(tr(UiText.SAVE), { save(text) }, Modifier.testTag("dub-save-${r.id}"), kind = ButtonKind.Primary,
                    icon = AfIcons.Regular.Check, small = true)
                if (r.own) GlassButton(My.breakSyllables(tr(UiText.DUB_USE_SUBTITLE)), useSubtitle, Modifier.testTag("dub-use-subtitle-${r.id}"),
                    icon = AfIcons.Regular.Subtitles, small = true)
            }
        }
    }
}
