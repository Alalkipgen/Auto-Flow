package io.github.alalkipgen.autoflow.core.subtitles

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A name kept the same in every line: original → Burmese (D-81 names list). */
@Serializable
@SerialName("TranslateName")
data class TranslateName(val from: String, val my: String)

/**
 * What a SUB_TRANSLATE job was asked (written once at enqueue, `.cache/jobs/<job>/request.json`): the `st` version it
 * read ([base], immutable), the options, and every batch of cue ids (item `b<first id>`).
 */
@Serializable
@SerialName("TranslateRequest")
data class TranslateRequest(
    val base: String,
    val style: String,
    val names: List<TranslateName> = emptyList(),
    val replaceEdited: Boolean = false,
    val contextLines: Int = 5,
    val batches: List<List<String>>,
)

/** The Burmese of one line and the [myFrom] stamp of the original it was made from. */
@Serializable
@SerialName("TranslatedLine")
data class TranslatedLine(val id: String, val textMy: String, val myFrom: String)

/** A saved batch (`b<id>.json`): its lines, or why the model's answer could not be used ([failed]; the batch failed alone). */
@Serializable
@SerialName("TranslatedBatch")
data class TranslatedBatch(val lines: List<TranslatedLine> = emptyList(), val failed: String? = null)

/** Which lines Translate sends and how its answer joins a version (specs/quick-tools.md §5 Translate, §7, D-81); pure. */
object SubtitleTranslate {
    /**
     * Lines with text that have no Burmese for their current original ("Only new or changed lines"). A line whose
     * Burmese the owner typed is skipped unless [replaceEdited] ("Also replace my edits") — then it is sent again.
     */
    fun todo(doc: SubtitleDoc, replaceEdited: Boolean): List<SubtitleCue> = SubtitleOps.sort(doc).cues.filter { c ->
        c.text.isNotBlank() && if (c.myEdited) replaceEdited else c.textMy.isNullOrBlank() || c.myFrom != myHash(c.text)
    }

    fun batches(todo: List<SubtitleCue>, batchLines: Int): List<List<String>> = todo.map { it.id }.chunked(batchLines.coerceAtLeast(1))

    fun item(batch: List<String>): String = "b${batch.first()}"

    /** Up to [n] lines just before cue [firstId] in time order (their original and Burmese) — context, never sent to change. */
    fun context(doc: SubtitleDoc, firstId: String, n: Int): List<SubtitleCue> {
        val cues = SubtitleOps.sort(doc).cues
        val at = cues.indexOfFirst { it.id == firstId }.takeIf { it >= 0 } ?: return emptyList()
        return cues.subList(maxOf(0, at - n), at)
    }

    /**
     * [lines] put into [doc]: a line gets its Burmese only while its original is still the one translated (same
     * `myFrom`); `myEdited` is cleared. Returns the new document and how many lines changed.
     */
    fun apply(doc: SubtitleDoc, lines: List<TranslatedLine>): Pair<SubtitleDoc, Int> {
        val byId = lines.associateBy { it.id }
        var n = 0
        val cues = doc.cues.map { c ->
            val t = byId[c.id]?.takeIf { it.myFrom == myHash(c.text) } ?: return@map c
            n++
            c.copy(textMy = t.textMy, myFrom = t.myFrom, myEdited = false)
        }
        return doc.copy(cues = cues) to n
    }
}
