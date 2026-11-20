package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.recap.Srt
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** The Subtitles tool (specs/quick-tools.md §5): the video's facts, then its source (S-2b); editing follows (S-3). */
@Composable
fun SubtitlesScreen(model: SubtitlesModel, session: ProjectSession, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val all by model.state.collectAsState()
    val open by session.state.collectAsState()
    val state = all.takeIf { it.dir != null && it.dir == open?.dir } ?: SubtitlesUiState()
    val tool = open?.project?.subtitles
    val video = tool?.video
    var choosing by remember(open?.dir, tool?.current) { mutableStateOf(false) }
    LaunchedEffect(open?.dir, tool?.current) { model.refresh() }
    Box(modifier.fillMaxSize().background(AutoFlowColors.Background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 900.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding)
            .testTag("subtitles-scroll"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (onBack != null) WrapRow {
                AfButton(tr(UiText.PROJECTS_BACK), onBack, Modifier.heightIn(min = 48.dp).testTag("subtitles-back"))
                open?.project?.name?.let { AfText(it, bold = true) }
            }
            state.busy?.let { AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("subtitles-busy"), muted = true) }
            state.notice?.let {
                AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("subtitles-notice"),
                    color = if (state.ok) AutoFlowColors.Green else AutoFlowColors.Failed)
            }
            state.warning?.let { AfText(My.breakSyllables(it.text(LocalUiLanguage.current)), Modifier.testTag("subtitles-warning"), color = AutoFlowColors.Amber) }
            if (video != null) {
                VideoCard(video)
                val doc = state.doc
                if (tool?.current != null && doc != null && !choosing) DocCard(doc) { choosing = true }
                else SourceCard(state, model, onCancel = if (tool?.current != null && doc != null) ({ choosing = false }) else null)
            } else if (state.busy == null) AfText(My.breakSyllables(tr(UiText.SUBTITLES_NO_VIDEO)), Modifier.testTag("subtitles-no-video"), muted = true)
        }
    }
}

@Composable
private fun VideoCard(video: SourceVideo) {
    AfCard(tr(UiText.SUBTITLES_VIDEO), Modifier.testTag("subtitles-video")) {
        AfText(video.file.substringAfterLast('/'), mono = true)
        val fps = video.fps?.let { ((it * 100).toLong() / 100.0).toString().removeSuffix(".0") } ?: "?"
        AfText(My.breakSyllables(tr(UiText.SUBTITLES_VIDEO_FACTS, "duration" to RecapModel.clock(video.durationMs),
            "width" to video.width, "height" to video.height, "fps" to fps,
            "sound" to tr(if (video.hasAudio) UiText.SUBTITLES_SOUND_YES else UiText.SUBTITLES_SOUND_NO))),
            Modifier.testTag("subtitles-video-facts"), muted = true)
    }
}

/** Where the lines come from: a text track, an `.srt` file, or (Planned, S-5) the speech. */
@Composable
private fun SourceCard(state: SubtitlesUiState, model: SubtitlesModel, onCancel: (() -> Unit)?) {
    val idle = state.busy == null
    AfCard(tr(UiText.SUBTITLES_SOURCE), Modifier.testTag("subtitles-source")) {
        AfText(My.breakSyllables(tr(UiText.SUB_TRACKS)), bold = true)
        val tracks = state.tracks
        when {
            tracks == null -> AfText(My.breakSyllables(tr(if (state.tracksRead) UiText.SUB_TRACKS_UNREADABLE else UiText.SUB_TRACKS_READING)),
                Modifier.testTag("subtitles-tracks-unknown"), muted = true)
            tracks.isEmpty() -> AfText(My.breakSyllables(tr(UiText.SUB_TRACKS_NONE)), Modifier.testTag("subtitles-tracks-none"), muted = true)
            else -> {
                var pick by remember(tracks) { mutableStateOf(SubtitlesModel.defaultTrack(tracks)) }
                tracks.forEach { t ->
                    val title = tr(UiText.SUBTITLE_TRACK, "index" to t.index) + " · " + (t.language ?: "?")
                    val about = if (t.isText) listOfNotNull(t.codecName, t.title).joinToString(" · ") else tr(UiText.SUB_PICTURE_TRACK)
                    OptionCard(title, My.breakSyllables(about), t.isText && pick == t.index, { pick = t.index },
                        Modifier.testTag("subtitles-track-${t.index}"), enabled = idle && t.isText)
                }
                if (tracks.any { it.isText }) AfButton(My.breakSyllables(tr(UiText.SUB_USE_TRACK)),
                    { tracks.firstOrNull { it.index == pick }?.let { model.useTrack(it) } },
                    Modifier.fillMaxWidth().testTag("subtitles-use-track"), kind = ButtonKind.Primary, enabled = idle && pick != null)
            }
        }
        AfButton(My.breakSyllables(tr(UiText.IMPORT_SUBTITLES)), { model.importSrt() }, Modifier.fillMaxWidth().testTag("subtitles-import-srt"),
            kind = if (tracks?.any { it.isText } == true) ButtonKind.Secondary else ButtonKind.Primary, enabled = idle)
        onCancel?.let { AfButton(tr(UiText.CANCEL), it, Modifier.testTag("subtitles-source-cancel")) }
    }
    PlannedTile(tr(UiText.SUB_TRANSCRIBE), tr(UiText.SUB_TRANSCRIBE_NEXT), Modifier.testTag("subtitles-transcribe-planned"))
}

/** The current `st`: how many lines, where from, the first few lines; editing is Planned until S-3. */
@Composable
private fun DocCard(doc: SubtitleDoc, onChange: () -> Unit) {
    val from = when {
        doc.source.startsWith("track:") -> tr(UiText.SUB_FROM_TRACK, "index" to doc.source.removePrefix("track:"))
        doc.source == SubtitleDoc.SOURCE_SRT -> tr(UiText.SUB_FROM_SRT)
        else -> tr(UiText.SUB_FROM_OTHER)
    }
    AfCard(tr(UiText.SUB_DOC), Modifier.testTag("subtitles-doc")) {
        AfText(My.breakSyllables(tr(UiText.SUB_DOC_FACTS, "n" to doc.cues.size, "from" to from)), Modifier.testTag("subtitles-doc-facts"), bold = true)
        doc.cues.take(3).forEach { cue ->
            AfText(Srt.stamp(cue.startMs), mono = true, muted = true)
            AfText(My.breakSyllables(cue.text))
        }
        AfButton(My.breakSyllables(tr(UiText.SUB_CHANGE_SOURCE)), onChange, Modifier.testTag("subtitles-change-source"))
    }
    PlannedTile(tr(UiText.SUB_EDIT), tr(UiText.SUB_EDIT_NEXT), Modifier.testTag("subtitles-edit-planned"))
}
