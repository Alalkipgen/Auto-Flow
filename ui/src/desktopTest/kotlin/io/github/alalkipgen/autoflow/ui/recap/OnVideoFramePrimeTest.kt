package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieRequest
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import kotlin.test.*

class OnVideoFramePrimeTest {
    private val request = MovieRequest(3500, 1)
    private fun snapshot(at: Long, phase: MoviePhase = MoviePhase.PLAYING) =
        MovieSnapshot(positionMs = at, durationMs = 10_000, phase = phase)

    @Test fun playingAndOldFramesCannotAcknowledgeTheQueuedSeek() {
        val prime = LayerEditFramePrime(request)
        assertFalse(prime.acceptsFrame(request))
        assertFalse(prime.observe(snapshot(80), request).acceptsFrame(request))
        assertFalse(prime.observe(snapshot(3499), request).acceptsFrame(request))
        assertFalse(prime.observe(snapshot(3500), request).acceptsFrame(request), "setTime can report its target before decoded pixels catch up")
        val acknowledged = prime.observe(snapshot(3501), request)
        assertFalse(acknowledged.acceptsFrame(request), "the source acknowledgement is not a decoded frame")
        assertTrue(acknowledged.delivered(request).acceptsFrame(request), "a subsequent warming frame may request Pause, never expose output")
        assertEquals(LayerEditFramePrime.Stage.PAUSING, acknowledged.pausing().stage)
    }

    @Test fun aNewSeekOrRateCannotReuseAnOldFrameAcknowledgement() {
        val prime = LayerEditFramePrime(request).observe(snapshot(3501), request).delivered(request)
        assertFalse(prime.acceptsFrame(request.copy(seekRevision = 2)))
        assertFalse(prime.acceptsFrame(request.copy(sourceMs = 6500)))
        assertFalse(prime.acceptsFrame(request.copy(rate = .8f)))
        val next = request.copy(sourceMs = 6500, seekRevision = 2)
        assertFalse(prime.observe(snapshot(3500), next).acceptsFrame(next))
        assertFalse(prime.observe(snapshot(6501), next).acceptsFrame(next), "a new request clears the old frame")
        assertTrue(prime.observe(snapshot(6501), next).delivered(next).acceptsFrame(next))
    }

    @Test fun onlyRealProgressCanFinishWarmingAndNoFrameCanFinishPausing() {
        for (phase in listOf(MoviePhase.OPENING, MoviePhase.PAUSED, MoviePhase.FAILED, MoviePhase.CLOSED)) {
            assertFalse(LayerEditFramePrime(request).observe(snapshot(3501, phase), request).acceptsFrame(request))
        }
        val pausing = LayerEditFramePrime(request).pausing()
        assertFalse(pausing.observe(snapshot(4001), request).acceptsFrame(request))
        assertFalse(pausing.isPaused(snapshot(4001)))
        assertTrue(pausing.isPaused(snapshot(4001, MoviePhase.PAUSED)))
    }

    @Test fun onlyAFreshMatchingPausedSeekFrameCanExposeOutput() {
        val next = request.copy(seekRevision = 2)
        val seeking = LayerEditFramePrime(request).pausing().seeking(next)
        assertFalse(seeking.acceptsFrame(next), "the warming frame must stay hidden")
        assertFalse(seeking.observe(snapshot(4000, MoviePhase.PAUSED), next).acceptsFrame(next))
        assertFalse(seeking.observe(snapshot(3500), next).acceptsFrame(next))
        val ready = seeking.observe(snapshot(3500, MoviePhase.PAUSED), next)
        assertFalse(ready.acceptsFrame(request), "the pre-pause seek revision is stale")
        assertFalse(ready.acceptsFrame(next), "the paused clock alone is not a newly decoded frame")
        assertTrue(ready.delivered(next).acceptsFrame(next), "only a newly delivered paused-seek frame completes the handshake")
    }

    @Test fun thePausedSeekBitmapMayArriveBeforeItsAcknowledgementSnapshot() {
        val seeking = LayerEditFramePrime(request, LayerEditFramePrime.Stage.SEEKING)
        val delivered = seeking.delivered(request)
        assertFalse(delivered.acceptsFrame(request), "the bitmap alone is not the paused-source acknowledgement")
        assertTrue(delivered.observe(snapshot(3500, MoviePhase.PAUSED), request).acceptsFrame(request))
        assertFalse(delivered.observe(snapshot(4000, MoviePhase.PAUSED), request).acceptsFrame(request))
        assertFalse(delivered.observe(snapshot(3500, MoviePhase.PAUSED), request.copy(seekRevision = 2)).acceptsFrame(request.copy(seekRevision = 2)))
    }

    @Test fun eofHoldRequiresTheRealTerminalFrameAndAnActualEofRequest() {
        val ended = snapshot(9960, MoviePhase.ENDED)
        assertFalse(LayerEditFramePrime(request).acceptsEndedFrame(ended, true))
        val tail = LayerEditFramePrime(request.copy(sourceMs = 9999))
        assertFalse(tail.acceptsEndedFrame(ended, false))
        assertFalse(tail.acceptsEndedFrame(snapshot(9999), true))
        assertFalse(tail.acceptsEndedFrame(ended.copy(durationMs = 0), true))
        assertTrue(tail.acceptsEndedFrame(ended, true))
    }
}