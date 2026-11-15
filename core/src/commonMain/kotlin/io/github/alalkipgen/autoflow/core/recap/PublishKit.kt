package io.github.alalkipgen.autoflow.core.recap

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

    /** First line of [text] up to its first sentence end (`။` / `.` / `?` / `!`), at most [max] characters + "…". */
    fun chapterTitle(text: String, max: Int = 40): String {
        val line = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val end = line.indexOfFirst { it == '\u104B' || it == '.' || it == '?' || it == '!' }
        val sentence = (if (end > 0) line.substring(0, end) else line).trim()
        return if (sentence.length <= max) sentence else sentence.take(max).trimEnd() + "…"
    }

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
