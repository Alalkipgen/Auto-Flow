package io.github.alalkipgen.autoflow.media.playback

import kotlinx.coroutines.flow.StateFlow

/** Immutable, opaque BGRX pixels. rowBytes can include decoder padding; width/height are the visible picture. */
data class MovieFrame(val width: Int, val height: Int, val rowBytes: Int, val bgra: ByteArray, val serial: Long)

enum class MoviePhase { OPENING, PLAYING, PAUSED, ENDED, FAILED, CLOSED }
data class MovieSnapshot(val positionMs: Long = 0, val durationMs: Long = 0, val phase: MoviePhase = MoviePhase.OPENING, val problem: String? = null)

sealed interface MovieOpen {
    data class Ready(val player: MoviePlayer) : MovieOpen
    data class Unavailable(val reason: String) : MovieOpen
}

/** Platform decoder port. Controls never throw native failures across the module boundary. All times are source times. */
interface MoviePlayer {
    val frames: StateFlow<MovieFrame?>
    fun setPlaying(playing: Boolean)
    fun seek(positionMs: Long)
    fun setRate(rate: Float)
    fun snapshot(): MovieSnapshot
    fun close()
}
