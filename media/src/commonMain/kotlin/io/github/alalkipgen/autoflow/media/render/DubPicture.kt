package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.dubbing.DubMarks
import io.github.alalkipgen.autoflow.core.model.DubMask
import io.github.alalkipgen.autoflow.core.recap.WatermarkAnchor

/**
 * The watermark / logo on a dub (D-108): the image [file] scaled to [widthPx] (aspect kept) at [anchor], at [opacity].
 * The export reads it as one more input (a single picture, held for the whole video by `overlay`).
 */
data class DubMark(val file: String, val widthPx: Int, val anchor: WatermarkAnchor = WatermarkAnchor.BOTTOM_RIGHT,
    val opacity: Float = DubMarks.OPACITY)

/**
 * What a dub draws on the video's picture (D-107 / D-108, specs/dubbing.md §2 Style), in this order so the Burmese
 * subtitles stay on top: the blur / colour boxes ([masks], blurred at [sigma] px), the watermark ([mark]), then the
 * subtitles ([ass], fonts in [fontsDir]). Nothing drawn = the picture is copied as it is.
 */
data class DubPicture(val masks: List<DubMask> = emptyList(), val sigma: Int = 32, val ass: String? = null, val fontsDir: String = "",
    val mark: DubMark? = null) {
    val drawn: Boolean get() = masks.isNotEmpty() || ass != null || mark != null

    /**
     * The video chain from `[0:v]` to `[vout]`. A blurred box = split, crop the box (0–1 of the picture), `gblur`,
     * overlay it back (as Movie Recap's boxes); a colour box = `drawbox … t=fill`. The watermark = input [markInput]
     * scaled, made see-through and overlaid at its anchor (as Recap's IMAGE layer). Subtitles only = the burn's chain as
     * before (`[0:v]ass=…[vout]`).
     */
    fun chain(markInput: Int = 2): String {
        val parts = mutableListOf<String>()
        var cur = "0:v"
        masks.forEachIndexed { j, m ->
            val (x, y, w, h) = (0..3).map { i -> f3(m.rect.getOrElse(i) { if (i < 2) 0f else 1f }) }
            val l = "dm$j"
            val fill = m.fill
            parts += if (fill == null)
                "[$cur]split[${l}a][${l}b];[${l}b]crop=w=iw*$w:h=ih*$h:x=iw*$x:y=ih*$y,gblur=sigma=$sigma[${l}c];[${l}a][${l}c]overlay=x=main_w*$x:y=main_h*$y[$l]"
            else "[$cur]drawbox=x=iw*$x:y=ih*$y:w=iw*$w:h=ih*$h:color=${hex(fill)}@1:t=fill[$l]"
            cur = l
        }
        mark?.let { m ->
            val a = kotlin.math.round(m.opacity.coerceIn(0f, 1f) * 100).toInt().let { if (it >= 100) "1" else if (it <= 0) "0" else "0.${it.toString().padStart(2, '0').trimEnd('0')}" }
            parts += "[$markInput:v]scale=${m.widthPx}:-2,format=yuva420p,colorchannelmixer=aa=$a[dwi]"
            parts += "[$cur][dwi]overlay=x=${DubMarks.overlayX(m.anchor)}:y=${DubMarks.overlayY(m.anchor)}[dw]"
            cur = "dw"
        }
        parts += if (ass != null) "[$cur]${SubtitleBurn.filter(ass, fontsDir)}[vout]" else "[$cur]null[vout]"
        return parts.joinToString(";")
    }

    /** A 0–1 value with at most three decimals, never in exponent form (as [RenderCompiler]'s boxes). */
    private fun f3(v: Float): String {
        val r = kotlin.math.round(v.coerceIn(0f, 1f) * 1000).toInt()
        return if (r % 1000 == 0) "${r / 1000}" else "${r / 1000}.${(r % 1000).toString().padStart(3, '0').trimEnd('0')}"
    }

    /** `#RRGGBB` → `0xRRGGBB` (black when it is not a colour). */
    private fun hex(c: String): String = c.removePrefix("#").takeIf { it.length == 6 && it.all { ch -> ch.isDigit() || ch.uppercaseChar() in 'A'..'F' } }
        ?.let { "0x${it.uppercase()}" } ?: "0x000000"
}
