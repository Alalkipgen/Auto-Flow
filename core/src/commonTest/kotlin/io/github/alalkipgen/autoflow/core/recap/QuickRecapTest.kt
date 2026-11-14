package io.github.alalkipgen.autoflow.core.recap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickRecapTest {

    @Test
    fun defaultStage_isScenes() {
        val qr = QuickRecap()
        assertEquals(QuickRecapStage.SCENES, qr.stage)
    }

    @Test
    fun defaultTargetMs_matchesRecap() {
        assertEquals(Recap.DEFAULT_TARGET_MS, QuickRecap().targetMs)
    }

    @Test
    fun recap_quickField_defaultNull() {
        val recap = Recap()
        assertNull(recap.quick)
    }

    @Test
    fun recap_quickField_set() {
        val recap = Recap(quick = QuickRecap(stage = QuickRecapStage.REVIEW, targetMs = 60_000L))
        assertEquals(QuickRecapStage.REVIEW, recap.quick?.stage)
        assertEquals(60_000L, recap.quick?.targetMs)
    }

    @Test
    fun stageOrdinal_progressOrder() {
        val stages = QuickRecapStage.entries
        assertEquals(QuickRecapStage.SCENES, stages[0])
        assertEquals(QuickRecapStage.DONE, stages.last())
    }
}
