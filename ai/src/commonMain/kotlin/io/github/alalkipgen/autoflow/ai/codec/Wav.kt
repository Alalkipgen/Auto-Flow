package io.github.alalkipgen.autoflow.ai.codec

/** RIFF/WAVE PCM: writes 16-bit mono, reads the `fmt `/`data` header of any PCM WAV. */
internal object Wav {
    data class Info(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val dataBytes: Int) {
        val durationMs: Long
            get() {
                val bytesPerSecond = sampleRate.toLong() * channels * (bitsPerSample / 8)
                return if (bytesPerSecond == 0L) 0 else dataBytes * 1000L / bytesPerSecond
            }
    }

    fun pcm16Mono(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteSink(44 + dataBytes)
            .ascii("RIFF").intLe(36 + dataBytes).ascii("WAVE")
            .ascii("fmt ").intLe(16).shortLe(1).shortLe(1).intLe(sampleRate).intLe(sampleRate * 2).shortLe(2).shortLe(16)
            .ascii("data").intLe(dataBytes)
        for (s in samples) out.shortLe(s.toInt())
        return out.toByteArray()
    }

    /** Null when [bytes] is not a PCM WAV with both `fmt ` and `data` chunks. */
    fun parse(bytes: ByteArray): Info? {
        if (bytes.size < 12 || bytes.ascii(0) != "RIFF" || bytes.ascii(8) != "WAVE") return null
        var pos = 12
        var fmt: IntArray? = null
        while (pos + 8 <= bytes.size) {
            val id = bytes.ascii(pos)
            val len = bytes.intLe(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    if (len < 16 || body + 16 > bytes.size || bytes.shortLe(body) != 1) return null
                    fmt = intArrayOf(bytes.shortLe(body + 2), bytes.intLe(body + 4), bytes.shortLe(body + 14))
                }
                "data" -> {
                    val f = fmt ?: return null
                    val available = bytes.size - body
                    val data = if (len < 0) available else minOf(len, available) // len < 0: streamed, size unknown
                    return Info(sampleRate = f[1], channels = f[0], bitsPerSample = f[2], dataBytes = data)
                }
            }
            if (len < 0) return null
            pos = body + len + (len and 1)
        }
        return null
    }

    private fun ByteArray.ascii(at: Int): String =
        if (at + 4 > size) "" else (0 until 4).map { this[at + it].toInt().toChar() }.joinToString("")

    private fun ByteArray.intLe(at: Int): Int =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or ((this[at + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.shortLe(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)
}
