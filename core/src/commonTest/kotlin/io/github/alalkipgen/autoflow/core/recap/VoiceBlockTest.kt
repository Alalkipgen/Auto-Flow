package io.github.alalkipgen.autoflow.core.recap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceBlockTest {

    @Test
    fun teleprompterState_defaults() {
        val state = TeleprompterState(blockId = "b_01")
        assertEquals("b_01", state.blockId)
        assertEquals(0, state.segmentIndex)
        assertFalse(state.recording)
    }

    @Test
    fun teleprompterState_recording() {
        val state = TeleprompterState("b_02", segmentIndex = 3, recording = true)
        assertTrue(state.recording)
        assertEquals(3, state.segmentIndex)
    }

    @Test
    fun speakAllRequest_blocksRemaining() {
        val req = SpeakAllRequest(
            projectId = "p_01",
            blocksRemaining = listOf("b_01", "b_02", "b_03"),
            estimatedRpm = 30,
        )
        assertEquals(3, req.blocksRemaining.size)
        assertEquals(30, req.estimatedRpm)
    }

    @Test
    fun voiceTab_allValues() {
        val tabs = VoiceTab.entries
        assertTrue(VoiceTab.SPEAK in tabs)
        assertTrue(VoiceTab.RECORD in tabs)
        assertTrue(VoiceTab.CLEAN in tabs)
        assertTrue(VoiceTab.CLONE in tabs)
    }
}
