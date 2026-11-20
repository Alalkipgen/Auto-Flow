package io.github.alalkipgen.autoflow.core.dubbing

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc

/**
 * A line in the dub mix (specs/dubbing.md §6): take [au] from the line's start [atMs] with its first [leadMs] of
 * silence skipped, at [speed] (1.0 unless FASTER), cut at [slotMs] so it never reaches the next line.
 */
data class DubMixLine(val id: String, val au: ArtifactId, val atMs: Long, val leadMs: Long, val slotMs: Long, val speed: Double)

/** The Export pre-flight (spec §2): the script must be approved; [noVoice] lines have no current take, [tooLong] do not fit. */
data class DubExportCheck(val approved: Boolean, val noVoice: List<String>, val tooLong: List<String>) {
    val ok: Boolean get() = approved && noVoice.isEmpty() && tooLong.isEmpty()
}

/** What Export may mix (D-3); the UI shows [check], never decides it. */
object DubExport {
    /** Lines with nothing to say (blank) are neither spoken nor blocking. */
    fun check(tool: DubbingTool?, doc: SubtitleDoc, videoMs: Long, minGapMs: Long, maxVoiceSpeed: Double, lexicon: Lexicon): DubExportCheck {
        val slots = DubRules.slots(doc, videoMs, minGapMs)
        val noVoice = mutableListOf<String>(); val tooLong = mutableListOf<String>()
        doc.cues.forEachIndexed { i, c ->
            if (DubRules.sayText(c, lexicon).isEmpty()) return@forEachIndexed
            val take = DubRules.currentTake(tool, c, lexicon)
            when {
                take == null -> noVoice += c.id
                DubFit.of(take.ms, c.endMs - c.startMs, slots[i], maxVoiceSpeed).kind == DubFitKind.TOO_LONG -> tooLong += c.id
            }
        }
        return DubExportCheck(DubRules.isApproved(tool, doc), noVoice, tooLong)
    }

    /** The lines to mix, in order, when [check] passes; else null. */
    fun lines(tool: DubbingTool?, doc: SubtitleDoc, videoMs: Long, minGapMs: Long, maxVoiceSpeed: Double, lexicon: Lexicon): List<DubMixLine>? {
        if (!check(tool, doc, videoMs, minGapMs, maxVoiceSpeed, lexicon).ok) return null
        return voiced(tool, doc, videoMs, minGapMs, maxVoiceSpeed, lexicon)
    }

    /**
     * The preview before Export (F-6, D-95): the same placement as [lines] for every line that has a current take,
     * whether or not the script is approved or every line is spoken (a line too long is cut at its slot, as the mix would).
     */
    fun voiced(tool: DubbingTool?, doc: SubtitleDoc, videoMs: Long, minGapMs: Long, maxVoiceSpeed: Double, lexicon: Lexicon): List<DubMixLine> {
        val slots = DubRules.slots(doc, videoMs, minGapMs)
        return doc.cues.mapIndexedNotNull { i, c ->
            val take = DubRules.currentTake(tool, c, lexicon)?.takeIf { DubRules.sayText(c, lexicon).isNotEmpty() } ?: return@mapIndexedNotNull null
            DubMixLine(c.id, take.au, c.startMs, take.leadMs, slots[i], DubFit.of(take.ms, c.endMs - c.startMs, slots[i], maxVoiceSpeed).speed)
        }
    }
}
