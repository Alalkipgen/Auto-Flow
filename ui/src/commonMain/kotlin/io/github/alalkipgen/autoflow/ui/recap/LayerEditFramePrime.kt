package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieRequest
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import kotlin.math.abs

/** UI-only handshake: PLAYING and setTime's reported target can both precede the new decoded pixels. */
internal data class LayerEditFramePrime(
    val request: MovieRequest,
    val stage: Stage = Stage.WARMING,
    val sourceAcknowledged: Boolean = false,
    val frameReceived: Boolean = false,
) {
    enum class Stage { WARMING, PAUSING, SEEKING }
    val warming get() = stage == Stage.WARMING

    fun observe(snapshot: MovieSnapshot, current: MovieRequest) = LayerEditFramePrime(current,
        stage, when (stage) {
            Stage.WARMING -> snapshot.phase == MoviePhase.PLAYING && snapshot.positionMs > current.sourceMs
            Stage.PAUSING -> false
            // Same paused-source contract already measured by DesktopMoviePlayerTest; not a test-window change.
            Stage.SEEKING -> snapshot.phase == MoviePhase.PAUSED && abs(snapshot.positionMs - current.sourceMs) < 250
        }, frameReceived && request == current)

    // Warming frames are NEVER exposed: first pause the working decoder, then issue a fresh paused seek.
    // A paused seek can deliver its only bitmap BEFORE the next 40 ms snapshot; accept either event order.
    fun delivered(current: MovieRequest) = if (request == current) copy(frameReceived = true)
        else LayerEditFramePrime(current, stage, frameReceived = true)
    fun acceptsFrame(current: MovieRequest) = sourceAcknowledged && frameReceived && request == current
    fun pausing() = copy(stage = Stage.PAUSING, sourceAcknowledged = false, frameReceived = false)
    fun isPaused(snapshot: MovieSnapshot) = stage == Stage.PAUSING && snapshot.phase == MoviePhase.PAUSED
    fun seeking(current: MovieRequest) = LayerEditFramePrime(current, Stage.SEEKING)

    // A fitted EOF hold has no subsequent frame after ENDED; its real terminal frame is the requested one.
    fun acceptsEndedFrame(snapshot: MovieSnapshot, hasFrame: Boolean) = hasFrame &&
        snapshot.phase == MoviePhase.ENDED && snapshot.durationMs > 0 && request.sourceMs >= snapshot.durationMs - 1
}