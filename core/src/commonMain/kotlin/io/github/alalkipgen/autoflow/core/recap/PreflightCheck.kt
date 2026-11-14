package io.github.alalkipgen.autoflow.core.recap

/**
 * Pre-flight check for a Recap export (docs/specs/studio-v2.md §9): a list of warnings and blockers computed
 * entirely from project data (no render needed). Shown on the Export screen before each render.
 */
object PreflightCheck {

    /** One pre-flight item. [fix] = the navigation destination to fix it, e.g. "Voice" or "Script". */
    data class Item(val message: String, val kind: Kind, val fix: String? = null)

    enum class Kind { BLOCKER, WARNING, INFO }

    /**
     * Runs all pre-flight checks for [recap] and its [script].
     * [freeBytes] = available disk space (null = unknown); [estimatedOutputBytes] = rough output size estimate.
     */
    fun check(recap: Recap, script: RecapScript?): List<Item> {
        val items = mutableListOf<Item>()
        if (script == null) return items  // script not approved; blockers shown elsewhere

        val rows = RecapFit.rows(recap, script)

        // 1. Segments with no voice at all
        val unvoiced = rows.count { it.takeMs == null }
        if (unvoiced > 0) {
            items += Item(
                "$unvoiced scene${if (unvoiced == 1) "" else "s"} without a voice take — they will play with movie sound only.",
                Kind.WARNING,
                fix = "Voice",
            )
        }

        // 2. Stale takes (text changed after recording)
        val stale = script.segments.indices.count { RecapRules.isStale(recap, script, it) }
        if (stale > 0) {
            items += Item(
                "$stale take${if (stale == 1) "" else "s"} are stale (the script changed after recording).",
                Kind.WARNING,
                fix = "Voice",
            )
        }

        // 3. Takes that run on by more than 2 s
        val runsOn = rows.count { it.overMs > 2_000 }
        if (runsOn > 0) {
            items += Item(
                "$runsOn scene${if (runsOn == 1) "" else "s"} where the voice is more than 2 s longer than the clip.",
                Kind.WARNING,
                fix = "Voice",
            )
        }

        // 4. Rights notice reminder
        if (recap.rightsConfirmed) {
            items += Item(
                "Using movie clips requires permission from the rights holder. Auto Flow makes no claim about whether " +
                    "your use qualifies as commentary, education or fair use.",
                Kind.INFO,
            )
        }

        return items
    }
}
