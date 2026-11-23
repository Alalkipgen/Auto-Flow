package io.github.alalkipgen.autoflow.ui.export

import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.theme.GlassShapes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieRequest
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

internal fun previewWidth(available: Float, viewportHeight: Float, aspect: Float, fullscreen: Boolean = false): Float {
    val heightShare = when {
        fullscreen || aspect > 1f -> 1f
        aspect == 1f -> .75f
        else -> .60f
    }
    return minOf(available, viewportHeight * heightShare * aspect)
}

@Composable
internal fun ExportPreviewCard(file: PreviewFile?, model: ExportModel, showOpen: Boolean, glass: Boolean = false) {
    ExportCard(tr(UiText.PREVIEW_PLAYER), ExportTiles.Preview, glass, Modifier.testTag("export-preview-card")) {
        if (file == null) {
            if (glass) PreviewEmpty() else AfText(tr(UiText.PREVIEW_EMPTY), Modifier.testTag("export-preview-empty"), muted = true)
        } else key(file) {
            var inspected by remember { mutableStateOf(false) }
            var media by remember { mutableStateOf<RecapFrames.PreviewMedia?>(null) }
            LaunchedEffect(model, file) { media = model.inspectPreview(file); inspected = true }
            val info = media
            if (info != null) PreviewPlayback(file.path, info, model.previewVlcDir(), glass = glass)
            else AfText(tr(if (inspected) UiText.PREVIEW_UNREADABLE else UiText.PREVIEW_READING), muted = true)
            if (showOpen) {
                if (glass) GlassButton(tr(UiText.OPEN), { model.openPreview(file) }, Modifier.fillMaxWidth().testTag("export-preview-open"),
                    icon = AfIcons.Regular.ArrowSquareOut)
                else AfButton(tr(UiText.OPEN), { model.openPreview(file) }, Modifier.testTag("export-preview-open"))
            }
        }
    }
}

/** Group 2 B: no preview yet — a framed box with the Duotone monitor-play and the hint, centred. */
@Composable
private fun PreviewEmpty() {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(GlassColors.PathFill).border(1.dp, GlassColors.PathBorder, shape)
        .padding(horizontal = 14.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AfIcon(AfIcons.Duotone.MonitorPlay, tint = GlassColors.ExportBlue, size = 30.dp)
        AfText(My.breakSyllables(tr(UiText.PREVIEW_EMPTY)), Modifier.testTag("export-preview-empty"), muted = true, type = AfType.Caption,
            textAlign = TextAlign.Center)
    }
}

@Composable
internal fun PreviewPlayback(path: String, media: RecapFrames.PreviewMedia, vlc: String?, glass: Boolean = false,
    player: @Composable (MovieRequest, Modifier, (MovieSnapshot) -> Unit) -> Unit = { req, modifier, report ->
        MoviePlayback(path, req, modifier, vlcDir = vlc, onSnapshot = report)
    },
) {
    val aspect = requireNotNull(media.info.width).toFloat() / requireNotNull(media.info.height)
    val poster = remember(media) { media.poster?.let(::decodeImage) }
    val viewport = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }.takeIf { it > 0f } ?: 600f
    var full by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf(MovieSnapshot()) }
    var seek by remember { mutableLongStateOf(0L) }
    var generation by remember { mutableIntStateOf(0) }
    fun switchFullscreen(enable: Boolean) {
        if (started && snapshot.phase !in setOf(MoviePhase.ENDED, MoviePhase.FAILED)) {
            seek = snapshot.positionMs
            snapshot = MovieSnapshot(positionMs = seek)
        }
        full = enable
    }
    val ended = snapshot.phase == MoviePhase.ENDED
    val failed = snapshot.phase == MoviePhase.FAILED
    val picture: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.background(if (glass) Color.Black else AutoFlowColors.Panel).testTag("export-preview-player"),
            contentAlignment = Alignment.Center) {
            if (started && !ended && !failed) key(generation) {
                player(MovieRequest(seek, generation.toLong(), playing = playing, muted = false), Modifier.fillMaxSize()) { now ->
                    snapshot = now
                    if (now.phase == MoviePhase.ENDED || now.phase == MoviePhase.FAILED) playing = false
                }
            }
            if (!started || ended || failed || snapshot.phase == MoviePhase.OPENING) {
                if (poster != null) Image(poster, tr(UiText.PREVIEW_PLAYER), Modifier.fillMaxSize().testTag("export-preview-poster"), contentScale = ContentScale.FillBounds)
                else AfText(tr(if (failed || !started || ended) UiText.PREVIEW_UNREADABLE else UiText.PREVIEW_READING), muted = true)
            }
        }
    }
    val action = when {
        playing -> UiText.PAUSE
        ended || failed -> UiText.PREVIEW_REPLAY
        else -> UiText.PLAY
    }
    val toggle: () -> Unit = {
        if (playing) playing = false else {
            if (ended || failed) { snapshot = MovieSnapshot(); seek = 0; generation++ }
            started = true
            playing = true
        }
    }
    val actionIcon = when (action) {
        UiText.PAUSE -> AfIcons.Fill.Pause
        UiText.PLAY -> AfIcons.Fill.Play
        else -> AfIcons.Regular.Redo
    }
    val playButton: @Composable () -> Unit = {
        if (glass) GlassButton(tr(action), toggle, Modifier.testTag("export-preview-playback"), icon = actionIcon)
        else AfButton(tr(action), toggle, Modifier.testTag("export-preview-playback"), icon = actionIcon)
    }
    val content: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val width = previewWidth(maxWidth.value, if (full) (viewport - 140f).coerceAtLeast(48f) else viewport, aspect, full)
            picture(Modifier.width(width.dp).aspectRatio(aspect)
                .then(if (glass) Modifier.shadow(8.dp, VideoShape).clip(VideoShape) else Modifier))
        }
        if (failed && poster != null) AfText(tr(UiText.PREVIEW_UNREADABLE), muted = true)
        WrapRow {
            playButton()
            if (glass) GlassButton(tr(UiText.PREVIEW_FULLSCREEN), { switchFullscreen(true) }, Modifier.testTag("export-preview-fullscreen"),
                icon = AfIcons.Regular.CornersOut)
            else AfButton(tr(if (full) UiText.PREVIEW_EXIT_FULLSCREEN else UiText.PREVIEW_FULLSCREEN),
                { switchFullscreen(!full) }, Modifier.testTag("export-preview-fullscreen"))
        }
    }
    if (!full) content() else if (glass) GlassFullscreen(aspect, picture, playButton, failed && poster != null) { switchFullscreen(false) }
    else Dialog(onDismissRequest = { switchFullscreen(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        // Reserve room for actions at large font and keep the actual video centred.
        Column(Modifier.fillMaxSize().background(AutoFlowColors.Background).padding(8.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f, fill = false).testTag("export-fullscreen-content")) {
                Column(Modifier.fillMaxWidth()) { content() }
            }
        }
    }
}

/**
 * Group 2 D 25c–d (PR 5): the Movie Recap Export preview in fullscreen, the timeline Fullscreen look — black, the picture
 * centred (radius 12), nothing drawn over it; portrait = picture · controls, landscape = the picture fills the height
 * above one control bar. Controls = this player's own (Play / Pause / Replay) · spacer · exit `corners-in` (selected),
 * still `export-preview-fullscreen` with the label Exit fullscreen.
 */
@Composable
private fun GlassFullscreen(aspect: Float, picture: @Composable (Modifier) -> Unit, playButton: @Composable () -> Unit, unreadable: Boolean,
    onExit: () -> Unit) {
    Dialog(onDismissRequest = onExit, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        val controls: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                playButton()
                Spacer(Modifier.weight(1f))
                GlassIconButton(AfIcons.Regular.CornersIn, tr(UiText.PREVIEW_EXIT_FULLSCREEN), onExit, Modifier.testTag("export-preview-fullscreen"),
                    size = GlassShapes.IconButtonHeader, selected = true)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("export-fullscreen-content")) {
            val boxHeight = maxHeight
            val frame = Modifier.clip(VideoShape)
            if (maxWidth > maxHeight) Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    picture(frame.fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true))
                }
                Spacer(Modifier.height(6.dp))
                controls()
            } else Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.Center) {
                Box(Modifier.fillMaxWidth().heightIn(max = (boxHeight - 140.dp).coerceAtLeast(120.dp)), contentAlignment = Alignment.Center) {
                    picture(frame.fillMaxWidth().aspectRatio(aspect))
                }
                if (unreadable) AfText(tr(UiText.PREVIEW_UNREADABLE), Modifier.padding(top = 6.dp), muted = true)
                Spacer(Modifier.height(10.dp))
                controls()
            }
        }
    }
}
