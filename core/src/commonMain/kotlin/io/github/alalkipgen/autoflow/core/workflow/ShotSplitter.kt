package io.github.alalkipgen.autoflow.core.workflow

import io.github.alalkipgen.autoflow.core.CoreError
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.AnalysisSection
import io.github.alalkipgen.autoflow.core.model.Shot
import kotlin.math.abs

/**
 * Splits a song into shots (PLAN 1A, docs/prompts/02-phase1-core.md item 4):
 * - equal clips of `clipMs`;
 * - a remainder of at least [MIN_LAST_CLIP_MS] becomes its own last shot, a shorter one merges into the previous shot;
 * - a cut within [SNAP_WINDOW_MS] of a section start moves onto it, as long as cuts stay strictly increasing.
 * Each shot gets the section it overlaps most (earliest on a tie). Specs and lyrics stay empty until the storyboard.
 */
object ShotSplitter {
    const val MIN_LAST_CLIP_MS = 2_000L
    const val SNAP_WINDOW_MS = 1_500L

    fun split(durationMs: Long, clipMs: Long, sections: List<AnalysisSection>? = null): CoreResult<List<Shot>> {
        if (durationMs <= 0) return CoreResult.Err(CoreError.InvalidInput("Song duration must be greater than 0"))
        if (clipMs <= 0) return CoreResult.Err(CoreError.InvalidInput("Clip length must be greater than 0"))

        val cuts = mutableListOf<Long>() // internal cut points, strictly increasing, all in (0, durationMs)
        var t = clipMs
        while (t < durationMs) {
            cuts += t
            t += clipMs
        }
        if (cuts.isNotEmpty() && durationMs - cuts.last() < MIN_LAST_CLIP_MS) cuts.removeAt(cuts.lastIndex)

        val boundaries = sections.orEmpty().map { it.startMs }.filter { it in 1 until durationMs }.distinct().sorted()
        for (i in cuts.indices) {
            val nearest = boundaries.minByOrNull { abs(it - cuts[i]) } ?: break
            if (abs(nearest - cuts[i]) > SNAP_WINDOW_MS) continue
            val previous = if (i == 0) 0L else cuts[i - 1]
            val next = if (i == cuts.lastIndex) durationMs else cuts[i + 1]
            if (nearest > previous && nearest < next) cuts[i] = nearest
        }

        val edges = listOf(0L) + cuts + durationMs
        val shots = edges.zipWithNext().mapIndexed { index, (start, end) ->
            Shot(number = index + 1, startMs = start, durationMs = end - start, section = sectionFor(start, end, sections))
        }
        return CoreResult.Ok(shots)
    }

    private fun sectionFor(start: Long, end: Long, sections: List<AnalysisSection>?) = sections.orEmpty()
        .map { it to (minOf(end, it.endMs) - maxOf(start, it.startMs)) }
        .filter { it.second > 0 }
        .maxByOrNull { it.second } // first maximum wins, so ties go to the earlier section in list order
        ?.first?.type
}
