package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.github.alalkipgen.autoflow.media.playback.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

actual val moviePlaybackSupported: Boolean = true

@Composable
actual fun MoviePlayback(path: String, request: MovieRequest, modifier: Modifier, vlcDir: String?, onSnapshot: (MovieSnapshot) -> Unit) {
    val opened = remember(path, vlcDir) { DesktopMoviePlayer.open(path, vlcDir) }
    val callback by rememberUpdatedState(onSnapshot)
    val player = (opened as? MovieOpen.Ready)?.player
    DisposableEffect(player) { onDispose { player?.close() } }
    LaunchedEffect(opened) {
        if (opened is MovieOpen.Unavailable) callback(MovieSnapshot(phase = MoviePhase.FAILED, problem = opened.reason))
        else while (true) { player?.snapshot()?.let(callback); withContext(Dispatchers.Default) { delay(40) } }
    }
    if (player == null) { Box(modifier); return }
    LaunchedEffect(player, request.seekRevision) { player.seek(request.sourceMs) }
    LaunchedEffect(player, request.rate, request.playing) { player.setRate(request.rate); player.setPlaying(request.playing) }
    val frame by player.frames.collectAsState()
    val bitmap = remember(frame) {
        frame?.let { f ->
            org.jetbrains.skia.Image.makeRaster(ImageInfo(f.width, f.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), f.bgra, f.rowBytes).toComposeImageBitmap()
        }
    }
    Box(modifier) { bitmap?.let { Image(it, "Movie playback", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) } }
}
