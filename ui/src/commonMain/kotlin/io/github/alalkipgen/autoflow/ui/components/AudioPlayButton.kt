package io.github.alalkipgen.autoflow.ui.components

import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.theme.GlassShapes

/**
 * Play / Stop icon button (48 dp, label = description + tooltip) for one audio file; the player exists only while it
 * plays (none on desktop: [rememberSongPlayer] is null).
 */
@Composable
fun AudioPlayButton(path: String, playing: Boolean, onToggle: () -> Unit, glass: Boolean = false) {
    if (!playing) {
        // Glass (Movie Recap block cards, Group 2 A §2): a 40 dp speaker, so it is not taken for the picture's Play.
        if (glass) GlassIconButton(AfIcons.Fill.Volume, tr(UiText.AUDIO_PLAY), onToggle, size = GlassShapes.IconButtonHeader)
        else AfIconButton(AfIcons.Fill.Play, tr(UiText.AUDIO_PLAY), onToggle)
        return
    }
    val player = rememberSongPlayer(path)
    if (player == null) {
        AfText(tr(UiText.PLAYBACK_UNAVAILABLE), muted = true, size = 12.sp)
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
    if (glass) GlassIconButton(AfIcons.Fill.Square, tr(UiText.AUDIO_STOP), { player.pause(); onToggle() }, size = GlassShapes.IconButtonHeader, selected = true)
    else AfIconButton(AfIcons.Fill.Square, tr(UiText.AUDIO_STOP), { player.pause(); onToggle() }, selected = true)
}
