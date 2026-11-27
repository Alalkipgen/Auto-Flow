package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.text.BurmeseSyllables
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Text from the `publishKit` stage (docs/specs/studio-v2.md §9), in Burmese. */
@Serializable
@SerialName("PublishKit")
data class PublishKit(
    val title: String = "",
    val description: String = "",
    val hashtags: List<String> = emptyList(),
    val chapters: List<PublishChapter> = emptyList(),
)

/** A chapter starting [atMs] into the exported video. */
@Serializable
@SerialName("PublishChapter")
data class PublishChapter(val atMs: Long, val title: String)

/** Builds `exports/<name>.publish.txt` (5B-10). Pure: the UI writes the file and offers Copy / Share per item. */
object PublishKitText {
    fun path(exportName: String): String = "exports/$exportName.publish.txt"

    /** The thumbnail beside it (frame + title layer, 5B-10). */
    fun thumbPath(exportName: String): String = "exports/$exportName.thumb.jpg"

    /**
     * Chapters from the story blocks (5B-10 "from block times"): each block starts where the ones before it end in
     * the export ([blockLengthsMs], output order) and is named by the start of its text ([chapterTitle]).
     */
    fun chaptersFromBlocks(blockLengthsMs: List<Long>, blockTexts: List<String>): List<PublishChapter> =
        chapters(blockStartsMs(blockLengthsMs), blockTexts.map(::chapterTitle))

    /**
     * First line of [text] up to its first sentence end, at most [max] characters + "…". A sentence ends at `။`, `?` or
     * `!`, or at a `.` (or `...`) followed by a space or the end of the line — not after a short abbreviation (Dr, Mr,
     * Mrs, Ms, St, Jr, Sr, vs, No) or a single letter, so "Dr. Mann …" is not cut to "Dr" (P9-G2-13). A long title is
     * cut at the last space or Burmese syllable start within [max] (never inside a syllable); a title without either
     * is cut at [max].
     */
    fun chapterTitle(text: String, max: Int = 40): String {
        val line = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val end = sentenceEnd(line)
        val sentence = (if (end > 0) line.substring(0, end) else line).trim()
        if (sentence.length <= max) return sentence
        val cut = (max downTo 1).firstOrNull { canCutAt(sentence, it) && sentence.substring(0, it).isNotBlank() } ?: max
        return sentence.take(cut).trimEnd() + "…"
    }

    /** Words that end with a `.` inside a sentence ("Dr. Mann", "Mr. Brand", "Mrs. Cooper", "No. 5"). */
    private val ABBREVIATIONS = setOf("Dr", "Mr", "Mrs", "Ms", "St", "Jr", "Sr", "vs", "Vs", "No")

    /** Index of the first sentence end in [line] (see [chapterTitle]), or -1. */
    private fun sentenceEnd(line: String): Int {
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\u104B' || c == '?' || c == '!') return i
            if (c != '.') { i++; continue }
            var last = i
            while (last + 1 < line.length && line[last + 1] == '.') last++
            val closes = last + 1 == line.length || line[last + 1].isWhitespace()
            var start = i
            while (start > 0 && line[start - 1].isLetter()) start--
            val word = line.substring(start, i)
            val short = word in ABBREVIATIONS || (word.length == 1 && (word[0] in 'A'..'Z' || word[0] in 'a'..'z'))
            if (closes && !short) return i
            i = last + 1
        }
        return -1
    }

    /** A cut before [i] keeps whole words and whole Burmese syllables (a space, a syllable start, Burmese → Latin). */
    private fun canCutAt(s: String, i: Int): Boolean = i < s.length && (s[i].isWhitespace() || s[i - 1].isWhitespace() ||
        BurmeseSyllables.startsAt(s, i) || (BurmeseSyllables.isBurmese(s[i - 1]) && !BurmeseSyllables.isBurmese(s[i])))

    /** [kit] as posted: its own chapters when it has any, else [auto] (made from the blocks). */
    fun withChapters(kit: PublishKit, auto: List<PublishChapter>): PublishKit =
        if (kit.chapters.isNotEmpty()) kit else kit.copy(chapters = auto)

    /** Start of each block in the export, from the block lengths in output order (first = 0). */
    fun blockStartsMs(blockLengthsMs: List<Long>): List<Long> = blockLengthsMs.runningFold(0L) { acc, ms -> acc + ms }.dropLast(1)

    /** Chapters at the given block starts; [titles] pairs with [startsMs] by index, blank titles are skipped. */
    fun chapters(startsMs: List<Long>, titles: List<String>): List<PublishChapter> =
        startsMs.zip(titles).filter { it.second.isNotBlank() }.map { PublishChapter(it.first, it.second.trim()) }

    /** `#tag` form: trimmed, inner spaces removed, one leading `#`, no duplicates, blanks dropped. */
    fun hashtags(tags: List<String>): List<String> =
        tags.map { it.trim().trimStart('#').replace(Regex("\\s+"), "") }.filter { it.isNotEmpty() }.distinct().map { "#$it" }

    /** `m:ss` under an hour, else `h:mm:ss` (YouTube chapter format). */
    fun timestamp(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
    }

    /**
     * Chapter lines in time order. YouTube needs the first chapter at 0:00, so when the earliest one starts later,
     * it is moved to 0:00.
     */
    fun chapterLines(chapters: List<PublishChapter>): List<String> {
        val sorted = chapters.sortedBy { it.atMs }
        return sorted.mapIndexed { i, c -> "${timestamp(if (i == 0) 0 else c.atMs)} ${c.title}" }
    }

    /** The description as posted: text, then chapters, then hashtags. */
    fun description(kit: PublishKit): String = listOf(
        kit.description.trim(),
        chapterLines(kit.chapters).joinToString("\n"),
        hashtags(kit.hashtags).joinToString(" "),
    ).filter { it.isNotEmpty() }.joinToString("\n\n")

    /** The whole file: each item under a heading so it can be copied on its own. */
    fun file(kit: PublishKit): String = buildString {
        append("# Title\n").append(kit.title.trim()).append("\n\n")
        append("# Description\n").append(kit.description.trim()).append("\n\n")
        append("# Chapters\n").append(chapterLines(kit.chapters).joinToString("\n")).append("\n\n")
        append("# Hashtags\n").append(hashtags(kit.hashtags).joinToString(" ")).append('\n')
    }

    private fun two(v: Long) = v.toString().padStart(2, '0')
}
