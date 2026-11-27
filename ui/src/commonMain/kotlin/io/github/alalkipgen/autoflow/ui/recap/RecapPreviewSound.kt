package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import io.github.alalkipgen.autoflow.media.render.RecapTimeline
import io.github.alalkipgen.autoflow.ui.components.SongPlayer
import io.github.alalkipgen.autoflow.ui.components.rememberSongPlayer
import kotlin.math.abs

/**
 * F-5 (D-94): the live preview's sound before any render — the take of the block under the output clock (the same
 * voice cues as the export, at its SmartFit speed) and the background music, looped from the start at its volume.
 * Each follows the muted picture's clock: it plays while the preview plays and is put back when it drifts. The
 * movie's own sound, voice polish, ducking and loudness are in the render only.
 */
@Composable
internal fun RecapPreviewSound(timeline: RecapTimeline, music: RecapPreviewSource.Music?, outputMs: Long, playing: Boolean) {
    val cue = timeline.voiceAt(outputMs)
    if (cue != null) key(cue.file, cue.startMs) {
        PreviewTrack(cue.file, PreviewSync.offset(outputMs, cue.startMs, cue.speedFactor), playing, cue.speedFactor, 1f, loop = false)
    }
    if (music != null) key(music.file) { PreviewTrack(music.file, outputMs, playing, 1f, music.volume, loop = true) }
}

@Composable
internal fun PreviewTrack(file: String, wantMs: Long, playing: Boolean, speed: Float, volume: Float, loop: Boolean) {
    val player = rememberSongPlayer(file) ?: return
    LaunchedEffect(player, speed, volume) { player.setSpeed(speed); player.setVolume(volume) }
    SideEffect { PreviewSync.apply(player, wantMs, playing, loop) }
    DisposableEffect(player) { onDispose { player.pause() } }
}

/** One step of keeping a [SongPlayer] on the preview clock (called after every preview recomposition). */
internal object PreviewSync {
    /** A track further than this from the clock is put back (seeking more often would stutter). */
    const val DRIFT_MS = 300L

    /** Where in a take [startMs] at [speed] the output time [outputMs] is. */
    fun offset(outputMs: Long, startMs: Long, speed: Float): Long = ((outputMs - startMs).coerceAtLeast(0) * speed).toLong()

    fun apply(player: SongPlayer, wantMs: Long, playing: Boolean, loop: Boolean) {
        val length = player.durationMs?.takeIf { it > 0 }
        val at = if (loop && length != null) wantMs % length else wantMs
        val past = length != null && at >= length
        if (!playing || past) {
            if (player.playing) player.pause()
            if (!past && abs(player.positionMs - at) > DRIFT_MS) player.seekTo(at)
            return
        }
        if (abs(player.positionMs - at) > DRIFT_MS) player.seekTo(at)
        if (!player.playing) player.play()
    }
}
