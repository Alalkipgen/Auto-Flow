package io.github.alalkipgen.autoflow.media.audio

/** 16-bit PCM WAV → mono samples in [-1, 1] (channels averaged). Only what [SongAudio] writes needs to parse. */
class PcmWav(val rate: Int, val samples: FloatArray) {
    val durationMs: Long get() = if (rate <= 0) 0 else samples.size * 1000L / rate

    companion object {
        fun parse(bytes: ByteArray): PcmWav? {
            if (bytes.size < 12 || ascii(bytes, 0) != "RIFF" || ascii(bytes, 8) != "WAVE") return null
            var pos = 12
            var rate = 0
            var channels = 0
            var bits = 0
            var format = 0
            while (pos + 8 <= bytes.size) {
                val id = ascii(bytes, pos)
                val size = int32(bytes, pos + 4).toLong() and 0xFFFFFFFFL
                val body = pos + 8
                if (id == "fmt " && body + 16 <= bytes.size) {
                    format = int16(bytes, body)
                    channels = int16(bytes, body + 2)
                    rate = int32(bytes, body + 4)
                    bits = int16(bytes, body + 14)
                } else if (id == "data") {
                    if (format != 1 || bits != 16 || channels < 1 || rate <= 0) return null
                    // ffmpeg writing to a pipe leaves the size unset (0 / 0xFFFFFFFF): read to the end then.
                    val end = if (size == 0L || body + size > bytes.size) bytes.size else (body + size).toInt()
                    val frames = (end - body) / (2 * channels)
                    val out = FloatArray(frames)
                    for (f in 0 until frames) {
                        var sum = 0
                        for (c in 0 until channels) sum += int16s(bytes, body + (f * channels + c) * 2)
                        out[f] = sum / (channels * 32768f)
                    }
                    return PcmWav(rate, out)
                }
                if (body + size >= bytes.size) return null
                pos = body + size.toInt() + (size.toInt() and 1)
            }
            return null
        }

        private fun ascii(b: ByteArray, at: Int) = if (at + 4 > b.size) "" else String(CharArray(4) { (b[at + it].toInt() and 0xFF).toChar() })
        private fun int16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        private fun int16s(b: ByteArray, at: Int) = int16(b, at).toShort().toInt()
        private fun int32(b: ByteArray, at: Int) = int16(b, at) or (int16(b, at + 2) shl 16)

        /** A 16-bit mono WAV of [samples] (tests and fixtures). */
        fun encode(rate: Int, samples: FloatArray): ByteArray {
            val data = samples.size * 2
            val out = ByteArray(44 + data)
            fun put32(at: Int, v: Int) { for (i in 0..3) out[at + i] = (v ushr (8 * i)).toByte() }
            fun put16(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v ushr 8).toByte() }
            "RIFF".forEachIndexed { i, c -> out[i] = c.code.toByte() }
            put32(4, 36 + data)
            "WAVEfmt ".forEachIndexed { i, c -> out[8 + i] = c.code.toByte() }
            put32(16, 16); put16(20, 1); put16(22, 1); put32(24, rate); put32(28, rate * 2); put16(32, 2); put16(34, 16)
            "data".forEachIndexed { i, c -> out[36 + i] = c.code.toByte() }
            put32(40, data)
            samples.forEachIndexed { i, s -> put16(44 + i * 2, (s.coerceIn(-1f, 1f) * 32767).toInt()) }
            return out
        }
    }
}
