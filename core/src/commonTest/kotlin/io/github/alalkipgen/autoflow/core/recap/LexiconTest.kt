package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Lexicon and spoken text (5B-7, studio-v2 §2). */
class LexiconTest {
    private val lexicon = Lexicon(
        listOf(
            LexiconEntry("NASA", "နာဆာ"),
            LexiconEntry("NASA HQ", "နာဆာ ဌာနချုပ်"),
            LexiconEntry("A", "AB"),
            LexiconEntry("", "ignored"),
        ),
    )

    @Test
    fun longestEntryWinsAndReplacedTextIsNotScannedAgain() {
        assertEquals("နာဆာ ဌာနချုပ် နဲ့ နာဆာ", lexicon.apply("NASA HQ နဲ့ NASA"))
        assertEquals("ABAB", lexicon.apply("AA"))
        assertEquals("ဘာမှမပြောင်း", lexicon.apply("ဘာမှမပြောင်း"))
        assertEquals("", lexicon.apply(""))
    }

    @Test
    fun speakAsWinsOverTheCaptionText() {
        val block = RecapBlock("b_1", listOf(RecapClip(0, 1000)), textMy = "NASA က ပြောတယ်", speakMy = " NASA HQ က ပြောတယ် ")
        assertEquals("နာဆာ ဌာနချုပ် က ပြောတယ်", SpokenText.of(block, lexicon))
        assertEquals("NASA က ပြောတယ်", SpokenText.of(block.copy(speakMy = "  ")))
    }

    @Test
    fun hashMatchesV1SoMigratedTakesStayCurrent() {
        val block = RecapBlock("b_1", listOf(RecapClip(0, 1000)), textMy = "  ညမှာ ရထား ထွက်တယ်။ ")
        assertEquals(RecapRules.textHash(block.textMy), SpokenText.hash(block))
    }

    @Test
    fun lexiconOrSpeakAsChangeMakesTakesStale() {
        val block = RecapBlock("b_1", listOf(RecapClip(0, 1000)), textMy = "NASA က ပြောတယ်")
        val take = BlockTake("b_1", ArtifactId("au_0123456789ab"), TakeSource.TTS, durationMs = 1000, spokenHash = SpokenText.hash(block))
        assertFalse(SpokenText.isStale(take, block))
        assertTrue(SpokenText.isStale(take, block, lexicon))
        assertTrue(SpokenText.isStale(take, block.copy(speakMy = "နာဆာ က ပြောတယ်")))
    }
}
