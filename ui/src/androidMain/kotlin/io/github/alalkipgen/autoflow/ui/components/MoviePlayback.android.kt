package io.github.alalkipgen.autoflow.ui.components

import android.net.Uri
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

actual val moviePlaybackSupported: Boolean = true

/**
 * ExoPlayer on a TextureView (not a SurfaceView, so the Compose boxes and caption drawn after it stay on top). The
 * clip loops muted; the player is released when the preview stops or leaves the screen.
 */
@Composable
actual fun MoviePlayback(path: String, startMs: Long, endMs: Long, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(path, startMs, endMs) {
        ExoPlayer.Builder(context).build().apply {
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs.coerceAtLeast(0))
                .apply { if (endMs > startMs) setEndPositionMs(endMs) }
                .build()
            setMediaItem(MediaItem.Builder().setUri(Uri.fromFile(File(path))).setClippingConfiguration(clip).build())
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    // A new player (another scene) gets a new view: the factory attaches each player once.
    key(player) {
        AndroidView(factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } }, modifier = modifier)
    }
}
