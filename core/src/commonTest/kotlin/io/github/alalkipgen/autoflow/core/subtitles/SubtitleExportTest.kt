package io.github.alalkipgen.autoflow.core.subtitles

import io.github.alalkipgen.autoflow.core.recap.Srt
import io.github.alalkipgen.autoflow.core.recap.SrtCue
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleExportTest {
    private val doc = SubtitleDoc(source = "srt", nextId = 3, cues = listOf(
        SubtitleCue("c2", 3_000, 4_500, "Second", textMy = "ဒုတိယ"),
        SubtitleCue("c1", 1_000, 2_500, "First\nline"),
    ))

    @Test
    fun srtRoundTripsInTimeOrderAndBurmeseFallsBack() {
        val my = Srt.parse(SubtitleExport.srt(doc, TextChoice.BURMESE))
        assertEquals(listOf(SrtCue(1_000, 2_500, "First\nline"), SrtCue(3_000, 4_500, "ဒုတိယ")), my)
        val orig = Srt.parse(SubtitleExport.srt(doc, TextChoice.ORIGINAL))
        assertEquals(listOf("First\nline", "Second"), orig.map { it.text })
        assertEquals(SubtitleOps.toSrt(doc, TextChoice.ORIGINAL), orig)
    }

    @Test
    fun defaultIsBurmeseOnlyWhenSomeLineHasBurmese() {
        assertEquals(TextChoice.BURMESE, SubtitleExport.defaultChoice(doc))
        assertEquals(TextChoice.ORIGINAL, SubtitleExport.defaultChoice(doc.copy(cues = doc.cues.map { it.copy(textMy = " ") })))
    }

    @Test
    fun fileNameIsSafeAndNamesLanguageAndVersion() {
        assertEquals("My-film-2024-my-st0001abcd.srt", SubtitleExport.fileName("My film: 2024?", TextChoice.BURMESE, "st-0001-abcd"))
        assertEquals("ရုပ်ရှင်-orig-st1.srt", SubtitleExport.fileName("ရုပ်ရှင်", TextChoice.ORIGINAL, "st-1"))
        assertEquals("subtitles-both-draft.srt", SubtitleExport.fileName("///", TextChoice.BOTH, ""))
    }
}
