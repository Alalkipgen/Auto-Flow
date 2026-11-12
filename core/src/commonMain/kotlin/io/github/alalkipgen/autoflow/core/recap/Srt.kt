package io.github.alalkipgen.autoflow.core.recap

/** One subtitle cue (movie time). */
data class SrtCue(val startMs: Long, val endMs: Long, val text: String)

/** SubRip read / write (docs/specs/recap.md §4, §6). Reading is lenient: malformed blocks are skipped. */
object Srt {
    private val TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val TAG = Regex("""<[^>]+>|\{\\[^}]*\}""")

    fun parse(text: String): List<SrtCue> {
        val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<SrtCue>()
        var i = 0
        while (i < lines.size) {
            val m = TIME.find(lines[i])
            if (m == null) { i++; continue }
            val g = m.groupValues
            val start = ms(g[1], g[2], g[3], g[4])
            val end = ms(g[5], g[6], g[7], g[8])
            i++
            val body = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank() && TIME.find(lines[i]) == null) { body += lines[i]; i++ }
            // A trailing number line belongs to the next cue.
            if (body.isNotEmpty() && i < lines.size && TIME.find(lines[i]) != null && body.last().trim().all { it.isDigit() }) body.removeAt(body.lastIndex)
            val t = body.joinToString("\n") { it.replace(TAG, "").trim() }.trim()
            if (t.isNotEmpty() && end > start) out += SrtCue(start, end, t)
        }
        return out.sortedBy { it.startMs }
    }

    fun write(cues: List<SrtCue>): String = cues.withIndex().joinToString("") { (k, c) ->
        "${k + 1}\n${stamp(c.startMs)} --> ${stamp(c.endMs)}\n${c.text}\n\n"
    }

    /** Cue texts that overlap `[startMs, endMs)`, joined with spaces and cut to [maxChars]. */
    fun textIn(cues: List<SrtCue>, startMs: Long, endMs: Long, maxChars: Int): String {
        val t = cues.filter { it.endMs > startMs && it.startMs < endMs }.joinToString(" ") { it.text.replace('\n', ' ') }
        return if (t.length <= maxChars) t else t.take(maxChars).trimEnd() + "…"
    }

    fun stamp(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        val f = ms % 1000
        return "${pad(h, 2)}:${pad(m, 2)}:${pad(s, 2)},${pad(f, 3)}"
    }

    private fun pad(v: Long, n: Int) = v.toString().padStart(n, '0')

    private fun ms(h: String, m: String, s: String, f: String): Long =
        h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + f.padEnd(3, '0').toLong()
}
