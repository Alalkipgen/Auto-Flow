package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.alalkipgen.autoflow.media.playback.MovieRequest
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot

/** Platform surface only; native decoder ownership lives in :media, never in common screen/business logic. */
expect val moviePlaybackSupported: Boolean

/** F-6: show the paused first frame as soon as a preview opens (Android), instead of only after Play (desktop VLC). */
expect val moviePreviewAtOnce: Boolean

/** Source-clock controlled, muted movie under Compose overlays. A changed seekRevision applies one seek.
 * Optional frame feed reuses this decoder (never a second player or repeated Exact frame). [frameSize] describes
 * the source aspect for Android TextureView capture, not the output-frame aspect. */
@Composable
expect fun MoviePlayback(
    path: String,
    request: MovieRequest,
    modifier: Modifier = Modifier,
    vlcDir: String? = null,
    frameSize: androidx.compose.ui.unit.IntSize? = null,
    onFrame: ((androidx.compose.ui.graphics.ImageBitmap) -> Unit)? = null,
    onSnapshot: (MovieSnapshot) -> Unit = {},
)

/**
 * Test seam for the screenshot tests: a stand-in decoder with the [MoviePlayback] contract (the same requests, snapshots
 * and frames) and its own "open on the first frame" behaviour. Null (always in the app) = the platform decoder.
 */
internal class PreviewPlayer(
    val atOnce: Boolean,
    val content: @Composable (path: String, request: MovieRequest, modifier: Modifier,
        onFrame: ((androidx.compose.ui.graphics.ImageBitmap) -> Unit)?, onSnapshot: (MovieSnapshot) -> Unit) -> Unit,
)

internal val LocalPreviewPlayer = androidx.compose.runtime.staticCompositionLocalOf<PreviewPlayer?> { null }

/** [MoviePlayback], or the [LocalPreviewPlayer] stand-in when one is provided. */
@Composable
internal fun PreviewMoviePlayback(path: String, request: MovieRequest, modifier: Modifier = Modifier, vlcDir: String? = null,
    frameSize: androidx.compose.ui.unit.IntSize? = null, onFrame: ((androidx.compose.ui.graphics.ImageBitmap) -> Unit)? = null,
    onSnapshot: (MovieSnapshot) -> Unit = {}) {
    val stand = LocalPreviewPlayer.current
    if (stand != null) stand.content(path, request, modifier, onFrame, onSnapshot)
    else MoviePlayback(path, request, modifier, vlcDir, frameSize, onFrame, onSnapshot)
}

/** [moviePreviewAtOnce], or the [LocalPreviewPlayer] stand-in's. */
internal val previewAtOnce: Boolean @Composable get() = LocalPreviewPlayer.current?.atOnce ?: moviePreviewAtOnce
