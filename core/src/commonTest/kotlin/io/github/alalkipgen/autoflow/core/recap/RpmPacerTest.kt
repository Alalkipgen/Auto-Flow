package io.github.alalkipgen.autoflow.core.recap

import kotlin.test.Test
import kotlin.test.assertEquals

/** RPM pacer — only tests the synchronous / pure helpers (5A-5). */
class RpmPacerTest {

    @Test
    fun rpmPropertyIsSet() {
        val pacer = RpmPacer(rpm = 30)
        assertEquals(30, pacer.rpm)
    }

    @Test
    fun etaSeconds_mixedMeasured_returnsMax() {
        val job = AiJob(
            id = "j1",
            kind = AiJobKind.TTS_ALL,
            project = "p1",
            items = listOf("0", "1", "2"),
            rpm = 60,
            msPerItem = 2_000L,
        )
        // max(3 * 2000 / 1000, 3 * 60 / 60) = max(6, 3) = 6
        assertEquals(6L, job.etaSeconds())
    }

    @Test
    fun etaSeconds_onlyRpm() {
        val job = AiJob(
            id = "j2",
            kind = AiJobKind.TTS_ALL,
            project = "p1",
            items = List(10) { it.toString() },
            rpm = 30,
            msPerItem = null,
        )
        // 10 * 60 / 30 = 20 s
        assertEquals(20L, job.etaSeconds())
    }

    @Test
    fun etaSeconds_emptyItems_zero() {
        val job = AiJob(
            id = "j3",
            kind = AiJobKind.PUBLISH_KIT,
            project = "p1",
            items = emptyList(),
            rpm = 60,
        )
        assertEquals(0L, job.etaSeconds())
    }

    @Test
    fun etaSeconds_measuredWins() {
        // measured = 3 * 10 s = 30, rpm = 10 items * 60/60 = 10 → measured wins
        val job = AiJob(
            id = "j4",
            kind = AiJobKind.TTS_ALL,
            project = "p1",
            items = List(10) { it.toString() },
            rpm = 60,
            msPerItem = 10_000L,
        )
        assertEquals(100L, job.etaSeconds())
    }
}
