package io.github.alalkipgen.autoflow.core.dubbing

import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.model.DubApproval
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc

/** Why a Dubbing step is closed (specs/dubbing.md §2); the UI says it, never decides it. */
enum class DubBlock { NO_VIDEO, TOO_LONG, NO_LINES, NOT_TRANSLATED, NOT_APPROVED }

/**
 * Dubbing gates (specs/dubbing.md §1–§2, D-83). The subtitle text (`textMy`) and the spoken line (`spokenMy`) are
 * different fields; the spoken line follows the subtitle text until the owner changes it, and the approval is bound to
 * the spoken lines by [hash] — any change (a spoken line, or a subtitle text a line follows) needs a new approval
 * before any voice is made.
 */
object DubRules {
    /** What the dub voice says for [cue]: its own spoken line, else the subtitle text. */
    fun spoken(cue: SubtitleCue): String = cue.spokenMy?.takeIf { it.isNotBlank() } ?: cue.textMy?.trim().orEmpty()

    /** Ids of the lines without Burmese subtitle text, in order. */
    fun notTranslated(doc: SubtitleDoc): List<String> = doc.cues.filter { it.textMy.isNullOrBlank() }.map { it.id }

    /** Hash of every line's id, times and spoken text — what the approval is for. */
    fun hash(doc: SubtitleDoc): String = Sha256.hex(doc.cues.joinToString("\n") { "${it.id}\t${it.startMs}\t${it.endMs}\t${spoken(it)}" }
        .encodeToByteArray()).take(16)

    /**
     * Each line's slot (spec §4): from its start to `next.start − minGapMs`, the last one to [videoMs]; never below the
     * line itself, so a line whose next one starts too soon still keeps its own time.
     */
    fun slots(doc: SubtitleDoc, videoMs: Long, minGapMs: Long): List<Long> = doc.cues.mapIndexed { i, c ->
        val end = doc.cues.getOrNull(i + 1)?.let { it.startMs - minGapMs } ?: videoMs
        maxOf(end, c.endMs) - c.startMs
    }

    /** Over the limit (`dubbing.maxSourceMs`, Q-41): refused at Video with `DUB_LIMIT`. */
    fun tooLong(durationMs: Long, maxSourceMs: Long): Boolean = maxSourceMs in 1 until durationMs

    /** Dub script opens when the video is within the limit and every line has Burmese subtitle text. */
    fun canOpenScript(durationMs: Long?, maxSourceMs: Long, doc: SubtitleDoc?): DubBlock? = when {
        durationMs == null -> DubBlock.NO_VIDEO
        tooLong(durationMs, maxSourceMs) -> DubBlock.TOO_LONG
        doc == null || doc.cues.isEmpty() -> DubBlock.NO_LINES
        notTranslated(doc).isNotEmpty() -> DubBlock.NOT_TRANSLATED
        else -> null
    }

    fun isApproved(tool: DubbingTool?, doc: SubtitleDoc?): Boolean =
        tool?.scriptApproved != null && doc != null && tool.scriptApproved.hash == hash(doc)

    /** Voice opens only for an approved, unchanged script. */
    fun canSpeak(durationMs: Long?, maxSourceMs: Long, tool: DubbingTool?, doc: SubtitleDoc?): DubBlock? =
        canOpenScript(durationMs, maxSourceMs, doc) ?: if (isApproved(tool, doc)) null else DubBlock.NOT_APPROVED

    /** Approve the current spoken lines; null when the script cannot be approved yet. */
    fun approve(tool: DubbingTool, durationMs: Long?, maxSourceMs: Long, doc: SubtitleDoc?, at: Long): DubbingTool? =
        if (canOpenScript(durationMs, maxSourceMs, doc) != null) null else tool.copy(scriptApproved = DubApproval(hash(doc!!), at))

    /**
     * [doc] with line [id]'s spoken line set to [text]; text equal to the subtitle text (or blank) means "follow the
     * subtitle text" (`spokenMy` = null). Unknown id → [doc] unchanged.
     */
    fun setSpoken(doc: SubtitleDoc, id: String, text: String): SubtitleDoc {
        val clean = text.trim()
        return doc.copy(cues = doc.cues.map { c ->
            if (c.id != id) c else c.copy(spokenMy = clean.takeIf { it.isNotEmpty() && it != c.textMy?.trim() })
        })
    }

    /** The spoken lines changed: the approval no longer holds, so it is cleared (spec §2). */
    fun afterEdit(tool: DubbingTool, doc: SubtitleDoc): DubbingTool = if (isApproved(tool, doc)) tool else tool.copy(scriptApproved = null)
}
