package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ui.components.SongPlayer
import kotlin.test.*

/** F-5 (D-94): the live preview's voice and music follow the muted picture's clock. */
class PreviewSyncTest {
    private class FakePlayer(override val durationMs: Long?) : SongPlayer {
        override var positionMs = 0L
        override var playing = false
        val seeks = mutableListOf<Long>()
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun seekTo(ms: Long) { seeks += ms; positionMs = ms }
    }

    @Test fun aTakeStartsAtTheClockAndIsOnlyPutBackWhenItDrifts() {
        val p = FakePlayer(4_000)
        PreviewSync.apply(p, 1_000, playing = true, loop = false)
        assertTrue(p.playing); assertEquals(listOf(1_000L), p.seeks)
        p.positionMs = 1_250
        PreviewSync.apply(p, 1_000 + PreviewSync.DRIFT_MS, playing = true, loop = false)
        assertEquals(listOf(1_000L), p.seeks, "small drift is left alone (seeking would stutter)")
        p.positionMs = 900
        PreviewSync.apply(p, 2_000, playing = true, loop = false)
        assertEquals(listOf(1_000L, 2_000L), p.seeks)
    }

    @Test fun pauseHoldsThePlaceAndPastTheEndIsSilent() {
        val p = FakePlayer(4_000)
        PreviewSync.apply(p, 1_000, playing = true, loop = false)
        PreviewSync.apply(p, 2_500, playing = false, loop = false)
        assertFalse(p.playing); assertEquals(2_500L, p.positionMs, "a scrub while paused moves the take with it")
        PreviewSync.apply(p, 5_000, playing = true, loop = false)
        assertFalse(p.playing, "the take ended before the block did"); assertEquals(2_500L, p.positionMs)
    }

    @Test fun musicLoopsFromTheStart() {
        val p = FakePlayer(3_000)
        PreviewSync.apply(p, 7_500, playing = true, loop = true)
        assertTrue(p.playing); assertEquals(1_500L, p.positionMs)
        val unknown = FakePlayer(null)
        PreviewSync.apply(unknown, 7_500, playing = true, loop = true)
        assertEquals(7_500L, unknown.positionMs, "length not known yet: the clock as it is")
    }

    @Test fun aSpedUpTakeIsAheadOfTheOutputClock() {
        assertEquals(0L, PreviewSync.offset(100, 200, 1.25f))
        assertEquals(1_250L, PreviewSync.offset(1_200, 200, 1.25f))
    }
}
