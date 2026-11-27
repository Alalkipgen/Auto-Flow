package io.github.alalkipgen.autoflow.ui.export

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
internal fun ExportPreviewCard(file: PreviewFile?, model: ExportModel, showOpen: Boolean) {
    AfCard(tr(UiText.PREVIEW_PLAYER), Modifier.testTag("export-preview-card")) {
        if (file == null) {
            AfText(tr(UiText.PREVIEW_EMPTY), Modifier.testTag("export-preview-empty"), muted = true)
        } else key(file) {
            var inspected by remember { mutableStateOf(false) }
            var media by remember { mutableStateOf<RecapFrames.PreviewMedia?>(null) }
            LaunchedEffect(model, file) { media = model.inspectPreview(file); inspected = true }
            val info = media
            if (info != null) PreviewPlayback(file.path, info, model.previewVlcDir())
            else AfText(tr(if (inspected) UiText.PREVIEW_UNREADABLE else UiText.PREVIEW_READING), muted = true)
            if (showOpen) AfButton(tr(UiText.OPEN), { model.openPreview(file) }, Modifier.testTag("export-preview-open"))
        }
    }
}

@Composable
internal fun PreviewPlayback(path: String, media: RecapFrames.PreviewMedia, vlc: String?,
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
    val content: @Composable () -> Unit = {
        val ended = snapshot.phase == MoviePhase.ENDED
        val failed = snapshot.phase == MoviePhase.FAILED
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val width = previewWidth(maxWidth.value, if (full) (viewport - 140f).coerceAtLeast(48f) else viewport, aspect, full)
            Box(Modifier.width(width.dp).aspectRatio(aspect).background(AutoFlowColors.Panel).testTag("export-preview-player"),
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
        if (failed && poster != null) AfText(tr(UiText.PREVIEW_UNREADABLE), muted = true)
        WrapRow {
            val action = when {
                playing -> UiText.PAUSE
                ended || failed -> UiText.PREVIEW_REPLAY
                else -> UiText.PLAY
            }
            AfButton(tr(action), {
                if (playing) playing = false else {
                    if (ended || failed) { snapshot = MovieSnapshot(); seek = 0; generation++ }
                    started = true
                    playing = true
                }
            }, Modifier.testTag("export-preview-playback"))
            AfButton(tr(if (full) UiText.PREVIEW_EXIT_FULLSCREEN else UiText.PREVIEW_FULLSCREEN),
                { switchFullscreen(!full) }, Modifier.testTag("export-preview-fullscreen"))
        }
    }
    if (!full) content() else Dialog(onDismissRequest = { switchFullscreen(false) },
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
