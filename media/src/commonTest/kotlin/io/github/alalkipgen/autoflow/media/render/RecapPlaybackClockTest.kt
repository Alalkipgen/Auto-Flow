package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.recap.SrtCue
import io.github.alalkipgen.autoflow.media.playback.*
import kotlin.test.*

class RecapPlaybackClockTest {
    private fun clock() = RecapPlaybackClock(RecapTimeline(listOf(
        RecapTimelineClip("block-a", 0, 0, 1_000, 10_000, 1f, 30_000),
        RecapTimelineClip("block-a", 1, 1_000, 2_000, 20_000, 0.8f, 30_000),
        RecapTimelineClip("block-b", 0, 2_000, 3_000, 5_000, 1f, 30_000),
    ), listOf(SrtCue(100, 600, "first"), SrtCue(1_200, 1_600, "second"))))
    private fun playing(ms: Long) = MovieSnapshot(ms, 30_000, MoviePhase.PLAYING)

    @Test fun sourceBoundariesTraverseEveryClipAndTimedCaptionsWithoutIndexIdentity() {
        val c = clock(); c.setPlaying(true); c.observe(playing(10_200), 40)
        assertEquals(200, c.positionMs); assertEquals("first", c.captions.single().text)
        c.observe(playing(10_990), 40)
        assertEquals("block-a:1", c.clip!!.key); assertEquals(20_000, c.request.sourceMs)
        assertEquals(0.8f, c.request.rate)
        c.observe(playing(10_990), 40); assertEquals(1_000, c.positionMs, "old-clock result cannot jump the output")
        c.observe(playing(20_240), 40); assertEquals(1_300, c.positionMs); assertEquals("second", c.captions.single().text)
        c.observe(playing(20_800), 40); assertEquals("block-b:0", c.clip!!.key)
        c.observe(playing(5_100), 40); assertTrue(c.captions.isEmpty())
        c.observe(playing(6_000), 40); assertFalse(c.playing); assertEquals(3_000, c.positionMs)
        c.setPlaying(true); assertEquals(0, c.positionMs); assertTrue(c.playing)
    }
    @Test fun pausedSeekUsesOutputTimeAndDoesNotContinuouslyReseekOrResume() {
        val c = clock(); c.seek(1_500)
        assertEquals(20_400, c.request.sourceMs); val revision = c.request.seekRevision
        c.observe(MovieSnapshot(20_400, 30_000, MoviePhase.PAUSED), 40)
        assertEquals(1_500, c.positionMs); assertFalse(c.playing)
        c.setPlaying(true); c.observe(playing(20_480), 40)
        assertEquals(1_600, c.positionMs); assertEquals(revision, c.request.seekRevision)
        c.setPlaying(false); c.observe(playing(20_600), 40); assertEquals(1_600, c.positionMs)
    }
    @Test fun eofHoldOnlyAdvancesWhilePlayingAndFailureStopsTheClock() {
        val c = RecapPlaybackClock(RecapTimeline(listOf(RecapTimelineClip("tail", 0, 0, 3_000, 29_000, 1f, 30_000)), emptyList()))
        c.setPlaying(true); c.observe(playing(29_900), 40)
        c.observe(MovieSnapshot(29_999, 30_000, MoviePhase.ENDED), 100)
        assertEquals(1_099, c.positionMs)
        c.setPlaying(false); c.observe(MovieSnapshot(29_999, 30_000, MoviePhase.ENDED), 100); assertEquals(1_099, c.positionMs)
        c.setPlaying(true); c.observe(MovieSnapshot(phase = MoviePhase.FAILED, problem = "unavailable"), 40)
        assertEquals("unavailable", c.problem); assertFalse(c.playing)
    }
    @Test fun failedSeekCannotMasqueradeAsProgress() {
        val c = clock(); c.seek(2_500); c.setPlaying(true)
        repeat(126) { c.observe(playing(10_100), 40) }
        assertEquals(2_500, c.positionMs); assertFalse(c.playing); assertNotNull(c.problem)
    }
}
