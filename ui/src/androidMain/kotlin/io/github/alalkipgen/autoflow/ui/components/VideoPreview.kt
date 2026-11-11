package io.github.alalkipgen.autoflow.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import java.io.File

/**
 * Android ExoPlayer video preview.
 * Shows a 16:9 player for the video at [path] (absolute file path).
 * Auto-pauses when disposed.
 */
@Composable
actual fun VideoPreview(path: String, modifier: Modifier) {
    val context = LocalContext.current
    val file = remember(path) { File(path) }

    if (!file.exists()) {
        Box(
            modifier.fillMaxWidth().background(AutoFlowColors.Panel),
            contentAlignment = Alignment.Center,
        ) {
            AfText("Video file not found", muted = true, size = 12.sp)
        }
        return
    }

    val exoPlayer = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
    }

    DisposableEffect(path) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f),
        update = { view -> view.player = exoPlayer },
    )
}
