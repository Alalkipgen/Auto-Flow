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
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** The Subtitles tool (specs/quick-tools.md §5). S-1b shows the copied video's facts; sources and editing follow. */
@Composable
fun SubtitlesScreen(model: SubtitlesModel, session: ProjectSession, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val all by model.state.collectAsState()
    val open by session.state.collectAsState()
    val state = all.takeIf { it.dir != null && it.dir == open?.dir } ?: SubtitlesUiState()
    val video = open?.project?.subtitles?.video
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
            if (video != null) VideoCard(video)
            else if (state.busy == null) AfText(My.breakSyllables(tr(UiText.SUBTITLES_NO_VIDEO)), Modifier.testTag("subtitles-no-video"), muted = true)
            PlannedTile(tr(UiText.SUBTITLES_SOURCE), tr(UiText.SUBTITLES_SOURCE_NEXT), Modifier.testTag("subtitles-source-planned"))
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
