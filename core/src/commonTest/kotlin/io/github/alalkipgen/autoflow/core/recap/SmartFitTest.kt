package io.github.alalkipgen.autoflow.core.recap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmartFitTest {

    @Test
    fun fitChoice_defaultIsExtend() {
        val recap = Recap()
        assertNull(recap.fit[0]) // absent = default EXTEND
    }

    @Test
    fun fitChoice_setAndRetrieve() {
        val recap = Recap(fit = mapOf(0 to FitChoice.SPEED, 2 to FitChoice.FREEZE))
        assertEquals(FitChoice.SPEED, recap.fit[0])
        assertEquals(FitChoice.FREEZE, recap.fit[2])
        assertNull(recap.fit[1])
    }

    @Test
    fun fitChoice_copy_updatesMap() {
        val recap = Recap(fit = mapOf(1 to FitChoice.EXTEND))
        val updated = recap.copy(fit = recap.fit + (1 to FitChoice.NEXT_RANGE))
        assertEquals(FitChoice.NEXT_RANGE, updated.fit[1])
    }

    @Test
    fun smartFitConfig_defaults() {
        val cfg = SmartFitConfig()
        assertEquals(1.0f, cfg.maxVoiceSpeed)
        assertEquals(1.0f, cfg.maxVideoSlow)
        assertEquals(350, cfg.silenceGapMs)
    }

    @Test
    fun fitChoiceEnum_allValues() {
        val choices = FitChoice.entries
        assert(FitChoice.EXTEND in choices)
        assert(FitChoice.NEXT_RANGE in choices)
        assert(FitChoice.SPEED in choices)
        assert(FitChoice.SLOW_VIDEO in choices)
        assert(FitChoice.FREEZE in choices)
    }
}
