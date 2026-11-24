package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.theme.AfSpace
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.Tone

/**
 * The Subtitles tool (specs/quick-tools.md §5): the video's facts and its source (S-2b), then the editor (S-3c), under a
 * pinned top bar when it has its own Back (Dubbing's steps bring their own bar).
 * Back with unsaved changes asks first: Save, Discard, or Keep editing.
 */
@Composable
fun SubtitlesScreen(model: SubtitlesModel, session: ProjectSession, editor: SubtitleEditorModel, modifier: Modifier = Modifier,
    vlcDir: String? = null, player: SubtitlePlayer = moviePlayer(vlcDir), onBack: (() -> Unit)? = null,
    exporter: SubtitleExportModel? = null, burner: SubtitleBurnModel? = null, transcribe: SubtitleTranscribeModel? = null,
    translate: SubtitleTranslateModel? = null) {
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
    val header: @Composable ColumnScope.() -> Unit = {
        state.busy?.let { AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("subtitles-busy"), muted = true) }
        state.notice?.let {
            AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("subtitles-notice"),
                color = if (state.ok) AutoFlowColors.Green else AutoFlowColors.Failed)
        }
        state.warning?.let { AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("subtitles-warning"), color = AutoFlowColors.Amber) }
        if (transcribe != null && open != null) TranscribePanel(transcribe, open!!.dir)
        if (translate != null && open != null) TranslatePanel(translate, open!!.dir)
    }
    Column(modifier.fillMaxSize().background(AutoFlowColors.Background)) {
        // DESIGN.md §9.7 panel 5: a pinned top bar — the tool, the project, and a warning dot while edits are unsaved.
        if (back != null) {
            val name = open?.project?.name.orEmpty()
            val dirty = draft?.dirty == true
            AfTopBar(tr(UiText.V3_SUBTITLES), Modifier.background(AutoFlowColors.Panel).padding(horizontal = AfSpace.S, vertical = AfSpace.Xs)
                .testTag("subtitles-top-bar"), subtitle = My.breakSyllables(if (dirty) "${tr(UiText.SUB_NOT_SAVED)} · $name" else name),
                status = Tone.Warning.takeIf { dirty }, onBack = back, backModifier = Modifier.testTag("subtitles-back"))
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (video != null && draft != null && !choosing) {
                Box(Modifier.widthIn(max = 1400.dp).fillMaxSize()) { // two panes from 840 dp
                    SubtitleEditor(editor, joinPath(open!!.dir, video.file), video, player, header = {
                        header()
                        AfText(My.breakSyllables(tr(UiText.SUB_DOC_FACTS, "n" to draft.doc.cues.size, "from" to from(draft.doc))),
                            Modifier.testTag("subtitles-doc-facts"), bold = true)
                        VideoFacts(video)
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
private fun VideoFacts(video: SourceVideo) {
    val fps = video.fps?.let { ((it * 100).toLong() / 100.0).toString().removeSuffix(".0") } ?: "?"
    AfText(My.breakSyllables(tr(UiText.SUBTITLES_VIDEO_FACTS, "duration" to RecapModel.clock(video.durationMs),
        "width" to video.width, "height" to video.height, "fps" to fps,
        "sound" to tr(if (video.hasAudio) UiText.SUBTITLES_SOUND_YES else UiText.SUBTITLES_SOUND_NO))),
        Modifier.testTag("subtitles-video-facts"), muted = true)
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
