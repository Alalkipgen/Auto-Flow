package io.github.alalkipgen.autoflow.media.render

/**
 * Reads `-progress pipe:1` blocks (`out_time_us=…`, `speed=1.2x`, `progress=continue|end`) from ffmpeg's merged
 * output and reports one [Progress] per block; the classic stats line (`… time=00:00:05.12 … speed=…`) also counts,
 * so a build without `-progress` still moves the bar. Never throws on odd lines.
 */
class FfmpegProgress(private val durationMs: Long) {
    data class Progress(val outTimeMs: Long, val fraction: Double, val speed: Double?, val done: Boolean)

    private var outUs: Long = 0
    private var speed: Double? = null

    /** A [Progress] when [line] ends a block (or is a stats line), else null. */
    fun feed(line: String): Progress? {
        val l = line.trim()
        when {
            l.startsWith("out_time_us=") || l.startsWith("out_time_ms=") ->
                l.substringAfter('=').toLongOrNull()?.takeIf { it >= 0 }?.let { outUs = it }
            l.startsWith("speed=") -> speed = parseSpeed(l.substringAfter('='))
            l.startsWith("progress=") -> return progress(done = l.substringAfter('=') == "end")
            "time=" in l && ("frame=" in l || "size=" in l) -> {
                Stats.find(l)?.let { m ->
                    val (h, mi, s, cs) = m.destructured
                    outUs = ((h.toLong() * 3600 + mi.toLong() * 60 + s.toLong()) * 1000 + cs.padEnd(3, '0').take(3).toLong()) * 1000
                }
                SpeedField.find(l)?.let { speed = parseSpeed(it.groupValues[1]) }
                return progress(done = false)
            }
        }
        return null
    }

    private fun progress(done: Boolean): Progress {
        val ms = outUs / 1000
        val f = if (done) 1.0 else if (durationMs <= 0) 0.0 else (ms.toDouble() / durationMs).coerceIn(0.0, 1.0)
        return Progress(ms, f, speed, done)
    }

    private fun parseSpeed(v: String): Double? = v.trim().removeSuffix("x").trim().toDoubleOrNull()

    private companion object {
        val Stats = Regex("""time=(\d+):(\d{2}):(\d{2})\.(\d+)""")
        val SpeedField = Regex("""speed=\s*([\d.]+)x""")
    }
}
