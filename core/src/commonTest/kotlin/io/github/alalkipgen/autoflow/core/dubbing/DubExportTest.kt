package io.github.alalkipgen.autoflow.core.dubbing

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.DubTake
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import kotlin.test.*

/** D-3a (specs/dubbing.md §2 Export pre-flight, §6): what blocks Export and the lines the mix gets. */
class DubExportTest {
    private val lex = Lexicon.EMPTY
    private val lock = VoiceLock("gemini", "tts-a", "Kore", "my")
    private val doc = SubtitleDoc(source = "srt", nextId = 4, cues = listOf(
        SubtitleCue("c1", 1_000, 2_000, "One.", textMy = "တစ်။"),
        SubtitleCue("c2", 3_000, 4_000, "Two.", textMy = "နှစ်။"),
        SubtitleCue("c3", 6_000, 7_000, "Three.", textMy = "သုံး။"),
    ))
    private fun take(id: String, ms: Long, lead: Long = 0) =
        DubTake(ArtifactId("au_${id.padStart(16, '0')}"), DubRules.takeKey(doc.cues.first { it.id == id }, lex, lock, null), ms, lead)
    private fun tool(takes: Map<String, DubTake>, approved: Boolean = true): DubbingTool {
        val t = DubbingTool(lock = lock, takes = takes)
        return if (approved) DubRules.approve(t, 10_000, 600_000, doc, 1)!! else t
    }
    private val all = mapOf("c1" to take("c1", 900, lead = 120), "c2" to take("c2", 1_800), "c3" to take("c3", 2_500))

    @Test fun everyLineNeedsACurrentTakeThatFitsAndAnApprovedScript() {
        val ok = DubExport.check(tool(all), doc, 10_000, 100, 1.0, lex)
        assertTrue(ok.ok)
        assertEquals(DubExportCheck(false, emptyList(), emptyList()), DubExport.check(tool(all, approved = false), doc, 10_000, 100, 1.0, lex))
        assertEquals(listOf("c2"), DubExport.check(tool(all - "c2"), doc, 10_000, 100, 1.0, lex).noVoice, "missing take")
        val old = all + ("c3" to all.getValue("c3").copy(key = "old"))
        assertEquals(listOf("c3"), DubExport.check(tool(old), doc, 10_000, 100, 1.0, lex).noVoice, "a take for older text or voice")
        // c2's slot is 3 000 → 5 900 = 2 900 ms; 3 000 ms is too long at 1.0×, fits at 1.10× (FASTER)
        val long = all + ("c2" to take("c2", 3_000))
        assertEquals(listOf("c2"), DubExport.check(tool(long), doc, 10_000, 100, 1.0, lex).tooLong)
        assertTrue(DubExport.check(tool(long), doc, 10_000, 100, 1.1, lex).ok)
    }

    @Test fun linesStartAtTheirLineAndKeepTheirSlotLeadAndSpeed() {
        val lines = assertNotNull(DubExport.lines(tool(all + ("c2" to take("c2", 3_000))), doc, 10_000, 100, 1.1, lex))
        assertEquals(listOf(
            DubMixLine("c1", all.getValue("c1").au, 1_000, 120, 1_900, 1.0),
            DubMixLine("c2", take("c2", 0).au, 3_000, 0, 2_900, 1.04),
            DubMixLine("c3", all.getValue("c3").au, 6_000, 0, 4_000, 1.0),
        ), lines)
        assertNull(DubExport.lines(tool(all - "c1"), doc, 10_000, 100, 1.0, lex))
    }

    @Test fun thePreviewPlacesEveryCurrentTakeBeforeApprovalOrTheLastTake() {
        // F-6 (D-95): not approved and c1 not spoken yet — Export is blocked, the preview still plays c2 and c3 where the mix would.
        val partial = tool(all - "c1", approved = false)
        assertNull(DubExport.lines(partial, doc, 10_000, 100, 1.0, lex))
        assertEquals(listOf(
            DubMixLine("c2", all.getValue("c2").au, 3_000, 0, 2_900, 1.0),
            DubMixLine("c3", all.getValue("c3").au, 6_000, 0, 4_000, 1.0),
        ), DubExport.voiced(partial, doc, 10_000, 100, 1.0, lex))
        assertEquals(DubExport.lines(tool(all), doc, 10_000, 100, 1.0, lex), DubExport.voiced(tool(all), doc, 10_000, 100, 1.0, lex))
        assertEquals(emptyList(), DubExport.voiced(null, doc, 10_000, 100, 1.0, lex))
    }
}
