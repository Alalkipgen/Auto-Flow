package io.github.alalkipgen.autoflow.core.dubbing

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.DubTake
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.LexiconEntry
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import kotlin.test.*

/** D-2 (specs/dubbing.md §4–§5): every fit branch and its edges; take keys bound to the spoken line, lock and tone. */
class DubFitTest {
    @Test fun eachBranchAndItsBoundary() {
        // line 2 000 ms, slot 3 000 ms
        assertEquals(DubFit(DubFitKind.FITS), DubFit.of(1_000, 2_000, 3_000, 1.0))
        assertEquals(DubFitKind.FITS, DubFit.of(2_000, 2_000, 3_000, 1.0).kind, "exactly the line fits")
        assertEquals(DubFit(DubFitKind.USES_PAUSE), DubFit.of(2_001, 2_000, 3_000, 1.0))
        assertEquals(DubFitKind.USES_PAUSE, DubFit.of(3_000, 2_000, 3_000, 1.0).kind, "exactly the slot uses the pause")
        assertEquals(DubFit(DubFitKind.TOO_LONG, overMs = 1), DubFit.of(3_001, 2_000, 3_000, 1.0), "1.0× max: never FASTER (Q-42)")
        assertEquals(DubFit(DubFitKind.TOO_LONG, overMs = 2_000), DubFit.of(5_000, 2_000, 3_000, 1.0))
    }

    @Test fun fasterOnlyUpToTheMaxSpeedInWholeHundredths() {
        assertEquals(DubFit(DubFitKind.FASTER, 1.1), DubFit.of(3_300, 2_000, 3_000, 1.25), "1.10× exactly, no float round-up")
        assertEquals(DubFit(DubFitKind.FASTER, 1.01), DubFit.of(3_001, 2_000, 3_000, 1.25), "ceil to hundredths")
        assertEquals(DubFit(DubFitKind.FASTER, 1.25), DubFit.of(3_750, 2_000, 3_000, 1.25), "exactly the max")
        assertEquals(DubFit(DubFitKind.TOO_LONG, overMs = 1), DubFit.of(3_751, 2_000, 3_000, 1.25), "3 751 / 1.25 = 3 000.8 → 1 ms over")
        assertEquals(DubFitKind.TOO_LONG, DubFit.of(100, 0, 0, 1.25).kind, "no slot")
        assertEquals(DubFit(DubFitKind.TOO_LONG, overMs = 1), DubFit.of(3_001, 2_000, 3_000, 0.5), "a max below 1.0 counts as 1.0")
    }

    private val lock = VoiceLock("gemini", "tts-a", "Kore", "my")
    private val lex = Lexicon(listOf(LexiconEntry("AF", "Auto Flow")))
    private val doc = SubtitleDoc(source = "srt", nextId = 3, cues = listOf(
        SubtitleCue("c1", 0, 1_500, "Hi.", textMy = "AF ပါ။"),
        SubtitleCue("c2", 2_000, 4_000, "Bye.", textMy = "သွားပြီ။"),
    ))

    @Test fun takeKeysFollowTheSpokenLineLexiconLockAndTone() {
        assertEquals("Auto Flow ပါ။", DubRules.sayText(doc.cues[0], lex))
        val k1 = DubRules.takeKey(doc.cues[0], lex, lock, "news")
        val take = DubTake(ArtifactId("au_000000000001"), k1, 900)
        val tool = DubbingTool(lock = lock, tone = "news", takes = mapOf("c1" to take))
        assertEquals(take, DubRules.currentTake(tool, doc.cues[0], lex))
        assertEquals(listOf("c2"), DubRules.toSpeak(tool, doc, lex), "a line with a current take is never re-spoken")
        assertNull(DubRules.currentTake(tool.copy(tone = "calm"), doc.cues[0], lex), "tone changed")
        assertNull(DubRules.currentTake(tool.copy(lock = lock.copy(voice = "Puck")), doc.cues[0], lex), "voice changed")
        assertNull(DubRules.currentTake(tool.copy(lock = null), doc.cues[0], lex), "no lock")
        assertNull(DubRules.currentTake(tool, doc.cues[0], Lexicon.EMPTY), "the Lexicon changed what is said")
        val edited = DubRules.setSpoken(doc, "c1", "AF ပါခင်ဗျာ။")
        assertEquals(listOf("c1", "c2"), DubRules.toSpeak(tool, edited, lex), "a changed spoken line needs a new take")
        val json = CoreJson.encodeToString(DubbingTool.serializer(), tool)
        assertEquals(tool, CoreJson.decodeFromString(DubbingTool.serializer(), json))
    }
}
