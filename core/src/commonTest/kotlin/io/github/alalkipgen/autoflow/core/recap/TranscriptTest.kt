package io.github.alalkipgen.autoflow.core.recap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptTest {

    @Test
    fun subtitleTrack_isText_subrip() {
        val track = SubtitleTrack(index = 0, codecName = "subrip", language = "my")
        assertTrue(track.isText)
        assertFalse(track.isPicture)
    }

    @Test
    fun subtitleTrack_isPicture_pgs() {
        val track = SubtitleTrack(index = 1, codecName = "hdmv_pgs_subtitle", language = "en")
        assertFalse(track.isText)
        assertTrue(track.isPicture)
    }

    @Test
    fun linesInRange_returnsFilteredLines() {
        val tx = Transcript(
            source = Transcript.SOURCE_SUBTITLE,
            lang = "my",
            lines = listOf(
                TranscriptLine(0, 2000, "Line 1"),
                TranscriptLine(2000, 4000, "Line 2"),
                TranscriptLine(4000, 6000, "Line 3"),
            )
        )
        val lines = tx.linesInRange(0, 4000)
        assertEquals(2, lines.size)
        assertEquals("Line 1", lines[0].text)
        assertEquals("Line 2", lines[1].text)
    }

    @Test
    fun linesInRange_respectsMaxChars() {
        val tx = Transcript(
            source = Transcript.SOURCE_GEMINI_SCENES,
            lang = "my",
            lines = (1..10).map { TranscriptLine(it * 1000L, (it + 1) * 1000L, "A".repeat(100)) }
        )
        val lines = tx.linesInRange(1000, 11000, maxChars = 350)
        // 100 chars each; first 3 = 300 ≤ 350, 4th would push to 400 > 350
        assertEquals(3, lines.size)
    }

    @Test
    fun linesInRange_emptyRange_returnsEmpty() {
        val tx = Transcript(
            source = Transcript.SOURCE_SUBTITLE,
            lang = "my",
            lines = listOf(TranscriptLine(1000, 2000, "Hello"))
        )
        assertEquals(emptyList(), tx.linesInRange(5000, 9000))
    }

    @Test
    fun transcript_schemaVersion_isTwo() {
        val tx = Transcript(source = Transcript.SOURCE_SUBTITLE)
        assertEquals(2, tx.schemaVersion)
    }
}
