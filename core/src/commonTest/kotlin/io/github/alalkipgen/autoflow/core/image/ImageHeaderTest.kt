package io.github.alalkipgen.autoflow.core.image

import io.github.alalkipgen.autoflow.core.model.Ratio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageHeaderTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun png(w: Int, h: Int) = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52) +
        bytes(w ushr 24, (w ushr 16) and 0xFF, (w ushr 8) and 0xFF, w and 0xFF, h ushr 24, (h ushr 16) and 0xFF, (h ushr 8) and 0xFF, h and 0xFF, 8, 6, 0, 0, 0)

    /** SOI, an APP0 segment, a DQT-like segment, then SOF2 (progressive) with height/width. */
    private fun jpeg(w: Int, h: Int) = bytes(0xFF, 0xD8) +
        bytes(0xFF, 0xE0, 0x00, 0x06, 0x4A, 0x46, 0x49, 0x46) +
        bytes(0xFF, 0xDB, 0x00, 0x03, 0x00) +
        bytes(0xFF, 0xC2, 0x00, 0x11, 0x08, h shr 8, h and 0xFF, w shr 8, w and 0xFF, 0x03) + ByteArray(12)

    @Test
    fun readsPngAndJpegSizes() {
        assertEquals(ImageInfo(1080, 1920, "png"), ImageHeader.read(png(1080, 1920)))
        assertEquals(ImageInfo(1920, 1080, "jpg"), ImageHeader.read(jpeg(1920, 1080)))
    }

    @Test
    fun unknownOrTruncatedBytesGiveNull() {
        assertNull(ImageHeader.read(ByteArray(0)))
        assertNull(ImageHeader.read("GIF89a......................".encodeToByteArray()))
        assertNull(ImageHeader.read(png(10, 10).copyOf(20)))
        assertNull(ImageHeader.read(bytes(0xFF, 0xD8, 0xFF, 0xDA, 0, 2, 0, 0)), "scan data before any frame header")
    }

    @Test
    fun ratioMatchAllowsSmallRounding() {
        assertTrue(ImageInfo(1080, 1920, "png").matches(Ratio.R9_16))
        assertTrue(ImageInfo(768, 1376, "png").matches(Ratio.R9_16), "AI Studio sizes are not exact 9:16")
        assertFalse(ImageInfo(1024, 1024, "png").matches(Ratio.R9_16))
        assertTrue(ImageInfo(1024, 1024, "png").matches(Ratio.R1_1))
        assertFalse(ImageInfo(0, 0, "png").matches(Ratio.R1_1))
    }
}
