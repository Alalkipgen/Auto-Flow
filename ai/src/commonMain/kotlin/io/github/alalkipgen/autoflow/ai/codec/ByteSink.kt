package io.github.alalkipgen.autoflow.ai.codec

/** Growable byte buffer for the tiny encoders in this package (common code has no ByteArrayOutputStream). */
internal class ByteSink(initial: Int = 256) {
    private var buf = ByteArray(maxOf(16, initial))
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var n = buf.size * 2
        while (n < size + extra) n *= 2
        buf = buf.copyOf(n)
    }

    fun byte(v: Int): ByteSink = apply { ensure(1); buf[size++] = v.toByte() }

    fun bytes(b: ByteArray, from: Int = 0, to: Int = b.size): ByteSink = apply {
        ensure(to - from)
        b.copyInto(buf, size, from, to)
        size += to - from
    }

    fun ascii(s: String): ByteSink = apply { s.forEach { byte(it.code) } }

    fun intBe(v: Int): ByteSink = byte(v ushr 24).byte(v ushr 16).byte(v ushr 8).byte(v)

    fun intLe(v: Int): ByteSink = byte(v).byte(v ushr 8).byte(v ushr 16).byte(v ushr 24)

    fun shortLe(v: Int): ByteSink = byte(v).byte(v ushr 8)

    fun toByteArray(): ByteArray = buf.copyOf(size)
}
