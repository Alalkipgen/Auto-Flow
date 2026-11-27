package io.github.alalkipgen.autoflow.core.recap

/**
 * Pre-flight check for a Recap export (docs/specs/studio-v2.md §9): a list of warnings and blockers computed
 * entirely from project data (no render needed). Shown on the Export screen before each render.
 */
object PreflightCheck {

    /** One pre-flight item. [fix] = the navigation destination to fix it, e.g. "Voice" or "Script". */
    data class Item(val message: String, val kind: Kind, val fix: String? = null,
        val reason: Reason = Reason.UNKNOWN, val count: Int = 0)

    /** Semantic result data for UI localization; never serialized into a project. */
    enum class Reason { UNKNOWN, UNVOICED, STALE, RUNS_ON, RIGHTS }

    enum class Kind { BLOCKER, WARNING, INFO }

    /** v1 shape: the same checks on [Recap.toV2] (lossless). */
    fun check(recap: Recap, script: RecapScript?): List<Item> {
        if (script == null) return emptyList()
        val (v2, story) = recap.toV2(script)
        return check(v2, story)
    }

    /**
     * Runs all pre-flight checks for [recap] and its [story] (v2, audit F07: by block, with the chosen takes).
     */
    fun check(recap: RecapV2, story: RecapStory?, lexicon: Lexicon = Lexicon.EMPTY): List<Item> {
        val items = mutableListOf<Item>()
        if (story == null) return items  // script not approved; blockers shown elsewhere

        val rows = RecapFit.rows(recap, story, lexicon)

        // 1. Segments with no voice at all
        val unvoiced = rows.count { it.takeMs == null }
        if (unvoiced > 0) {
            items += Item(
                "$unvoiced scene${if (unvoiced == 1) "" else "s"} without a voice take — they will play with movie sound only.",
                Kind.WARNING,
                fix = "Voice", reason = Reason.UNVOICED, count = unvoiced,
            )
        }

        // 2. Stale takes (text changed after recording)
        val stale = story.blocks.count { RecapRulesV2.isStale(recap, it, lexicon) }
        if (stale > 0) {
            items += Item(
                "$stale take${if (stale == 1) "" else "s"} are stale (the script changed after recording).",
                Kind.WARNING,
                fix = "Voice", reason = Reason.STALE, count = stale,
            )
        }

        // 3. Takes that run on by more than 2 s
        val runsOn = rows.count { it.overMs > 2_000 }
        if (runsOn > 0) {
            items += Item(
                "$runsOn scene${if (runsOn == 1) "" else "s"} where the voice is more than 2 s longer than the clip.",
                Kind.WARNING,
                fix = "Voice", reason = Reason.RUNS_ON, count = runsOn,
            )
        }

        // 4. Rights notice reminder
        if (recap.rightsConfirmed) {
            items += Item(
                "Using movie clips requires permission from the rights holder. Auto Flow makes no claim about whether " +
                    "your use qualifies as commentary, education or fair use.",
                Kind.INFO, reason = Reason.RIGHTS,
            )
        }

        return items
    }
}
