package io.github.alalkipgen.autoflow.ui.components

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/** Media3 ExoPlayer for the song file (audio only). */
@Composable
actual fun rememberSongPlayer(path: String): SongPlayer? {
    val context = LocalContext.current
    val player = remember(path) {
        if (!File(path).exists()) {
            null
        } else {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
                prepare()
            }
        }
    }
    DisposableEffect(player) {
        onDispose { player?.release() }
    }
    return remember(player) { player?.let(::ExoSongPlayer) }
}

private class ExoSongPlayer(private val exo: ExoPlayer) : SongPlayer {
    override val positionMs: Long get() = exo.currentPosition
    override val durationMs: Long? get() = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }
    override val playing: Boolean get() = exo.isPlaying
    override fun play() = exo.play()
    override fun pause() = exo.pause()
    override fun seekTo(ms: Long) = exo.seekTo(ms)
}
