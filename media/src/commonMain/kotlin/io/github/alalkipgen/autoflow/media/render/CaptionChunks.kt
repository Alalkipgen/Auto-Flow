package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.recap.SrtCue

/**
 * Long narration → short timed captions (phone test v0.1.14, fix 1): a recap segment's whole text on screen at once
 * covered the picture. Each cue is cut into chunks of at most [MAX_LINES] lines of `maxChars`, shown one after the
 * other; each chunk's time is its share of the text, so the words on screen follow the voice roughly.
 *
 * Break points, best first: sentence end (`။ . ! ?`, or a line break in the script), clause mark (`၊ , ; :`), a
 * space, then a Burmese syllable boundary — Burmese often has no spaces, and a break is never put inside a syllable
 * (before an asat or a stacked consonant, or after a virama). Latin words are cut only when one is longer than a line.
 */
object CaptionChunks {
    const val MAX_LINES = 2

    /** [cues] with every long text split into timed chunks (texts use `\n` between their lines). */
    fun split(cues: List<SrtCue>, maxChars: Int, maxLines: Int = MAX_LINES): List<SrtCue> = cues.flatMap { cue ->
        val chunks = chunks(cue.text, maxChars, maxLines)
        if (chunks.size <= 1) return@flatMap listOf(cue.copy(text = chunks.firstOrNull() ?: cue.text.trim()))
        val weights = chunks.map { c -> c.count { !it.isWhitespace() }.coerceAtLeast(1).toLong() }
        val total = weights.sum()
        val span = cue.endMs - cue.startMs
        var done = 0L
        chunks.mapIndexed { i, text ->
            val start = cue.startMs + span * done / total
            done += weights[i]
            val end = if (i == chunks.lastIndex) cue.endMs else cue.startMs + span * done / total
            SrtCue(start, end, text)
        }
    }

    /** [text] → chunks of at most [maxLines] lines; sentences start a new chunk unless they fit with the one before. */
    fun chunks(text: String, maxChars: Int, maxLines: Int = MAX_LINES): List<String> {
        val width = maxChars.coerceAtLeast(4)
        val out = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        for (sentence in sentences(text)) {
            val lines = lines(sentence, width)
            if (cur.isNotEmpty() && cur.size + lines.size <= maxLines) { cur += lines; continue }
            if (cur.isNotEmpty()) out += cur
            cur = mutableListOf()
            lines.chunked(maxLines).forEach { group ->
                if (group.size == maxLines) out += group else cur = group.toMutableList()
            }
        }
        if (cur.isNotEmpty()) out += cur
        return out.map { it.joinToString("\n") }
    }

    /** Sentences: split after `။ . ! ?` (with any closing quotes) and at line breaks; trimmed, never empty. */
    internal fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        fun flush() { sb.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }; sb.clear() }
        var i = 0
        val t = text.replace("\r\n", "\n")
        while (i < t.length) {
            val c = t[i]
            if (c == '\n') { flush(); i++; continue }
            sb.append(c)
            if (c in SENTENCE_END) {
                while (i + 1 < t.length && t[i + 1] in CLOSERS) { sb.append(t[i + 1]); i++ }
                // "3.5" or "e.g." keeps going: a full stop ends a sentence only before a space or the end.
                if (c != '.' || i + 1 >= t.length || t[i + 1].isWhitespace()) flush()
            }
            i++
        }
        flush()
        return out
    }

    /** One sentence → lines of at most [maxChars], cut at the best break point in the second half of each line. */
    internal fun lines(sentence: String, maxChars: Int): List<String> {
        val s = sentence.replace(Regex("\\s+"), " ").trim()
        val out = mutableListOf<String>()
        var start = 0
        while (start < s.length) {
            while (start < s.length && s[start] == ' ') start++
            if (start >= s.length) break
            if (s.length - start <= maxChars) { out += s.substring(start).trim(); break }
            val limit = start + maxChars
            var best = -1
            var bestRank = -1
            for (cut in start + 1..limit) {
                val rank = breakRank(s, cut)
                if (rank < 0) continue
                // A break in the first half of the line only counts when there is nothing in the second half.
                val r = if (cut - start >= maxChars / 2) rank + 10 else rank
                if (r >= bestRank) { best = cut; bestRank = r }
            }
            val cut = if (best > start) best else hardCut(s, start, limit)
            out += s.substring(start, cut).trim()
            start = cut
        }
        return out.filter { it.isNotEmpty() }
    }

    /** How good it is to start a new line at [i] (text before it goes on the line above): -1 not allowed, 3 best. */
    internal fun breakRank(s: String, i: Int): Int {
        if (i <= 0 || i >= s.length) return -1
        val prev = s[i - 1]
        val next = s[i]
        return when {
            next == ' ' -> -1 // break after the space, so the next line does not start with one
            prev == ' ' -> {
                val before = s.getOrNull(i - 2) ?: ' '
                when (before) {
                    in SENTENCE_END, in CLOSERS -> 3
                    in CLAUSE -> 2
                    else -> 1
                }
            }
            prev in SENTENCE_END -> 3
            prev in CLAUSE -> 2
            next in SENTENCE_END || next in CLAUSE || next in CLOSERS -> -1
            isSyllableStart(s, i) -> 0
            else -> -1
        }
    }

    /**
     * True when a Burmese syllable starts at [i]: a consonant, independent vowel or digit that is not stacked under
     * the one before (no virama U+1039 before it) and not killed (no asat U+103A or virama after it).
     */
    internal fun isSyllableStart(s: String, i: Int): Boolean {
        val c = s[i]
        if (!isMyanmarBase(c)) return false
        val prev = s.getOrNull(i - 1) ?: return false
        if (prev == VIRAMA) return false
        if (!isMyanmar(prev)) return false
        val next = s.getOrNull(i + 1)
        return next != ASAT && next != VIRAMA
    }

    /** No break point at all: cut at [limit], moved back so a combining mark stays with its letter. */
    private fun hardCut(s: String, start: Int, limit: Int): Int {
        var cut = limit
        while (cut > start + 1 && (isCombining(s[cut]) || s[cut - 1] == VIRAMA || s[cut].isLowSurrogate())) cut--
        return cut
    }

    private fun isMyanmar(c: Char) = c in '\u1000'..'\u109F'
    private fun isMyanmarBase(c: Char) = c in '\u1000'..'\u102A' || c == '\u103F' || c in '\u1040'..'\u1049' || c in '\u104C'..'\u104F'
    private fun isCombining(c: Char) = c in '\u102B'..'\u103E' || c in '\u1056'..'\u1059' || c in '\u105E'..'\u1060' ||
        c in '\u1062'..'\u1064' || c in '\u1067'..'\u106D' || c in '\u1071'..'\u1074' || c in '\u1082'..'\u108D' ||
        c == '\u108F' || c in '\u109A'..'\u109D' || c in '\u0300'..'\u036F' || c == '\u200C' || c == '\u200D'

    private const val VIRAMA = '\u1039'
    private const val ASAT = '\u103A'
    private const val SENTENCE_END = "။.!?！？"
    private const val CLAUSE = "၊,;:，；"
    private const val CLOSERS = "\"'”’)]»"
}
