package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.recap.RecapClip

/** Exact decimal seconds, not floating-point timestamps or a new persisted time representation. */
internal data class ClipRangeInput(val from: String = "", val to: String = "") {
    fun clip(): RecapClip? = parse(from)?.let { a -> parse(to)?.let { b -> RecapClip(a, b) } }
    companion object {
        fun seconds(ms: Long): String = "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"
        fun of(clip: RecapClip) = ClipRangeInput(seconds(clip.inMs), seconds(clip.outMs))
        fun parse(text: String): Long? {
            val s = text.trim()
            if (!s.matches(Regex("[0-9]+(\\.[0-9]{1,3})?"))) return null
            val whole = s.substringBefore('.').toLongOrNull() ?: return null
            if (whole > Long.MAX_VALUE / 1000) return null
            val fraction = s.substringAfter('.', "").padEnd(3, '0').toLong()
            val base = whole * 1000
            return if (base > Long.MAX_VALUE - fraction) null else base + fraction
        }
        fun valid(clips: List<RecapClip>, movieMs: Long): Boolean {
            var total = 0L
            return clips.isNotEmpty() && clips.all {
                if (it.inMs < 0 || it.outMs > movieMs || it.outMs <= it.inMs || it.durationMs < 500 ||
                    total > Long.MAX_VALUE - it.durationMs) false
                else { total += it.durationMs; true }
            }
        }
        fun split(clip: RecapClip, at: Long): List<RecapClip>? =
            if (at >= clip.inMs && at <= clip.outMs && at - clip.inMs >= 500 && clip.outMs - at >= 500)
                listOf(RecapClip(clip.inMs, at), RecapClip(at, clip.outMs)) else null
        fun merge(a: RecapClip, b: RecapClip): RecapClip? =
            if (a.outMs == b.inMs && a.inMs < a.outMs && b.inMs < b.outMs) RecapClip(a.inMs, b.outMs) else null
    }
}