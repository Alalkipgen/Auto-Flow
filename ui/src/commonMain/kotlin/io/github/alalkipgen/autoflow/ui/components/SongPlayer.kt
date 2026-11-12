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
}

/** A player for the audio file at [path], released with the composition; null where audio playback is unsupported. */
@Composable
expect fun rememberSongPlayer(path: String): SongPlayer?
