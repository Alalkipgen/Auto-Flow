package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.unit.DpSize
import io.github.alalkipgen.autoflow.ui.media.MediaThumbnails
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
    translate: SubtitleTranslateModel? = null, onEditorShown: ((Boolean) -> Unit)? = null,
    backHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> }) {
    val all by model.state.collectAsState()
    val open by session.state.collectAsState()
    val ed by editor.state.collectAsState()
    val state = all.takeIf { it.dir != null && it.dir == open?.dir } ?: SubtitlesUiState()
    val tool = open?.project?.subtitles
    val video = tool?.video
    var choosing by remember(open?.dir, tool?.current) { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    // Group 5 F 1 (🆕-5): Export is a full page in place of the editor, under its own top bar (this bar hides meanwhile).
    var exporting by remember(open?.dir) { mutableStateOf(false) }
    LaunchedEffect(open?.dir, tool?.current) { model.refresh(); editor.open() }
    val draft = ed.draft?.takeIf { ed.dir == open?.dir && it.base == tool?.current }
    val back = onBack?.let { b -> { if (draft?.dirty == true) leaving = true else b() } }
    val editing = video != null && draft != null && !choosing
    // 🆕-8: a host bar (Dubbing) shows Undo / Redo only while the editor is shown, not on the source page.
    SideEffect { onEditorShown?.invoke(editing) }
    val header: @Composable ColumnScope.() -> Unit = {
        // Group 5 C 3: extracting / importing = a progress card on top (the choices under it are off while it runs).
        state.busy?.let { SubProgressCard(My.breakSyllables(it.text(LocalUiLanguage.current)), "subtitles-busy") }
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
        if (back != null && !(editing && exporting && exporter != null && draft?.dirty == false)) {
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
                    }, onChangeSource = { choosing = true }, exporter = exporter, burner = burner, translate = translate,
                        exporting = exporting, onExporting = { exporting = it }, project = open?.project?.name.orEmpty(), backHandler = backHandler)
                }
            } else Column(Modifier.widthIn(max = 900.dp).fillMaxSize().paneScroll(rememberScrollState()).padding(screenPadding)
                .testTag("subtitles-scroll"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                header()
                if (video != null) {
                    VideoCard(video, open!!.dir)
                    SourceCard(state, model, open!!.dir, video.hasAudio, transcribe, onCancel = if (draft != null) ({ choosing = false }) else null)
                } else if (state.busy == null) NoVideo()
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
 * 6 dp amber dot and the text in WarningText — then [actions] (Undo / Redo, 🆕-8) at the right. Also the Voice & Audio
 * bar (Group 6 VA-1): no back button without [onBack], no subtitle line without [subtitle] (the task chooser).
 */
@Composable
internal fun SubtitlesTopBar(title: String, subtitle: String?, dirty: Boolean, onBack: (() -> Unit)?, tag: String = "subtitles-top-bar",
    backTag: String = "subtitles-back", actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().drawBehind {
            drawRect(Brush.verticalGradient(listOf(GlassColors.TopBarTop, GlassColors.TopBarBottom)))
            drawRect(GlassColors.TopBarLine, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
        }.padding(horizontal = GlassShapes.TopBarPadX, vertical = GlassShapes.TopBarPadY).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        onBack?.let { GlassIconButton(AfIcons.Regular.Back, tr(UiText.BACK), it, Modifier.testTag(backTag), size = GlassShapes.IconButtonHeader) }
        Column(Modifier.weight(1f)) {
            AfText(title, Modifier.semantics { contentDescription = title }, type = AfType.TopBarTitle, weight = FontWeight.Bold, maxLines = 1, ellipsis = true)
            if (subtitle != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

/**
 * The video (Group 5 C 1): a glass card with the cyan `film-strip` tile + SUBTITLES_VIDEO, then a 120 × 68 frame of the video
 * (the app's thumbnail cache, as Home / Projects; the film placeholder until it is made) beside the file name (mono 14 sp;
 * a long name breaks after "." / "-" / "_", never inside a word) and the facts 12 sp muted — under the frame when that
 * column would be too narrow (large fonts).
 */
@Composable
private fun VideoCard(video: SourceVideo, dir: String) {
    TileCard(tr(UiText.SUBTITLES_VIDEO), CardTile(AfIcons.Fill.FilmStrip, AutoFlowColors.Cyan), Modifier.testTag("subtitles-video")) {
        val source = remember(dir, video) { MediaThumbnails.Source(dir, video.file, revision = video.sizeBytes.toString(), durationMs = video.durationMs) }
        ThumbBeside(VideoThumb.width, {
            MediaThumb(source, AfIcons.Duotone.FilmStrip, Modifier.glassThumb(), size = VideoThumb, placeholderColour = AutoFlowColors.Cyan)
        }, { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MonoText(video.file.substringAfterLast('/'), 14.sp, AutoFlowColors.Text, modifier = Modifier.testTag("subtitles-video-file"))
                VideoFacts(video, size = 12.sp)
            }
        }, gap = 12.dp)
    }
}

/** The video frame of [VideoCard] (Group 5 C 1). */
private val VideoThumb = DpSize(120.dp, 68.dp)

/** No readable video (Group 5 C 3): the Group 3 dashed empty state — `video-camera-slash` Duotone + SUBTITLES_NO_VIDEO, centred. */
@Composable
private fun NoVideo() {
    Column(Modifier.fillMaxWidth().glassDashedBox(18.dp).padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AfIcon(AfIcons.Duotone.VideoCameraSlash, tint = AutoFlowColors.Muted, size = 40.dp)
        AfText(My.breakSyllables(tr(UiText.SUBTITLES_NO_VIDEO)), Modifier.testTag("subtitles-no-video"), muted = true,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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

/**
 * Where the lines come from: a text track, an `.srt` file, or the speech (S-5b, Gemini). Group 5 C 2–3: a glass card
 * (the purple `subtitles` tile + SUB_START_HOW), SUB_TRACKS as a small muted label, reading = an `hourglass` line, none /
 * unreadable = an `info` line, the text tracks as glass radio cards (recommended = the cyan pill), the picture tracks as an
 * `image` line, Use the subtitles inside the video (primary, `check`), a divider, then Import (.srt) (`folder-open`, primary
 * when there is no text track) and Transcribe full width, and Cancel (`x`) when choosing again. Busy = all off.
 */
@Composable
private fun SourceCard(state: SubtitlesUiState, model: SubtitlesModel, dir: String, hasSound: Boolean, transcribe: SubtitleTranscribeModel?,
    onCancel: (() -> Unit)?) {
    val idle = state.busy == null
    TileCard(tr(UiText.SUB_START_HOW), CardTile(AfIcons.Fill.Subtitles, AutoFlowColors.Purple), Modifier.testTag("subtitles-source")) {
        AfText(My.breakSyllables(tr(UiText.SUB_TRACKS)), muted = true, type = AfType.Caption)
        val tracks = state.tracks
        when {
            tracks == null -> GlassFact(if (state.tracksRead) AfIcons.Regular.Info else AfIcons.Regular.Hourglass,
                My.breakSyllables(tr(if (state.tracksRead) UiText.SUB_TRACKS_UNREADABLE else UiText.SUB_TRACKS_READING)),
                colour = AutoFlowColors.Muted, textModifier = Modifier.testTag("subtitles-tracks-unknown"))
            tracks.isEmpty() -> GlassFact(AfIcons.Regular.Info, My.breakSyllables(tr(UiText.SUB_TRACKS_NONE)), colour = AutoFlowColors.Muted,
                textModifier = Modifier.testTag("subtitles-tracks-none"))
            else -> {
                val text = tracks.filter { it.isText }
                var pick by remember(tracks) { mutableStateOf(SubtitlesModel.defaultTrack(tracks)) }
                text.forEach { t ->
                    val title = tr(UiText.SUBTITLE_TRACK, "index" to t.index) + " · " + (t.language ?: "?")
                    OptionCard(title, listOfNotNull(t.codecName, t.title).joinToString(" · "), pick == t.index, { pick = t.index },
                        // busy: the tracks dim with the buttons (sample 05 state 4)
                        Modifier.testTag("subtitles-track-${t.index}").alpha(if (idle) 1f else .4f),
                        recommended = t.index == SubtitlesModel.defaultTrack(tracks), enabled = idle, radio = true)
                }
                if (text.isEmpty()) GlassFact(AfIcons.Regular.Info, My.breakSyllables(tr(UiText.SUB_TRACKS_NONE)), colour = AutoFlowColors.Muted,
                    textModifier = Modifier.testTag("subtitles-tracks-none"))
                val pictures = tracks.filter { !it.isText }
                if (pictures.isNotEmpty()) GlassFact(AfIcons.Regular.Image, My.breakSyllables(tr(UiText.SUB_PICTURE_TRACK, "n" to pictures.size,
                    "codec" to pictures.map { it.codecName }.distinct().joinToString(", "))), colour = AutoFlowColors.Muted,
                    textModifier = Modifier.testTag("subtitles-picture-tracks"))
                if (text.isNotEmpty()) GlassButton(My.breakSyllables(tr(UiText.SUB_USE_TRACK)),
                    { text.firstOrNull { it.index == pick }?.let { model.useTrack(it) } },
                    Modifier.fillMaxWidth().testTag("subtitles-use-track"), kind = ButtonKind.Primary, enabled = idle && pick != null,
                    icon = AfIcons.Regular.Check)
            }
        }
        GlassHairline()
        GlassButton(My.breakSyllables(tr(UiText.IMPORT_SUBTITLES)), { model.importSrt() }, Modifier.fillMaxWidth().testTag("subtitles-import-srt"),
            kind = if (tracks?.any { it.isText } == true) ButtonKind.Secondary else ButtonKind.Primary, enabled = idle, icon = AfIcons.Regular.FolderOpen)
        if (transcribe != null) TranscribeButton(transcribe, dir, hasSound, idle)
        onCancel?.let { GlassButton(My.breakSyllables(tr(UiText.CANCEL)), it, Modifier.fillMaxWidth().testTag("subtitles-source-cancel"),
            icon = AfIcons.Regular.Close) }
    }
    if (transcribe == null) PlannedTile(tr(UiText.SUB_TRANSCRIBE), tr(if (hasSound) UiText.SUB_TRANSCRIBE_NEXT else UiText.MOVIE_NO_SOUND_TO_TRANSCRIBE), Modifier.testTag("subtitles-transcribe-planned"))
}
