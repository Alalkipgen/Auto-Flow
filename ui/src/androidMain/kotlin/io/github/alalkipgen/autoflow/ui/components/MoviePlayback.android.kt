package io.github.alalkipgen.autoflow.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.TextureView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.github.alalkipgen.autoflow.media.playback.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual val moviePlaybackSupported: Boolean = true

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.activity() else null
    else -> null
}

/** Same existing ExoPlayer/TextureView engine. Source intervals, speed, pause and seek now follow the output clock. */
@Composable
actual fun MoviePlayback(path: String, request: MovieRequest, modifier: Modifier, vlcDir: String?, onSnapshot: (MovieSnapshot) -> Unit) {
    val activity = LocalContext.current.activity()
    val callback by rememberUpdatedState(onSnapshot)
    val player = remember(path, activity) { activity?.let { AndroidMoviePlayer(it, path) } }
    DisposableEffect(player) { onDispose { player?.close() } }
    LaunchedEffect(player) {
        if (player == null) callback(MovieSnapshot(phase = MoviePhase.FAILED, problem = "Android playback needs an Activity. Still / Exact frame remain available."))
        else while (true) { callback(player.snapshot()); withContext(Dispatchers.Default) { delay(40) } }
    }
    LaunchedEffect(player, request.seekRevision) { player?.seek(request.sourceMs) }
    LaunchedEffect(player, request.rate, request.playing) { player?.setRate(request.rate); player?.setPlaying(request.playing) }
    key(player) { AndroidView(factory = { ctx -> TextureView(ctx).also { player?.attach(it) } }, modifier = modifier) }
}
