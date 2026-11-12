package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.sp

/** ▶ / ■ for one audio file; the player exists only while it plays (none on desktop: [rememberSongPlayer] is null). */
@Composable
fun AudioPlayButton(path: String, playing: Boolean, onToggle: () -> Unit) {
    if (!playing) {
        AfButton("▶", onToggle)
        return
    }
    val player = rememberSongPlayer(path)
    if (player == null) {
        AfText("Playback is not available here", muted = true, size = 12.sp)
        LaunchedEffect(path) { kotlinx.coroutines.delay(2_000); onToggle() }
        return
    }
    LaunchedEffect(player) {
        player.seekTo(0)
        player.play()
        kotlinx.coroutines.delay(300)
        while (player.playing) kotlinx.coroutines.delay(200)
        onToggle()
    }
    AfButton("■", { player.pause(); onToggle() })
}
