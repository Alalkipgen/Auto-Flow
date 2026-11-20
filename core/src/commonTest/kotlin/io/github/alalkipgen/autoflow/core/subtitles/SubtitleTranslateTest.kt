package io.github.alalkipgen.autoflow.core.subtitles

import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** S-6 (D-81): which lines are sent, batches and context, and how the Burmese joins a version. */
class SubtitleTranslateTest {
    private fun cue(id: String, start: Long, text: String, my: String? = null, from: String? = my?.let { myHash(text) }, edited: Boolean = false) =
        SubtitleCue(id, start, start + 1_000, text, textMy = my, myFrom = from, myEdited = edited)

    private val doc = SubtitleDoc(source = "srt", lang = "en", nextId = 8, cues = listOf(
        cue("c2", 2_000, "Fresh", my = "လတ်"),                                // translated, unchanged → skipped
        cue("c1", 1_000, "New"),                                              // no Burmese → sent (sorted first)
        cue("c3", 3_000, "Changed", my = "ဟောင်း", from = "00000000"),        // original changed → sent
        cue("c4", 4_000, "Mine", my = "ကိုယ်တိုင်", edited = true),            // hand-edited → only with replaceEdited
        cue("c5", 5_000, "   "),                                              // empty → never
        cue("c6", 6_000, "Mine changed", my = "x", from = "11111111", edited = true),
        cue("c7", 7_000, "Blank my", my = ""),
    ))

    @Test fun onlyNewOrChangedLinesAndEditsOnlyWhenAsked() {
        assertEquals(listOf("c1", "c3", "c7"), SubtitleTranslate.todo(doc, replaceEdited = false).map { it.id })
        assertEquals(listOf("c1", "c3", "c4", "c6", "c7"), SubtitleTranslate.todo(doc, replaceEdited = true).map { it.id })
    }

    @Test fun batchesKeysAndContext() {
        val todo = SubtitleTranslate.todo(doc, replaceEdited = true)
        val batches = SubtitleTranslate.batches(todo, 2)
        assertEquals(listOf(listOf("c1", "c3"), listOf("c4", "c6"), listOf("c7")), batches)
        assertEquals(listOf("bc1", "bc4", "bc7"), batches.map(SubtitleTranslate::item))
        assertEquals(listOf("c2", "c3"), SubtitleTranslate.context(doc, "c4", 2).map { it.id })
        assertEquals(emptyList(), SubtitleTranslate.context(doc, "c1", 5))
        assertEquals(emptyList(), SubtitleTranslate.context(doc, "gone", 5))
        assertEquals(listOf(listOf("c1"), listOf("c3")), SubtitleTranslate.batches(todo.take(2), 0), "at least one line per batch")
    }

    @Test fun applySetsBurmeseOnlyWhereTheOriginalIsStillTheSame() {
        val edited = doc.copy(cues = doc.cues.map { if (it.id == "c3") it.copy(text = "Changed again") else it })
        val (out, n) = SubtitleTranslate.apply(edited, listOf(
            TranslatedLine("c1", "အသစ်", myHash("New")),
            TranslatedLine("c3", "ပြောင်း", myHash("Changed")),         // the original changed meanwhile → kept as is
            TranslatedLine("c4", "ပြန်ပြန်", myHash("Mine")),
            TranslatedLine("gone", "x", myHash("x")),
        ))
        assertEquals(2, n)
        val byId = out.cues.associateBy { it.id }
        assertEquals(SubtitleCue("c1", 1_000, 2_000, "New", "အသစ်", myHash("New"), false), byId["c1"])
        assertEquals("ဟောင်း", byId["c3"]!!.textMy)
        assertEquals(false, byId["c4"]!!.myEdited)
        assertEquals("ပြန်ပြန်", byId["c4"]!!.textMy)
        assertEquals(doc.cues.map { it.id }, out.cues.map { it.id }, "ids and order never change")
    }

    @Test fun requestAndBatchFilesRoundTripAndTheJobKindIsStable() {
        val r = TranslateRequest("st_1", "formal", listOf(TranslateName("Mira", "မီရာ")), true, 5, listOf(listOf("c1", "c3")))
        assertEquals(r, CoreJson.decodeFromString(TranslateRequest.serializer(), CoreJson.encodeToString(TranslateRequest.serializer(), r)))
        val b = TranslatedBatch(failed = "unusable twice")
        assertEquals(b, CoreJson.decodeFromString(TranslatedBatch.serializer(), CoreJson.encodeToString(TranslatedBatch.serializer(), b)))
        val job = AiJob("st-p-1", AiJobKind.SUB_TRANSLATE, "/p", listOf("bc1"))
        assertEquals("\"SUB_TRANSLATE\"", CoreJson.encodeToString(AiJobKind.serializer(), job.kind))
    }
}
