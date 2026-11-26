package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.theme.*

/**
 * The Subtitles tool (specs/quick-tools.md §5): the video's facts and its source (S-2b), then the editor (S-3c), under a
 * pinned top bar when it has its own Back (Dubbing's steps bring their own bar; [onEditorShown] tells that bar when the
 * editor is shown, so it can carry Undo / Redo, 🆕-8).
 * Back with unsaved changes asks first: Save, Discard, or Keep editing.
 */
@Composable
fun SubtitlesScreen(model: SubtitlesModel, session: ProjectSession, editor: SubtitleEditorModel, modifier: Modifier = Modifier,
    vlcDir: String? = null, player: SubtitlePlayer = moviePlayer(vlcDir), onBack: (() -> Unit)? = null,
    exporter: SubtitleExportModel? = null, burner: SubtitleBurnModel? = null, transcribe: SubtitleTranscribeModel? = null,
    translate: SubtitleTranslateModel? = null, onEditorShown: ((Boolean) -> Unit)? = null) {
    val all by model.state.collectAsState()
    val open by session.state.collectAsState()
    val ed by editor.state.collectAsState()
    val state = all.takeIf { it.dir != null && it.dir == open?.dir } ?: SubtitlesUiState()
    val tool = open?.project?.subtitles
    val video = tool?.video
    var choosing by remember(open?.dir, tool?.current) { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(open?.dir, tool?.current) { model.refresh(); editor.open() }
    val draft = ed.draft?.takeIf { ed.dir == open?.dir && it.base == tool?.current }
    val back = onBack?.let { b -> { if (draft?.dirty == true) leaving = true else b() } }
    val editing = video != null && draft != null && !choosing
    // 🆕-8: a host bar (Dubbing) shows Undo / Redo only while the editor is shown, not on the source page.
    SideEffect { onEditorShown?.invoke(editing) }
    val header: @Composable ColumnScope.() -> Unit = {
        state.busy?.let { AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("subtitles-busy"), muted = true) }
        // Group 5 A 8: the notices as callouts — green `check-circle` saved, red `x-circle` cannot read, amber `warning`.
        state.notice?.let {
            if (state.ok) GlassCallout(AfIcons.Fill.CheckCircle, My.breakSyllables(it.text(LocalUiLanguage.current)), AutoFlowColors.Green,
                textModifier = Modifier.testTag("subtitles-notice"))
            else GlassCallout(AfIcons.Fill.XCircle, My.breakSyllables(it.text(LocalUiLanguage.current)), AutoFlowColors.Failed,
                textModifier = Modifier.testTag("subtitles-notice"))
        }
        state.warning?.let { GlassCallout(AfIcons.Fill.Warn, My.breakSyllables(it.text(LocalUiLanguage.current)), AutoFlowColors.Amber,
            textModifier = Modifier.testTag("subtitles-warning")) }
        if (transcribe != null && open != null) TranscribePanel(transcribe, open!!.dir)
        if (translate != null && open != null) TranslatePanel(translate, open!!.dir)
    }
    // Group 5 A 2: the ambient background (Group 1), not the flat Background fill.
    Column(modifier.fillMaxSize().ambientBackground()) {
        // DESIGN.md §9.7 panel 5: a pinned top bar — the tool, the project, and a warning dot while edits are unsaved.
        if (back != null) {
            val name = open?.project?.name.orEmpty()
            val dirty = draft?.dirty == true
            SubtitlesTopBar(tr(UiText.V3_SUBTITLES), My.breakSyllables(if (dirty) "${tr(UiText.SUB_NOT_SAVED)} · $name" else name), dirty, back) {
                // 🆕-8: Undo / Redo in the bar on the editor (not on the source page).
                if (editing) SubtitleUndoRedo(editor, translate)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (editing) {
                Box(Modifier.widthIn(max = 1400.dp).fillMaxSize()) { // two panes from 840 dp
                    SubtitleEditor(editor, joinPath(open!!.dir, video.file), video, player, header = {
                        header()
                        // Group 5 A 4: the `subtitles` icon + the doc facts 14 sp bold, the video facts 12 sp muted under the text.
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AfIcon(AfIcons.Regular.Subtitles, tint = AutoFlowColors.Muted, size = 18.dp)
                                AfText(My.breakSyllables(tr(UiText.SUB_DOC_FACTS, "n" to draft.doc.cues.size, "from" to from(draft.doc))),
                                    Modifier.testTag("subtitles-doc-facts"), type = AfType.Button, weight = FontWeight.Bold)
                            }
                            VideoFacts(video, Modifier.padding(start = 26.dp), size = 12.sp)
                        }
                    }, onChangeSource = { choosing = true }, exporter = exporter, burner = burner, translate = translate)
                }
            } else Column(Modifier.widthIn(max = 900.dp).fillMaxSize().paneScroll(rememberScrollState()).padding(screenPadding)
                .testTag("subtitles-scroll"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                header()
                if (video != null) {
                    VideoCard(video)
                    SourceCard(state, model, open!!.dir, video.hasAudio, transcribe, onCancel = if (draft != null) ({ choosing = false }) else null)
                } else if (state.busy == null) AfText(My.breakSyllables(tr(UiText.SUBTITLES_NO_VIDEO)), Modifier.testTag("subtitles-no-video"), muted = true)
            }
        }
    }
    if (leaving && onBack != null) LeaveDialog(
        save = { leaving = false; if (editor.save()) onBack() },
        discard = { leaving = false; editor.discard(); onBack() },
        stay = { leaving = false },
    )
}

/**
 * The Subtitles top bar (Group 5 A 1, the Group 2 `ProjectTopBar` look): the glass panel (White .08 → .03, a White .08
 * line under it), the 40 dp glass back button, [title] 17 sp bold on one line, [subtitle] 12 sp muted — while [dirty] a
 * 6 dp amber dot and the text in WarningText — then [actions] (Undo / Redo, 🆕-8) at the right.
 */
@Composable
internal fun SubtitlesTopBar(title: String, subtitle: String, dirty: Boolean, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().drawBehind {
            drawRect(Brush.verticalGradient(listOf(GlassColors.TopBarTop, GlassColors.TopBarBottom)))
            drawRect(GlassColors.TopBarLine, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
        }.padding(horizontal = GlassShapes.TopBarPadX, vertical = GlassShapes.TopBarPadY).testTag("subtitles-top-bar"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassIconButton(AfIcons.Regular.Back, tr(UiText.BACK), onBack, Modifier.testTag("subtitles-back"), size = GlassShapes.IconButtonHeader)
        Column(Modifier.weight(1f)) {
            AfText(title, Modifier.semantics { contentDescription = title }, type = AfType.TopBarTitle, weight = FontWeight.Bold, maxLines = 1, ellipsis = true)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (dirty) Box(Modifier.size(6.dp).clip(CircleShape).background(AutoFlowColors.Amber))
                AfText(subtitle, color = if (dirty) AutoFlowColors.WarningText else AutoFlowColors.Muted, type = AfType.Caption)
            }
        }
        actions()
    }
}

/**
 * 🆕-8: Undo / Redo as two 40 dp glass icon buttons (Undo = Redo's arrow mirrored, as before) for the Subtitles top bar
 * and the Dubbing top bar on step 2 — the same rules as in the list header before: on only while the editor is idle (no
 * restore offer, no translation running) and the draft can undo / redo; tags `subtitles-undo` / `subtitles-redo`.
 */
@Composable
internal fun SubtitleUndoRedo(editor: SubtitleEditorModel, translate: SubtitleTranslateModel?) {
    val s by editor.state.collectAsState()
    val draft = s.draft ?: return
    val translating = translate?.jobOf(s.dir, translate.jobs.collectAsState().value)
    val idle = s.offer == null && translating == null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(AfIcons.Regular.Redo, tr(UiText.SUB_UNDO), editor::undo, Modifier.graphicsLayer(scaleX = -1f).testTag("subtitles-undo"),
            size = GlassShapes.IconButtonHeader, enabled = idle && draft.canUndo)
        GlassIconButton(AfIcons.Regular.Redo, tr(UiText.SUB_REDO), editor::redo, Modifier.testTag("subtitles-redo"),
            size = GlassShapes.IconButtonHeader, enabled = idle && draft.canRedo)
    }
}

/**
 * Unsaved subtitle changes on leaving (Subtitles and Dubbing; Group 4 A 4): a glass dialog — Amber `floppy-disk` tile,
 * SUB_LEAVE, then Save (primary, check), Discard (danger, trash) and Keep editing (secondary), stacked full width.
 */
@Composable
internal fun LeaveDialog(save: () -> Unit, discard: () -> Unit, stay: () -> Unit) {
    GlassDialog(stay, AfIcons.Fill.FloppyDisk, AutoFlowColors.Amber, My.breakSyllables(tr(UiText.SUB_LEAVE)), Modifier.testTag("subtitles-leave")) {
        GlassDialogButtons(GlassDialogAction(My.breakSyllables(tr(UiText.SUB_KEEP_EDITING)), stay, tag = "subtitles-leave-stay"),
            GlassDialogAction(My.breakSyllables(tr(UiText.SAVE)), save, ButtonKind.Primary, AfIcons.Regular.Check, tag = "subtitles-leave-save"),
            GlassDialogAction(My.breakSyllables(tr(UiText.DISCARD)), discard, ButtonKind.Danger, AfIcons.Regular.Trash, tag = "subtitles-leave-discard"))
    }
}

@Composable
private fun from(doc: SubtitleDoc) = when {
    doc.source.startsWith("track:") -> tr(UiText.SUB_FROM_TRACK, "index" to doc.source.removePrefix("track:"))
    doc.source == SubtitleDoc.SOURCE_SRT -> tr(UiText.SUB_FROM_SRT)
    else -> tr(UiText.SUB_FROM_OTHER)
}

@Composable
private fun VideoCard(video: SourceVideo) {
    AfCard(tr(UiText.SUBTITLES_VIDEO), Modifier.testTag("subtitles-video"), icon = AfIcons.Regular.Film) {
        AfText(video.file.substringAfterLast('/'), mono = true)
        VideoFacts(video)
    }
}

@Composable
private fun VideoFacts(video: SourceVideo, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.TextUnit = 13.sp) {
    val fps = video.fps?.let { ((it * 100).toLong() / 100.0).toString().removeSuffix(".0") } ?: "?"
    AfText(My.breakSyllables(tr(UiText.SUBTITLES_VIDEO_FACTS, "duration" to RecapModel.clock(video.durationMs),
        "width" to video.width, "height" to video.height, "fps" to fps,
        "sound" to tr(if (video.hasAudio) UiText.SUBTITLES_SOUND_YES else UiText.SUBTITLES_SOUND_NO))),
        modifier.testTag("subtitles-video-facts"), muted = true, size = size)
}

/** Where the lines come from: a text track, an `.srt` file, or the speech (S-5b, Gemini). */
@Composable
private fun SourceCard(state: SubtitlesUiState, model: SubtitlesModel, dir: String, hasSound: Boolean, transcribe: SubtitleTranscribeModel?,
    onCancel: (() -> Unit)?) {
    val idle = state.busy == null
    AfCard(tr(UiText.SUB_START_HOW), Modifier.testTag("subtitles-source"), icon = AfIcons.Regular.Subtitles) {
        AfText(My.breakSyllables(tr(UiText.SUB_TRACKS)), bold = true)
        val tracks = state.tracks
        when {
            tracks == null -> AfText(My.breakSyllables(tr(if (state.tracksRead) UiText.SUB_TRACKS_UNREADABLE else UiText.SUB_TRACKS_READING)),
                Modifier.testTag("subtitles-tracks-unknown"), muted = true)
            tracks.isEmpty() -> AfText(My.breakSyllables(tr(UiText.SUB_TRACKS_NONE)), Modifier.testTag("subtitles-tracks-none"), muted = true)
            else -> {
                val text = tracks.filter { it.isText }
                var pick by remember(tracks) { mutableStateOf(SubtitlesModel.defaultTrack(tracks)) }
                text.forEach { t ->
                    val title = tr(UiText.SUBTITLE_TRACK, "index" to t.index) + " · " + (t.language ?: "?")
                    OptionCard(title, listOfNotNull(t.codecName, t.title).joinToString(" · "), pick == t.index, { pick = t.index },
                        Modifier.testTag("subtitles-track-${t.index}"), recommended = t.index == SubtitlesModel.defaultTrack(tracks), enabled = idle)
                }
                if (text.isEmpty()) AfText(My.breakSyllables(tr(UiText.SUB_TRACKS_NONE)), Modifier.testTag("subtitles-tracks-none"), muted = true)
                val pictures = tracks.filter { !it.isText }
                if (pictures.isNotEmpty()) AfText(My.breakSyllables(tr(UiText.SUB_PICTURE_TRACK, "n" to pictures.size,
                    "codec" to pictures.map { it.codecName }.distinct().joinToString(", "))), Modifier.testTag("subtitles-picture-tracks"), muted = true)
                if (text.isNotEmpty()) AfButton(My.breakSyllables(tr(UiText.SUB_USE_TRACK)),
                    { text.firstOrNull { it.index == pick }?.let { model.useTrack(it) } },
                    Modifier.fillMaxWidth().testTag("subtitles-use-track"), kind = ButtonKind.Primary, enabled = idle && pick != null)
            }
        }
        AfButton(My.breakSyllables(tr(UiText.IMPORT_SUBTITLES)), { model.importSrt() }, Modifier.fillMaxWidth().testTag("subtitles-import-srt"),
            kind = if (tracks?.any { it.isText } == true) ButtonKind.Secondary else ButtonKind.Primary, enabled = idle, icon = AfIcons.Regular.Folder)
        if (transcribe != null) TranscribeButton(transcribe, dir, hasSound, idle)
        onCancel?.let { AfButton(tr(UiText.CANCEL), it, Modifier.testTag("subtitles-source-cancel")) }
    }
    if (transcribe == null) PlannedTile(tr(UiText.SUB_TRANSCRIBE), tr(if (hasSound) UiText.SUB_TRANSCRIBE_NEXT else UiText.MOVIE_NO_SOUND_TO_TRANSCRIBE), Modifier.testTag("subtitles-transcribe-planned"))
}
