package io.github.alalkipgen.autoflow.ai.codec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodecTest {
    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
    private fun ByteArray.be32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)

    @Test
    fun checksumsMatchReferenceValues() {
        assertEquals(0xCBF43926.toInt(), Checksums.crc32("123456789".encodeToByteArray()))
        assertEquals(0x11E60398, Checksums.adler32("Wikipedia".encodeToByteArray()))
        assertEquals(1, Checksums.adler32(ByteArray(0)))
    }

    @Test
    fun pngHasSignatureHeaderAndValidChunkCrcs() {
        val png = Png.encodeRgb(3, 2, IntArray(6) { 0x336699 })
        assertContentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), png.copyOf(8))
        val types = mutableListOf<String>()
        var pos = 8
        while (pos < png.size) {
            val len = png.be32(pos)
            val typed = png.copyOfRange(pos + 4, pos + 8 + len)
            types += typed.copyOf(4).decodeToString()
            assertEquals(Checksums.crc32(typed), png.be32(pos + 8 + len), "CRC of ${types.last()}")
            pos += 12 + len
        }
        assertEquals(listOf("IHDR", "IDAT", "IEND"), types)
        assertEquals(3, png.be32(16))
        assertEquals(2, png.be32(20))
        assertEquals(listOf(8, 2), listOf(png.u8(24), png.u8(25))) // 8-bit RGB
    }

    @Test
    fun wavRoundTripsHeaderAndDuration() {
        val wav = Wav.pcm16Mono(ShortArray(24_000 * 3 / 2), 24_000)
        assertEquals(44 + 72_000, wav.size)
        assertEquals(Wav.Info(24_000, 1, 16, 72_000), Wav.parse(wav))
        assertEquals(1_500, Wav.parse(wav)!!.durationMs)
    }

    @Test
    fun wavParseRejectsNonWav() {
        assertNull(Wav.parse("ID3 not a wav file".encodeToByteArray()))
        assertNull(Wav.parse(ByteArray(0)))
        assertNull(Wav.parse(Wav.pcm16Mono(ShortArray(4), 8_000).copyOf(30))) // truncated before fmt ends
    }
}
