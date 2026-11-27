package io.github.alalkipgen.autoflow.core.subtitles

import io.github.alalkipgen.autoflow.core.recap.SrtCue

/** Why an edit was refused; the document is unchanged. */
sealed interface EditRefusal {
    data object NoSuchLine : EditRefusal
    /** Start must be ≥ 0 and end after start. */
    data object BadTimes : EditRefusal
    /** Split time must be strictly inside the line. */
    data object SplitOutside : EditRefusal
    /** No cursor and no line break: the UI must ask where the text splits. */
    data object SplitNeedsTextCut : EditRefusal
    data object NoNextLine : EditRefusal
    /** A shift before 0:00; [largestMs] is the most negative shift allowed (≤ 0). */
    data class ShiftBeforeStart(val largestMs: Long) : EditRefusal
}

sealed interface Edit {
    data class Done(val doc: SubtitleDoc) : Edit
    data class Refused(val reason: EditRefusal) : Edit
}

enum class TextChoice { ORIGINAL, BURMESE, BOTH }

/** Pure editing operations on a [SubtitleDoc] (specs/quick-tools.md §4). Each returns a new document. */
object SubtitleOps {
    /** A first version from SubRip cues: ids `c1…cn` in time order. */
    fun fromCues(cues: List<SrtCue>, source: String, lang: String? = null): SubtitleDoc {
        val sorted = cues.sortedWith(compareBy({ it.startMs }, { it.endMs }))
        return SubtitleDoc(source = source, lang = lang, nextId = sorted.size + 1,
            cues = sorted.mapIndexed { i, c -> SubtitleCue("c${i + 1}", c.startMs, c.endMs, c.text) })
    }

    /** Stable time order (start, then end); ids unchanged. */
    fun sort(doc: SubtitleDoc): SubtitleDoc = doc.copy(cues = doc.cues.sortedWith(compareBy({ it.startMs }, { it.endMs })))

    fun setTimes(doc: SubtitleDoc, id: String, startMs: Long, endMs: Long): Edit {
        if (doc.cues.none { it.id == id }) return Edit.Refused(EditRefusal.NoSuchLine)
        if (startMs < 0 || endMs <= startMs) return Edit.Refused(EditRefusal.BadTimes)
        return Edit.Done(sort(doc.copy(cues = doc.cues.map { if (it.id == id) it.copy(startMs = startMs, endMs = endMs) else it })))
    }

    /**
     * The owner typed the original text of [id]. A Burmese line made from the old text keeps its stamp, so
     * "Original changed" shows; the same text is no change.
     */
    fun setText(doc: SubtitleDoc, id: String, text: String): Edit {
        val cue = doc.cues.firstOrNull { it.id == id } ?: return Edit.Refused(EditRefusal.NoSuchLine)
        if (cue.text == text) return Edit.Done(doc)
        return Edit.Done(doc.copy(cues = doc.cues.map { if (it.id == id) it.copy(text = text) else it }))
    }

    /** The owner typed the Burmese of [id]: marked as theirs (D-81) and stamped with the current original. Blank = none. */
    fun setTextMy(doc: SubtitleDoc, id: String, textMy: String): Edit {
        val cue = doc.cues.firstOrNull { it.id == id } ?: return Edit.Refused(EditRefusal.NoSuchLine)
        val my = textMy.takeIf { it.isNotBlank() }
        if (cue.textMy == my) return Edit.Done(doc)
        return Edit.Done(doc.copy(cues = doc.cues.map {
            if (it.id == id) it.copy(textMy = my, myFrom = my?.let { _ -> myHash(it.text) }, myEdited = my != null) else it
        }))
    }

    /**
     * Splits line [id] at [atMs] (strictly inside). Text: at [textCut] when given, else at the first line break,
     * else refused with [EditRefusal.SplitNeedsTextCut]. Burmese: at [myCut] when given, else it stays on the first
     * half (and is then flagged "Original changed"). The second half gets `c<nextId>`.
     */
    fun split(doc: SubtitleDoc, id: String, atMs: Long, textCut: Int? = null, myCut: Int? = null): Edit {
        val i = doc.cues.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return Edit.Refused(EditRefusal.NoSuchLine)
        val c = doc.cues[i]
        if (atMs <= c.startMs || atMs >= c.endMs) return Edit.Refused(EditRefusal.SplitOutside)
        val cut = textCut?.takeIf { it in 1 until c.text.length } ?: c.text.indexOf('\n').takeIf { it > 0 }
            ?: return Edit.Refused(EditRefusal.SplitNeedsTextCut)
        val my = c.textMy
        val myAt = myCut?.takeIf { my != null && it in 1 until my.length }
        val first = c.copy(endMs = atMs, text = c.text.take(cut).trim(), textMy = if (myAt != null) my!!.take(myAt).trim() else my)
        val second = SubtitleCue("c${doc.nextId}", atMs, c.endMs, c.text.drop(cut).trim(),
            textMy = myAt?.let { my!!.drop(it).trim() }, myFrom = myAt?.let { c.myFrom }, myEdited = myAt != null && c.myEdited)
        val cues = doc.cues.toMutableList().apply { this[i] = first; add(i + 1, second) }
        return Edit.Done(doc.copy(nextId = doc.nextId + 1, cues = cues))
    }

    /** Merges line [id] with the next one: first start → second end, texts joined by a line break, first id kept. */
    fun merge(doc: SubtitleDoc, id: String): Edit {
        val i = doc.cues.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return Edit.Refused(EditRefusal.NoSuchLine)
        val a = doc.cues[i]
        val b = doc.cues.getOrNull(i + 1) ?: return Edit.Refused(EditRefusal.NoNextLine)
        val text = a.text + "\n" + b.text
        val bothFresh = a.textMy != null && b.textMy != null && a.myFrom == myHash(a.text) && b.myFrom == myHash(b.text)
        val my = listOfNotNull(a.textMy, b.textMy).takeIf { it.isNotEmpty() }?.joinToString("\n")
        val merged = a.copy(endMs = maxOf(a.endMs, b.endMs), text = text, textMy = my,
            myFrom = if (bothFresh) myHash(text) else a.myFrom, myEdited = a.myEdited || b.myEdited,
            spokenMy = listOfNotNull(a.spokenMy, b.spokenMy).takeIf { it.isNotEmpty() }?.joinToString(" "))
        val cues = doc.cues.toMutableList().apply { this[i] = merged; removeAt(i + 1) }
        return Edit.Done(doc.copy(cues = cues))
    }

    /** A new empty line after [id] (or at the start when null): from that line's end, up to [lengthMs] or the next start. */
    fun insertAfter(doc: SubtitleDoc, id: String?, lengthMs: Long = 2_000): Edit {
        val i = if (id == null) -1 else doc.cues.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return Edit.Refused(EditRefusal.NoSuchLine)
        val start = if (i < 0) 0L else doc.cues[i].endMs
        val nextStart = doc.cues.getOrNull(i + 1)?.startMs
        val end = nextStart?.takeIf { it > start }?.let { minOf(it, start + lengthMs) } ?: (start + lengthMs)
        val cue = SubtitleCue("c${doc.nextId}", start, end, "")
        val cues = doc.cues.toMutableList().apply { add(i + 1, cue) }
        return Edit.Done(doc.copy(nextId = doc.nextId + 1, cues = cues))
    }

    fun delete(doc: SubtitleDoc, id: String): Edit {
        if (doc.cues.none { it.id == id }) return Edit.Refused(EditRefusal.NoSuchLine)
        return Edit.Done(doc.copy(cues = doc.cues.filterNot { it.id == id }))
    }

    /** Moves every line (or line [fromId] and all after it) by [deltaMs]; nothing may start before 0:00. */
    fun shift(doc: SubtitleDoc, deltaMs: Long, fromId: String? = null): Edit {
        val from = if (fromId == null) 0 else doc.cues.indexOfFirst { it.id == fromId }.takeIf { it >= 0 } ?: return Edit.Refused(EditRefusal.NoSuchLine)
        val moved = doc.cues.drop(from)
        val earliest = moved.minOfOrNull { it.startMs } ?: return Edit.Done(doc)
        if (earliest + deltaMs < 0) return Edit.Refused(EditRefusal.ShiftBeforeStart(-earliest))
        return Edit.Done(sort(doc.copy(cues = doc.cues.take(from) + moved.map { it.copy(startMs = it.startMs + deltaMs, endMs = it.endMs + deltaMs) })))
    }

    /** SubRip text numbered 1…n in time order. Burmese falls back to the original for an untranslated line. */
    fun toSrt(doc: SubtitleDoc, choice: TextChoice): List<SrtCue> = sort(doc).cues.map { c ->
        val text = when (choice) {
            TextChoice.ORIGINAL -> c.text
            TextChoice.BURMESE -> c.textMy?.takeIf { it.isNotBlank() } ?: c.text
            TextChoice.BOTH -> listOfNotNull(c.text, c.textMy?.takeIf { it.isNotBlank() }).joinToString("\n")
        }
        SrtCue(c.startMs, c.endMs, text)
    }
}
