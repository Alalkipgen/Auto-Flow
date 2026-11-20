package io.github.alalkipgen.autoflow.core.subtitles

import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SubtitleChecksTest {
    private val cfg = ToolsConfig.Subtitles()
    private fun cue(id: String, start: Long, end: Long, text: String, my: String? = null, from: String? = my?.let { myHash(text) }) =
        SubtitleCue(id, start, end, text, textMy = my, myFrom = from)
    private fun doc(vararg cues: SubtitleCue) = SubtitleDoc(source = "srt", nextId = cues.size + 1, cues = cues.toList())

    @Test
    fun eachIssueIsFoundAndCleanLinesAreLeftOut() {
        val d = doc(
            cue("c1", 0, 2_000, "Fine line."),
            cue("c2", 2_000, 3_500, "Overlaps the next"),
            cue("c3", 3_000, 3_500, "Short"),
            cue("c4", 4_000, 6_000, "This one line is far longer than forty-two characters"),
            cue("c5", 6_000, 8_000, "one\ntwo\nthree"),
            cue("c6", 8_000, 10_000, "  "),
        )
        assertEquals(mapOf(
            "c2" to setOf(CueIssue.OVERLAP),
            "c3" to setOf(CueIssue.TOO_SHORT),
            "c4" to setOf(CueIssue.LINE_TOO_LONG),
            "c5" to setOf(CueIssue.TOO_MANY_LINES),
            "c6" to setOf(CueIssue.EMPTY),
        ), SubtitleChecks.check(d, cfg))
        assertEquals(42, SubtitleChecks.width("x".repeat(21) + "   " + "y".repeat(20)))
    }

    @Test
    fun translationChecksStartOnceSomeLineHasBurmese() {
        val none = doc(cue("c1", 0, 2_000, "Hello"), cue("c2", 2_000, 4_000, "Bye"))
        assertEquals(emptyMap(), SubtitleChecks.check(none, cfg))
        val some = doc(cue("c1", 0, 2_000, "Hello", my = "မင်္ဂလာပါ"), cue("c2", 2_000, 4_000, "Bye"),
            cue("c3", 4_000, 6_000, "Changed", my = "ပြောင်းပြီ", from = myHash("Before")))
        assertEquals(mapOf("c2" to setOf(CueIssue.NOT_TRANSLATED), "c3" to setOf(CueIssue.ORIGINAL_CHANGED)), SubtitleChecks.check(some, cfg))
    }

    @Test
    fun lengthFollowsTheTextOnScreen() {
        val longMy = "ဒီည ထွက်သွားရမယ်။ ".repeat(4).trim()
        val d = doc(cue("c1", 0, 2_000, "Short", my = longMy))
        assertEquals(emptyMap(), SubtitleChecks.check(d, cfg, TextChoice.ORIGINAL))
        assertEquals(setOf(CueIssue.LINE_TOO_LONG), SubtitleChecks.check(d, cfg, TextChoice.BURMESE)["c1"])
        assertEquals(setOf(CueIssue.LINE_TOO_LONG), SubtitleChecks.check(d, cfg, TextChoice.BOTH)["c1"])
    }

    @Test
    fun autoFixTrimsOverlapsAndDropsEmptyLinesOnly() {
        val d = doc(cue("c1", 0, 2_500, "A"), cue("c2", 2_000, 3_000, ""), cue("c3", 2_200, 4_000, "C"), cue("c4", 2_200, 4_500, "D"))
        val fixed = SubtitleChecks.autoFix(d)
        assertEquals(listOf(Triple("c1", 0L, 2_200L), Triple("c3", 2_200L, 4_000L), Triple("c4", 2_200L, 4_500L)),
            fixed.cues.map { Triple(it.id, it.startMs, it.endMs) }) // same start: left for the owner
        val clean = doc(cue("c1", 0, 1_000, "A"))
        assertSame(clean, SubtitleChecks.autoFix(clean))
    }

    @Test
    fun exportStopsOnlyForNoLinesOrBadTimes() {
        assertEquals(setOf(ExportBlock.NO_LINES), SubtitleChecks.exportBlocks(doc()))
        assertEquals(setOf(ExportBlock.BAD_TIMES), SubtitleChecks.exportBlocks(doc(cue("c1", 1_000, 1_000, "x"))))
        assertTrue(SubtitleChecks.exportBlocks(doc(cue("c1", 0, 300, ""), cue("c2", 100, 900, "y"))).isEmpty()) // warnings only
    }
}
