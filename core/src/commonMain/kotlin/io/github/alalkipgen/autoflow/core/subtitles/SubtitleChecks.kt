package io.github.alalkipgen.autoflow.core.subtitles

import io.github.alalkipgen.autoflow.core.config.ToolsConfig

/** What a line may need checking for (specs/quick-tools.md §5). Checks never block Save. */
enum class CueIssue { OVERLAP, TOO_SHORT, LINE_TOO_LONG, TOO_MANY_LINES, EMPTY, NOT_TRANSLATED, ORIGINAL_CHANGED }

/** Why an export cannot start (the Burmese-font check is the export screen's, S-4). */
enum class ExportBlock { NO_LINES, BAD_TIMES }

object SubtitleChecks {
    /**
     * Issues per cue id, in time order; cues without issues are left out. [choice] is the text the owner looks at:
     * length checks apply to it (Burmese falls back to the original like [SubtitleOps.toSrt]). "Not translated"
     * is reported only once some line has Burmese, so an untranslated document is not all warnings.
     */
    fun check(doc: SubtitleDoc, config: ToolsConfig.Subtitles, choice: TextChoice = TextChoice.ORIGINAL): Map<String, Set<CueIssue>> {
        val sorted = SubtitleOps.sort(doc).cues
        val translating = sorted.any { !it.textMy.isNullOrBlank() }
        val out = LinkedHashMap<String, Set<CueIssue>>()
        sorted.forEachIndexed { i, c ->
            val issues = LinkedHashSet<CueIssue>()
            sorted.getOrNull(i + 1)?.let { next -> if (next.startMs < c.endMs) issues += CueIssue.OVERLAP }
            if (c.endMs - c.startMs < config.minCueMs) issues += CueIssue.TOO_SHORT
            val shown = shownTexts(c, choice)
            if (shown.first().isBlank()) issues += CueIssue.EMPTY
            val lines = shown.map { t -> t.split('\n').map { it.trim() }.filter { it.isNotEmpty() } }
            if (lines.any { l -> l.any { width(it) > config.maxCharsPerLine } }) issues += CueIssue.LINE_TOO_LONG
            if (lines.any { it.size > config.maxLines }) issues += CueIssue.TOO_MANY_LINES
            if (translating && c.textMy.isNullOrBlank() && c.text.isNotBlank()) issues += CueIssue.NOT_TRANSLATED
            if (!c.textMy.isNullOrBlank() && c.myFrom != null && c.myFrom != myHash(c.text)) issues += CueIssue.ORIGINAL_CHANGED
            if (issues.isNotEmpty()) out[c.id] = issues
        }
        return out
    }

    /** Export stops only for these; everything else is a warning. */
    fun exportBlocks(doc: SubtitleDoc): Set<ExportBlock> = buildSet {
        if (doc.cues.isEmpty()) add(ExportBlock.NO_LINES)
        if (doc.cues.any { it.startMs < 0 || it.endMs <= it.startMs }) add(ExportBlock.BAD_TIMES)
    }

    /**
     * "Fix automatically" (a draft edit): empty lines are removed and an overlapping line ends where the next one
     * starts; two lines starting at the same time stay as they are (the owner decides). No fix → the same document.
     */
    fun autoFix(doc: SubtitleDoc, choice: TextChoice = TextChoice.ORIGINAL): SubtitleDoc {
        val kept = SubtitleOps.sort(doc).cues.filter { shownTexts(it, choice).first().isNotBlank() }
        val fixed = kept.mapIndexed { i, c ->
            val next = kept.getOrNull(i + 1)
            if (next != null && next.startMs < c.endMs && next.startMs > c.startMs) c.copy(endMs = next.startMs) else c
        }
        return if (fixed == doc.cues) doc else doc.copy(cues = fixed)
    }

    /** Line length as `CaptionChunks` measures it: characters after joining runs of spaces. */
    fun width(line: String): Int = line.replace(Regex("\\s+"), " ").trim().length

    /** The texts on screen for [choice], the main one first (Both = the original, then the Burmese when there is one). */
    private fun shownTexts(c: SubtitleCue, choice: TextChoice): List<String> = when (choice) {
        TextChoice.ORIGINAL -> listOf(c.text)
        TextChoice.BURMESE -> listOf(c.textMy?.takeIf { it.isNotBlank() } ?: c.text)
        TextChoice.BOTH -> listOfNotNull(c.text, c.textMy?.takeIf { it.isNotBlank() })
    }
}
