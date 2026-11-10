package io.github.alalkipgen.autoflow.core.image

import io.github.alalkipgen.autoflow.core.model.Ratio
import kotlin.math.abs

/** Pixel size of an imported reference image; [format] is `"png"` or `"jpg"`. */
data class ImageInfo(val width: Int, val height: Int, val format: String) {
    /** True when the image has [ratio]'s shape within [tolerance] (relative), e.g. 1080×1920 for 9:16. */
    fun matches(ratio: Ratio, tolerance: Double = 0.03): Boolean {
        if (width <= 0 || height <= 0) return false
        val want = ratio.width.toDouble() / ratio.height
        return abs(width.toDouble() / height - want) / want <= tolerance
    }
}

/**
 * Reads the size from a PNG (IHDR) or JPEG (SOFn) header without decoding the image, so `:core` stays free of image
 * libraries. Used for the "image ratio ≠ project ratio" warning (specs/flow-handoff.md "Import Image").
 */
object ImageHeader {
    private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    /** `null` when the bytes are not a PNG or JPEG this reader understands. */
    fun read(bytes: ByteArray): ImageInfo? = when {
        bytes.size >= 24 && bytes.copyOfRange(0, 8).contentEquals(PNG) -> ImageInfo(int32(bytes, 16), int32(bytes, 20), "png")
        bytes.size >= 4 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8 -> jpeg(bytes)
        else -> null
    }

    private fun jpeg(b: ByteArray): ImageInfo? {
        var i = 2
        while (i + 3 < b.size) {
            if (u8(b, i) != 0xFF) return null
            val marker = u8(b, i + 1)
            when {
                marker == 0xFF -> i++ // fill byte
                marker == 0x01 || marker in 0xD0..0xD8 -> i += 2 // no length
                marker == 0xDA || marker == 0xD9 -> return null // scan data or end before a frame header
                marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> {
                    if (i + 8 >= b.size) return null
                    return ImageInfo(width = u16(b, i + 7), height = u16(b, i + 5), format = "jpg")
                }
                else -> i += 2 + u16(b, i + 2)
            }
        }
        return null
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun int32(b: ByteArray, i: Int) = (u16(b, i) shl 16) or u16(b, i + 2)
}
