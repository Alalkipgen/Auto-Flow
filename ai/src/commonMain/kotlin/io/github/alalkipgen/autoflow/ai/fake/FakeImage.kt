package io.github.alalkipgen.autoflow.ai.fake

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.ImageProvider
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.codec.Png
import io.github.alalkipgen.autoflow.core.model.Ratio
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Offline [ImageProvider]: a solid-colour PNG in the requested [Ratio] (long side [longSide] px), colour picked from
 * a hash of the prompt, with the shot number drawn when the prompt names one ("Shot 3", "shot #12").
 */
class FakeImageProvider(
    private val delayMs: Long = 0,
    private val longSide: Int = 320,
) : ImageProvider {
    var calls: Int = 0
        private set

    override suspend fun image(prompt: String, ratio: Ratio, refImages: List<ByteArray>): AiResult<ByteArray> {
        calls++
        if (delayMs > 0) delay(delayMs)
        if (prompt.isBlank()) return AiResult.Failed("empty_prompt", "Image prompt is empty")
        val (w, h) = sizeFor(ratio, longSide)
        val background = colour(fakeSeed(ratio.label, prompt))
        val pixels = Placeholder.render(w, h, background, shotLabel(prompt).orEmpty())
        return AiResult.Ok(Png.encodeRgb(w, h, pixels), Usage(model = MODEL, latencyMs = delayMs))
    }

    companion object {
        const val MODEL = "fake-image"
        private val SHOT = Regex("""(?i)\bshot\s*#?\s*(\d{1,3})\b""")

        internal fun shotLabel(prompt: String): String? = SHOT.find(prompt)?.groupValues?.get(1)

        internal fun sizeFor(ratio: Ratio, longSide: Int): Pair<Int, Int> =
            if (ratio.width >= ratio.height) {
                longSide to (longSide * ratio.height.toDouble() / ratio.width).roundToInt().coerceAtLeast(1)
            } else {
                (longSide * ratio.width.toDouble() / ratio.height).roundToInt().coerceAtLeast(1) to longSide
            }

        /** Muted colour (HSV s = 0.45, v = 0.7) with the hue from [seed]. */
        internal fun colour(seed: Long): Int {
            val h = ((seed ushr 1) % 360).toInt() / 60.0
            val v = 0.7
            val c = v * 0.45
            val x = c * (1 - kotlin.math.abs(h % 2 - 1))
            val (r, g, b) = when (h.toInt()) {
                0 -> Triple(c, x, 0.0)
                1 -> Triple(x, c, 0.0)
                2 -> Triple(0.0, c, x)
                3 -> Triple(0.0, x, c)
                4 -> Triple(x, 0.0, c)
                else -> Triple(c, 0.0, x)
            }
            val m = v - c
            fun ch(f: Double) = ((f + m) * 255).roundToInt().coerceIn(0, 255)
            return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
    }
}

/** Draws digits with a 5x7 bitmap font, centred, on a solid background. Non-digits are skipped. */
internal object Placeholder {
    private const val FG_LIGHT = 0xFFFFFF
    private const val FG_DARK = 0x111111

    // One Int per row, 5 bits wide (bit 4 = leftmost column).
    private val GLYPHS = arrayOf(
        intArrayOf(0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E), // 0
        intArrayOf(0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E), // 1
        intArrayOf(0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F), // 2
        intArrayOf(0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E), // 3
        intArrayOf(0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02), // 4
        intArrayOf(0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E), // 5
        intArrayOf(0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E), // 6
        intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08), // 7
        intArrayOf(0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E), // 8
        intArrayOf(0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C), // 9
    )

    fun render(width: Int, height: Int, background: Int, label: String): IntArray {
        val pixels = IntArray(width * height) { background }
        val digits = label.filter { it in '0'..'9' }.map { it - '0' }
        if (digits.isEmpty()) return pixels
        val cols = digits.size * 6 - 1 // 5 px glyph + 1 px gap
        val scale = minOf(width * 6 / 10 / cols, height * 4 / 10 / 7)
        if (scale < 1) return pixels
        val left = (width - cols * scale) / 2
        val top = (height - 7 * scale) / 2
        val fg = if (luminance(background) > 140) FG_DARK else FG_LIGHT
        digits.forEachIndexed { i, d ->
            for (row in 0 until 7) for (col in 0 until 5) {
                if (GLYPHS[d][row] shr (4 - col) and 1 == 0) continue
                val x0 = left + (i * 6 + col) * scale
                val y0 = top + row * scale
                for (y in y0 until y0 + scale) for (x in x0 until x0 + scale) pixels[y * width + x] = fg
            }
        }
        return pixels
    }

    private fun luminance(rgb: Int): Int =
        ((rgb ushr 16 and 0xFF) * 299 + (rgb ushr 8 and 0xFF) * 587 + (rgb and 0xFF) * 114) / 1000
}
