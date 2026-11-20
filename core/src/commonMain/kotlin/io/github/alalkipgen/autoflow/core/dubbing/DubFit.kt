package io.github.alalkipgen.autoflow.core.dubbing

/** How a line's take fits its time (specs/dubbing.md §4); the picture never moves. */
enum class DubFitKind { FITS, USES_PAUSE, FASTER, TOO_LONG }

/** [kind] with the voice [speed] it plays at (1.0 unless FASTER) and, when TOO_LONG, [overMs] still too long at the top speed. */
data class DubFit(val kind: DubFitKind, val speed: Double = 1.0, val overMs: Long = 0) {
    companion object {
        /**
         * The fit of a take of [takeMs] voice (silence trimmed) for a line of [lineMs] with a slot of [slotMs]
         * (`DubRules.slots`): within the line → FITS; within the slot → USES_PAUSE; within the slot at up to
         * [maxVoiceSpeed] → FASTER at speed ceil2(take/slot); else TOO_LONG by take/maxVoiceSpeed − slot (rounded up).
         * Integer hundredths, so 1.10× is 1.10 and not 1.11 from a float rounding.
         */
        fun of(takeMs: Long, lineMs: Long, slotMs: Long, maxVoiceSpeed: Double): DubFit {
            val max = (maxVoiceSpeed * 100).let { kotlin.math.round(it).toLong() }.coerceAtLeast(100)
            return when {
                takeMs <= lineMs -> DubFit(DubFitKind.FITS)
                takeMs <= slotMs -> DubFit(DubFitKind.USES_PAUSE)
                slotMs > 0 && takeMs * 100 <= max * slotMs -> DubFit(DubFitKind.FASTER, ((takeMs * 100 + slotMs - 1) / slotMs) / 100.0)
                else -> DubFit(DubFitKind.TOO_LONG, overMs = (takeMs * 100 + max - 1) / max - slotMs)
            }
        }
    }
}
