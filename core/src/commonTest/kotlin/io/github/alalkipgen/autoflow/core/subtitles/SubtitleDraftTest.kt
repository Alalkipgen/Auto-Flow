package io.github.alalkipgen.autoflow.core.subtitles

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.recap.SrtCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SubtitleDraftTest {
    private val base = ArtifactId("st_0123456789ab")
    private val saved = SubtitleOps.fromCues(listOf(SrtCue(0, 2_000, "ဒီည ထွက်သွားရမယ်။ မနက်ဖြန် ပြန်လာမယ်။"), SrtCue(3_000, 5_000, "Two")), "srt")

    @Test
    fun undoRedoAndDirty() {
        var d = SubtitleDraft.of(base, saved)
        assertFalse(d.dirty || d.canUndo || d.canRedo)
        d = d.apply { SubtitleOps.split(it, "c1", 1_000, textCut = "ဒီည ထွက်သွားရမယ်။".length) }.first
        assertEquals(listOf("ဒီည ထွက်သွားရမယ်။", "မနက်ဖြန် ပြန်လာမယ်။"), d.doc.cues.take(2).map { it.text })
        d = d.apply { SubtitleOps.merge(it, "c1") }.first
        assertEquals("ဒီည ထွက်သွားရမယ်။\nမနက်ဖြန် ပြန်လာမယ်။", d.doc.cues.first().text) // merged with a line break
        assertTrue(d.dirty)
        d = d.undo()
        assertEquals(3, d.doc.cues.size)
        d = d.undo()
        assertEquals(saved, d.doc)
        assertFalse(d.dirty)
        assertTrue(d.canRedo)
        d = d.redo().redo()
        assertEquals(2, d.doc.cues.size)
        assertFalse(d.canRedo)
    }

    @Test
    fun refusedAndNoOpEditsLeaveHistoryAlone() {
        val d = SubtitleDraft.of(base, saved)
        val (same, why) = d.apply { SubtitleOps.setTimes(it, "c1", 5_000, 1_000) }
        assertSame(d, same)
        assertEquals(EditRefusal.BadTimes, why)
        val (noop, none) = d.apply { Edit.Done(it) }
        assertSame(d, noop)
        assertNull(none)
    }

    @Test
    fun onlyTheLastFiftyStepsCanBeUndone() {
        var d = SubtitleDraft.of(base, saved)
        repeat(60) { d = d.apply { doc -> SubtitleOps.shift(doc, 10) }.first }
        var undone = 0
        while (d.canUndo) { d = d.undo(); undone++ }
        assertEquals(SubtitleDraft.LIMIT, undone)
        assertEquals(100L, d.doc.cues.first().startMs) // 10 steps of 10 ms are past the limit
    }

    @Test
    fun theDraftFileRestoresTheEditsAndSavesAsAnEdit() {
        val edited = SubtitleDraft.of(base, saved).apply { SubtitleOps.delete(it, "c2") }.first
        val text = CoreJson.encodeToString(DraftFile.serializer(), edited.toFile(savedAtMs = 1_700_000_000_000))
        val file = CoreJson.decodeFromString(DraftFile.serializer(), text)
        val back = SubtitleDraft.restore(file, saved)
        assertEquals(edited.doc, back.doc)
        assertTrue(back.dirty && !back.canUndo)
        assertEquals(SubtitleDoc.SOURCE_EDIT to base, back.toSave().let { it.source to it.derivedFrom })
    }
}
