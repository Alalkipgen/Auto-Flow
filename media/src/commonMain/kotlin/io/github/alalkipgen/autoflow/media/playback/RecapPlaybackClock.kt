package io.github.alalkipgen.autoflow.media.playback

import io.github.alalkipgen.autoflow.media.render.RecapTimeline

/** A seek command changes revision; the decoder must not re-seek on each rendered/polled frame. */
data class MovieRequest(val sourceMs: Long, val seekRevision: Long, val rate: Float = 1f, val playing: Boolean = false, val muted: Boolean = true)

/** One explicit OUTPUT clock; source times are interpreted only inside the active stable block/clip interval. */
class RecapPlaybackClock(val timeline: RecapTimeline) {
    var positionMs = 0L; private set
    var playing = false; private set
    var problem: String? = null; private set
    private var revision = 0L
    private var waitingForSource: Long? = null
    private var waitMs = 0L
    val clip get() = timeline.clipAt(positionMs)
    val captions get() = timeline.captionsAt(positionMs)
    val request: MovieRequest get() = MovieRequest(clip?.sourceAt(positionMs) ?: 0, revision, clip?.sourceRate ?: 1f, playing)

    fun setPlaying(value: Boolean) {
        if (value && timeline.durationMs > 0 && positionMs >= timeline.durationMs) seek(0)
        playing = value && clip != null && problem == null
    }
    fun seek(outputMs: Long) {
        problem = null
        positionMs = outputMs.coerceIn(0, (timeline.durationMs - 1).coerceAtLeast(0))
        revision++; waitingForSource = clip?.sourceAt(positionMs); waitMs = 0
    }
    fun observe(snapshot: MovieSnapshot, elapsedMs: Long) {
        if (snapshot.phase == MoviePhase.FAILED) { problem = snapshot.problem ?: "Movie playback failed"; playing = false; return }
        if (snapshot.phase == MoviePhase.CLOSED || snapshot.phase == MoviePhase.OPENING) return
        val c = clip ?: return
        val waiting = waitingForSource
        if (waiting != null) {
            // Ignore the previous clip's stale clock while the platform decoder applies a seek.
            if (kotlin.math.abs(snapshot.positionMs - waiting) > 500) {
                waitMs += elapsedMs.coerceIn(0, 200)
                if (waitMs >= 5_000) { problem = "Movie seek did not settle. Use still / Exact frame."; playing = false }
                return
            }
            waitingForSource = null
        }
        if (!playing) return
        val atEof = snapshot.phase == MoviePhase.ENDED
        val mapped = c.outputAt(snapshot.positionMs)
        // Export tpad holds the last frame during a fitted run-on past EOF. Only this frozen tail uses wall time.
        val next = if (atEof && c.sourceAt(c.outputToMs - 1) >= c.movieDurationMs - 1) {
            maxOf(positionMs, mapped) + elapsedMs.coerceIn(0, 200)
        } else if (snapshot.phase == MoviePhase.PLAYING) mapped else positionMs
        if (next >= c.outputToMs - 20) {
            positionMs = c.outputToMs
            if (positionMs >= timeline.durationMs) { playing = false; return }
            revision++; waitingForSource = clip?.sourceAt(positionMs); waitMs = 0
        } else positionMs = next.coerceAtLeast(c.outputFromMs)
    }
}
