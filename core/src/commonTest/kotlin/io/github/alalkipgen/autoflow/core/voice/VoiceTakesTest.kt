package io.github.alalkipgen.autoflow.core.voice

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.LexiconEntry
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import kotlin.test.*

class VoiceTakesTest {
    private val lock = VoiceLock("gemini", "tts-model", "Charon", "my")
    private fun voiced(v: VoiceTool) = v.copy(parts = v.parts.mapIndexed { i, p -> p.copy(take = ArtifactId("au_take$i")) }, joined = ArtifactId("au_joined"))

    @Test fun editingOnePartKeepsTheOtherTakesAndGivesTheNewPartAFreshId() {
        val first = VoiceTakes.plan(VoiceTool(text = "First one.\nSecond.\nThird one.", lock = lock), 10, Lexicon.EMPTY)
        assertEquals(listOf("p1", "p2", "p3"), first.parts.map { it.id })
        val done = voiced(first)
        val edited = VoiceTakes.plan(done.copy(text = "First one.\nSecond 2.\nThird one."), 10, Lexicon.EMPTY)
        assertEquals(listOf("p1", "p4", "p3"), edited.parts.map { it.id })
        assertEquals(listOf(ArtifactId("au_take0"), null, ArtifactId("au_take2")), edited.parts.map { it.take })
        assertNull(edited.joined, "the joined voice no longer matches")
        assertEquals(1, VoiceTakes.unvoiced(edited, 10, Lexicon.EMPTY))
        assertEquals(done, VoiceTakes.plan(done, 10, Lexicon.EMPTY), "unchanged text keeps every take and the joined voice")
    }

    @Test fun voiceToneAndLexiconAreInTheKey() {
        val done = voiced(VoiceTakes.plan(VoiceTool(text = "AF rocks.", lock = lock), 500, Lexicon.EMPTY))
        for (changed in listOf(done.copy(lock = lock.copy(voice = "Kore")), done.copy(lock = lock.copy(model = "other")), done.copy(tone = "news")))
            assertTrue(VoiceTakes.plan(changed, 500, Lexicon.EMPTY).parts.single().take == null, "$changed")
        val lexicon = Lexicon(listOf(LexiconEntry("AF", "Auto Flow")))
        assertEquals(1, VoiceTakes.unvoiced(done, 500, lexicon))
        assertEquals(0, VoiceTakes.unvoiced(done, 500, Lexicon(listOf(LexiconEntry("XYZ", "x")))), "an entry that matches nothing changes nothing")
        assertNotEquals(VoiceTakes.key("a", lock, null), VoiceTakes.key("a", lock, "calm"))
        assertEquals("Auto Flow rocks.", VoiceTakes.spoken(" AF rocks. ", lexicon))
    }

    @Test fun repeatedAndMovedPartsKeepTheirTakes() {
        val done = voiced(VoiceTakes.plan(VoiceTool(text = "Yes.\nNo.\nYes.", lock = lock), 6, Lexicon.EMPTY))
        val moved = VoiceTakes.plan(done.copy(text = "No.\nYes.\nYes.\nMaybe."), 6, Lexicon.EMPTY)
        assertEquals(listOf("p2", "p1", "p3", "p4"), moved.parts.map { it.id })
        assertEquals(listOf(true, true, true, false), moved.parts.map { it.take != null })
        assertEquals(emptyList(), VoiceTakes.plan(done.copy(text = "  "), 6, Lexicon.EMPTY).parts)
    }
}
