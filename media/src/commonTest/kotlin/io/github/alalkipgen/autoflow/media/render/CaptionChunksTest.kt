package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.recap.SrtCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Recap captions in short timed chunks (phone test v0.1.14, fix 1): at most two lines on screen at a time. */
class CaptionChunksTest {
    private fun squash(s: String) = s.filterNot { it.isWhitespace() }

    private fun assertLinesFit(chunks: List<String>, maxChars: Int) {
        chunks.forEach { c ->
            val lines = c.split('\n')
            assertTrue(lines.size <= 2, "more than two lines: $c")
            lines.forEach { l ->
                assertTrue(l.length <= maxChars, "too long (${l.length}): $l")
                assertFalse(l.isBlank())
                assertFalse(l.first() in '\u102B'..'\u103E', "line starts with a combining mark: $l")
                assertFalse(l.last() == '\u1039', "line ends with a virama: $l")
            }
        }
    }

    @Test
    fun shortTextStaysOneCue() {
        val cue = SrtCue(200, 5_200, "ကောင်လေး")
        assertEquals(listOf(cue), CaptionChunks.split(listOf(cue), 22))
    }

    @Test
    fun longBurmeseNarrationBecomesTwoLineChunksOverTheSameTime() {
        val text = "ဒီဇာတ်ကားမှာ ကောင်လေးဟာ သူ့သူငယ်ချင်းကို ရှာဖို့ မြို့ကြီးဆီ ခရီးထွက်ခဲ့ပါတယ်။ " +
            "လမ်းမှာ သူဟာ အန္တရာယ်တွေ အများကြီးနဲ့ ကြုံတွေ့ခဲ့ရပြီး၊ နောက်ဆုံးမှာတော့ သူငယ်ချင်းကို ပြန်တွေ့ခဲ့ပါတယ်။"
        val out = CaptionChunks.split(listOf(SrtCue(1_000, 13_000, text)), 22)
        assertTrue(out.size >= 4, "chunks: ${out.size}")
        assertLinesFit(out.map { it.text }, 22)
        assertEquals(1_000, out.first().startMs)
        assertEquals(13_000, out.last().endMs)
        out.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs, "back to back") }
        assertTrue(out.all { it.endMs > it.startMs })
        assertEquals(squash(text), squash(out.joinToString("") { it.text }), "no text lost or changed")
        assertTrue(out.any { it.text.endsWith("ပါတယ်။") && "လမ်းမှာ" !in it.text }, "the second sentence starts a new chunk")
    }

    @Test
    fun burmeseWithoutSpacesIsCutOnlyBetweenSyllables() {
        val text = "ကျွန်တော်တို့ဟာအင်္ဂလိပ်စာကိုလေ့လာနေကြပြီးမြန်မာစာကိုလည်းဆက်လက်လေ့လာနေကြပါတယ်"
        val chunks = CaptionChunks.chunks(text, 22)
        assertLinesFit(chunks, 22)
        assertEquals(text, chunks.joinToString("") { it.replace("\n", "") })
        // Kinzi (င်္) and asat endings stay whole.
        val lines = chunks.flatMap { it.split('\n') }
        assertTrue(lines.none { it.startsWith("္") || it.startsWith("်") })
    }

    @Test
    fun syllableBoundaries() {
        val s = "ကျွန်တော်"
        // က|ျွ န် တော်: only before တ.
        val starts = s.indices.filter { CaptionChunks.isSyllableStart(s, it) }
        assertEquals(listOf(s.indexOf('တ')), starts)
        val stacked = "မန္တလေး"
        assertFalse(CaptionChunks.isSyllableStart(stacked, stacked.indexOf('တ')), "stacked under the virama")
        assertTrue(CaptionChunks.isSyllableStart(stacked, stacked.indexOf('လ')))
    }

    @Test
    fun latinWordsAreNotCutAndClauseMarksArePreferred() {
        val text = "The hero walks into the city, looking for his old friend."
        val chunks = CaptionChunks.chunks(text, 30)
        assertLinesFit(chunks, 30)
        val lines = chunks.flatMap { it.split('\n') }
        assertEquals(listOf("The hero walks into the city,", "looking for his old friend."), lines)
    }

    @Test
    fun sentencesAndShortOnesShareAChunk() {
        assertEquals(listOf("ဟုတ်ကဲ့။", "ဒါပေမဲ့ 3.5 နာရီကြာတယ်။"), CaptionChunks.sentences("ဟုတ်ကဲ့။ ဒါပေမဲ့ 3.5 နာရီကြာတယ်။"))
        assertEquals(listOf("ဟုတ်ကဲ့။\nဒါပေမဲ့ 3.5 နာရီကြာတယ်။"), CaptionChunks.chunks("ဟုတ်ကဲ့။ ဒါပေမဲ့ 3.5 နာရီကြာတယ်။", 28))
    }

    @Test
    fun chunkEdgesTimeTheChunksInTakeTime() {
        val text = "The hero walks into the city. He looks for his old friend. Nobody knows him there."
        val chunks = CaptionChunks.chunks(text, 20)
        assertTrue(chunks.size >= 2, chunks.toString())
        // One edge per chunk start plus the last end: each chunk exactly there.
        val edges = (0..chunks.size).map { 100L + it * 700L }
        val exact = CaptionChunks.timed(text, 10_000, edges, 20)
        assertEquals(chunks, exact.map { it.text })
        assertEquals(edges.zipWithNext(), exact.map { it.startMs to it.endMs })
        // Just [start, end]: the chunks share that window, one after the other.
        val window = CaptionChunks.timed(text, 10_000, listOf(500L, 4_500L), 20)
        assertEquals(500L, window.first().startMs)
        assertEquals(4_500L, window.last().endMs)
        assertTrue(window.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
        // Invalid edges (past the take, not increasing, too few): automatic over the whole take.
        val automatic = CaptionChunks.timed(text, 3_000, null, 20)
        assertEquals(0L to 3_000L, automatic.first().startMs to automatic.last().endMs)
        for (bad in listOf(listOf(500L, 4_500L), listOf(900L, 400L), listOf(100L), listOf(-1L, 800L))) {
            assertEquals(automatic, CaptionChunks.timed(text, 3_000, bad, 20), bad.toString())
        }
    }
}
