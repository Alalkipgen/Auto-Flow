package io.github.alalkipgen.autoflow.core.subtitles

/** Subtitle times typed and shown by the owner: `h:mm:ss.mmm` or `mm:ss.mmm` (`,` also accepted); shown to 0.1 s. */
object TimeText {
    private val FULL = Regex("""^\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?:[.,](\d{1,3}))?\s*$""")

    /** Milliseconds, or null when the text is not a time (the UI keeps the old value and says why). */
    fun parse(text: String): Long? {
        val m = FULL.find(text) ?: return null
        val (h, mm, ss, frac) = m.destructured
        val minutes = mm.toLong()
        val seconds = ss.toLong()
        if (seconds > 59 || (h.isNotEmpty() && minutes > 59)) return null
        return (h.ifEmpty { "0" }.toLong() * 3_600 + minutes * 60 + seconds) * 1_000 + frac.padEnd(3, '0').ifEmpty { "0" }.toLong()
    }

    /** `m:ss.t` under an hour, else `h:mm:ss.t` (tenths rounded down, so a shown time never passes the real one). */
    fun show(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val h = t / 3_600_000
        val m = t / 60_000 % 60
        val s = t / 1_000 % 60
        val tenth = t / 100 % 10
        val ss = s.toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss.$tenth" else "$m:$ss.$tenth"
    }
}
