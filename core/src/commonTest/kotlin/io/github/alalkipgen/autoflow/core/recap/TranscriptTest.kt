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

    // --- 5B-1 scene transcription --------------------------------------------------------------------------------

    @Test
    fun covers_usesTheUnionOfTranscribedRanges() {
        val tx = Transcript(source = Transcript.SOURCE_GEMINI_SCENES, scenes = listOf(TranscriptRange(610_000, 900_000), TranscriptRange(10_000, 610_000)))
        assertTrue(tx.covers(10_000, 900_000), "a long scene sent in two parts")
        assertTrue(tx.covers(20_000, 30_000))
        assertFalse(tx.covers(0, 20_000))
        assertFalse(tx.covers(800_000, 901_000))
        assertFalse(Transcript(source = Transcript.SOURCE_GEMINI_SCENES).covers(0, 1))
    }

    @Test
    fun withScenes_replacesOnlyTheLinesOfTheRangesSentAgain() {
        val old = Transcript(
            source = Transcript.SOURCE_GEMINI_SCENES,
            lines = listOf(TranscriptLine(1_000, 2_000, "keep"), TranscriptLine(11_000, 12_000, "old")),
            scenes = listOf(TranscriptRange(0, 5_000), TranscriptRange(10_000, 15_000)),
        )
        val next = old.withScenes(listOf(TranscriptRange(10_000, 15_000), TranscriptRange(20_000, 25_000)), listOf(TranscriptLine(21_000, 22_000, "c"), TranscriptLine(13_000, 14_000, "new")))
        assertEquals(listOf("keep", "new", "c"), next.lines.map { it.text })
        assertEquals(listOf(TranscriptRange(0, 5_000), TranscriptRange(10_000, 15_000), TranscriptRange(20_000, 25_000)), next.scenes)
        assertEquals("new c", next.textIn(12_000, 23_000))
        assertEquals("", next.textIn(5_000, 10_000))
    }

    @Test
    fun aTranscriptWrittenBeforeScenesExistedStillReads() {
        val json = """{"schemaVersion":2,"source":"gemini-scenes","lang":"my","lines":[{"startMs":1,"endMs":2,"text":"a"}]}"""
        val tx = io.github.alalkipgen.autoflow.core.model.CoreJson.decodeFromString(Transcript.serializer(), json)
        assertEquals(emptyList(), tx.scenes)
        assertEquals(1, tx.lines.size)
        val again = io.github.alalkipgen.autoflow.core.model.CoreJson.decodeFromString(
            Transcript.serializer(),
            io.github.alalkipgen.autoflow.core.model.CoreJson.encodeToString(Transcript.serializer(), tx.withScenes(listOf(TranscriptRange(0, 5)), emptyList())),
        )
        assertEquals(listOf(TranscriptRange(0, 5)), again.scenes)
    }
}
