package io.github.alalkipgen.autoflow.ai.codec

/**
 * Minimal PNG encoder for placeholder images: 8-bit RGB, filter 0, zlib "stored" (uncompressed) deflate blocks.
 * Pixels are `0xRRGGBB` ints, row-major.
 */
internal object Png {
    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private const val MAX_STORED = 65_535

    fun encodeRgb(width: Int, height: Int, rgb: IntArray): ByteArray {
        require(width > 0 && height > 0 && rgb.size == width * height) { "bad image size ${width}x$height/${rgb.size}" }
        val raw = ByteArray((width * 3 + 1) * height)
        var o = 0
        for (y in 0 until height) {
            raw[o++] = 0 // filter type: None
            for (x in 0 until width) {
                val c = rgb[y * width + x]
                raw[o++] = (c ushr 16).toByte()
                raw[o++] = (c ushr 8).toByte()
                raw[o++] = c.toByte()
            }
        }
        val ihdr = ByteSink(13).intBe(width).intBe(height).byte(8).byte(2).byte(0).byte(0).byte(0).toByteArray()
        return ByteSink(raw.size + 128).bytes(SIGNATURE)
            .chunk("IHDR", ihdr)
            .chunk("IDAT", zlibStored(raw))
            .chunk("IEND", ByteArray(0))
            .toByteArray()
    }

    private fun ByteSink.chunk(type: String, data: ByteArray): ByteSink {
        val typed = ByteSink(data.size + 4).ascii(type).bytes(data).toByteArray()
        return intBe(data.size).bytes(typed).intBe(Checksums.crc32(typed))
    }

    private fun zlibStored(data: ByteArray): ByteArray {
        val out = ByteSink(data.size + data.size / MAX_STORED * 5 + 16).byte(0x78).byte(0x01)
        var pos = 0
        do {
            val len = minOf(MAX_STORED, data.size - pos)
            val last = pos + len == data.size
            out.byte(if (last) 1 else 0).shortLe(len).shortLe(len.inv() and 0xFFFF).bytes(data, pos, pos + len)
            pos += len
        } while (!last)
        return out.intBe(Checksums.adler32(data)).toByteArray()
    }
}

internal object Checksums {
    private val CRC_TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun crc32(data: ByteArray): Int {
        var c = -1
        for (b in data) c = CRC_TABLE[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
        return c.inv()
    }

    fun adler32(data: ByteArray): Int {
        var a = 1
        var b = 0
        for (x in data) {
            a = (a + (x.toInt() and 0xFF)) % 65_521
            b = (b + a) % 65_521
        }
        return (b shl 16) or a
    }
}
