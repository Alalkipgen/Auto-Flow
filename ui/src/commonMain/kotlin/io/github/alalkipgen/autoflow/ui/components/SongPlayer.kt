package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.runtime.Composable

/** Plays the song for tap-to-sync (PLAN Q-11). Read [positionMs] often (it is not Compose state). */
interface SongPlayer {
    val positionMs: Long

    /** Song length, or null until it is known. */
    val durationMs: Long?
    val playing: Boolean
    fun play()
    fun pause()
    fun seekTo(ms: Long)

    /** Playback rate with the pitch kept (F-5: a SmartFit SPEED take in the live preview); ignored where unsupported. */
    fun setSpeed(speed: Float) {}

    /** 0–1 (F-5: the music bed at its mix volume). */
    fun setVolume(volume: Float) {}
}

/** A player for the audio file at [path], released with the composition; null where audio playback is unsupported. */
@Composable
expect fun rememberSongPlayer(path: String): SongPlayer?
