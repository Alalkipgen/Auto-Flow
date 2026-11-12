package io.github.alalkipgen.autoflow.core.recap

/** Scene list rules (docs/specs/recap.md §3): cut times → scenes, short ones merged, long ones split. */
object Scenes {
    const val THRESHOLD_MORE = 6
    const val THRESHOLD_NORMAL = 10
    const val THRESHOLD_FEWER = 16
    const val MIN_MS = 1_000L
    const val MAX_MS = 20_000L

    /**
     * Scenes covering `[0, durationMs)` cut at [cuts] (movie times, any order; out-of-range ones ignored). A scene
     * shorter than [MIN_MS] joins the previous one (the first joins the next); a scene longer than [MAX_MS] is split
     * into equal parts of at most [MAX_MS]. Picks of [previous] survive where a scene starts at the same time.
     */
    fun fromCuts(cuts: List<Long>, durationMs: Long, previous: List<RecapScene> = emptyList()): List<RecapScene> {
        if (durationMs <= 0) return emptyList()
        val bounds = (listOf(0L) + cuts.filter { it in 1 until durationMs }.sorted() + durationMs).distinct()
        val merged = mutableListOf<Pair<Long, Long>>()
        for (i in 0 until bounds.size - 1) {
            val s = bounds[i]
            val e = bounds[i + 1]
            if (merged.isNotEmpty() && e - s < MIN_MS) merged[merged.lastIndex] = merged.last().first to e
            else merged += s to e
        }
        if (merged.size > 1 && merged[0].second - merged[0].first < MIN_MS) {
            merged[1] = merged[0].first to merged[1].second
            merged.removeAt(0)
        }
        val keep = previous.filter { it.picked }.map { it.startMs }.toSet()
        return merged.flatMap { (s, e) ->
            val parts = ((e - s + MAX_MS - 1) / MAX_MS).toInt().coerceAtLeast(1)
            (0 until parts).map { k -> s + (e - s) * k / parts to s + (e - s) * (k + 1) / parts }
        }.map { (s, e) -> RecapScene(s, e, picked = s in keep) }
    }

    /** True when the picked total is more than 20 % away from the target (the header turns amber). */
    fun offTarget(recap: Recap): Boolean {
        val t = recap.targetMs
        if (t <= 0 || recap.picked.isEmpty()) return false
        val p = recap.pickedMs
        return p * 10 > t * 12 || p * 10 < t * 8
    }

    /** Parses `lavfi.scd.time: 12.345` values from an `scdet` log line; null when the line has none. */
    fun cutMs(logLine: String): Long? {
        val m = SCD_TIME.find(logLine) ?: return null
        return (m.groupValues[1].toDoubleOrNull() ?: return null).let { (it * 1000).toLong() }
    }

    private val SCD_TIME = Regex("""lavfi\.scd\.time:\s*([0-9]+(?:\.[0-9]+)?)""")
}
