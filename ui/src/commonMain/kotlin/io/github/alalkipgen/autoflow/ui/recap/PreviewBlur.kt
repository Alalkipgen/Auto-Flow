package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.ui.graphics.ImageBitmap
import io.github.alalkipgen.autoflow.ui.components.bitmapFromArgb
import kotlin.math.*

/** Three separable box passes approximate a Gaussian in the interactive proxy, not ffmpeg's exact kernel. */
internal fun previewBlur(image: ImageBitmap, x: Int, y: Int, w: Int, h: Int, sigma: Float, feather: Int = 0): ImageBitmap {
    var src = IntArray(w * h)
    image.readPixels(src, x, y, w, h)
    val ideal = sqrt(4.0 * sigma * sigma + 1.0)
    var low = floor(ideal).toInt().coerceAtLeast(1)
    if (low % 2 == 0) low--
    val high = low + 2
    val count = ((12 * sigma * sigma - 3 * low * low - 12 * low - 9) / (-4f * low - 4)).roundToInt().coerceIn(0, 3)
    repeat(3) { pass ->
        val radius = ((if (pass < count) low else high) - 1) / 2
        for (horizontal in listOf(true, false)) {
            val dst = IntArray(src.size)
            val len = if (horizontal) w else h
            val lines = if (horizontal) h else w
            fun index(line: Int, pos: Int) = if (horizontal) line * w + pos.coerceIn(0, w - 1) else pos.coerceIn(0, h - 1) * w + line
            repeat(lines) { line ->
                var r = 0L; var g = 0L; var b = 0L
                fun add(pos: Int, sign: Int) {
                    val p = src[index(line, pos)]
                    r += ((p ushr 16) and 255) * sign; g += ((p ushr 8) and 255) * sign; b += (p and 255) * sign
                }
                for (p in -radius..radius) add(p, 1)
                val divisor = radius * 2 + 1
                repeat(len) { pos ->
                    dst[index(line, pos)] = (255 shl 24) or ((r / divisor).toInt() shl 16) or ((g / divisor).toInt() shl 8) or (b / divisor).toInt()
                    add(pos - radius, -1); add(pos + radius + 1, 1)
                }
            }
            src = dst
        }
    }
    if (feather > 0) for (yy in 0 until h) for (xx in 0 until w) {
        val edge = minOf(xx, w - 1 - xx, yy, h - 1 - yy) + 1
        src[yy * w + xx] = (src[yy * w + xx] and 0xFFFFFF) or ((minOf(edge, feather) * 255 / feather) shl 24)
    }
    return bitmapFromArgb(w, h, src)
}
