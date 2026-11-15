package io.github.alalkipgen.autoflow.media.render

/**
 * Overlays → the `.ass` file that the compiler's `ass=` filter draws (specs/render-graph.md "Text overlays", S4):
 * `PlayResX/Y` = output size, one `Style` per font / size / colour, one `Dialogue` per overlay with `\an` + `\pos`
 * from [Pos] and `\fad` for [Anim.FADE]. No auto-wrap (`WrapStyle: 2`): line breaks come only from `\n` in the text.
 */
object AssWriter {
    const val FADE_MS = 300

    /** [assScale] maps a font family to its [FontInfo.assScale]; unknown families use 1.0. */
    fun write(overlays: List<Overlay>, output: Output, assScale: (String) -> Double?): String {
        val styles = overlays.map { key(it, assScale) }.distinct()
        val w = output.width
        val h = output.height
        return buildString {
            append("[Script Info]\n")
            append("; Written by Auto Flow from the render graph; edits are overwritten on the next render.\n")
            append("ScriptType: v4.00+\n")
            append("PlayResX: $w\nPlayResY: $h\n")
            append("WrapStyle: 2\nScaledBorderAndShadow: yes\n\n")
            append("[V4+ Styles]\n")
            append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, ")
            append("Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
            styles.forEachIndexed { i, s ->
                if (s.box) {
                    // BorderStyle 3 = opaque box; libass fills it with OutlineColour, Outline = the padding.
                    val pad = maxOf(4, s.size / 6)
                    append("Style: s${i + 1},${s.font.replace(',', ' ')},${s.size},${s.color},${s.color},$BOX_COLOUR,$BOX_COLOUR,0,0,0,0,100,100,0,0,3,$pad,0,2,0,0,0,1\n")
                } else {
                    // Outline width: custom strokeWidth if >0, else auto (size/24 clamped to ≥2).
                    val outline = if (s.strokeWidth > 0) s.strokeWidth else maxOf(2, s.size / 24)
                    val outlineColour = if (s.strokeWidth > 0) s.strokeColour else "&H00000000"
                    append("Style: s${i + 1},${s.font.replace(',', ' ')},${s.size},${s.color},${s.color},$outlineColour,&H80000000,0,0,0,0,100,100,0,0,1,$outline,0,2,0,0,0,1\n")
                }
            }
            append("\n[Events]\n")
            append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
            overlays.sortedBy { it.startMs }.forEach { o ->
                val style = styles.indexOf(key(o, assScale)) + 1
                val centreY = o.centerY
                val centreX = o.centerX
                val (an, x, y) = if (centreY != null || centreX != null) Triple(
                    5,
                    centreX?.let { (w * it.coerceIn(0f, 1f)).toInt() } ?: (w / 2),
                    centreY?.let { (h * it.coerceIn(0.05f, 0.95f)).toInt() } ?: (h / 2),
                ) else when (o.position) {
                    Pos.TOP -> Triple(8, w / 2, h * MARGIN_PERCENT / 100)
                    Pos.CENTER -> Triple(5, w / 2, h / 2)
                    Pos.BOTTOM -> Triple(2, w / 2, h - h * MARGIN_PERCENT / 100)
                }
                val fade = if (o.anim == Anim.FADE) {
                    val ms = minOf(FADE_MS.toLong(), (o.endMs - o.startMs) / 2)
                    "\\fad($ms,$ms)"
                } else {
                    ""
                }
                append("Dialogue: 0,${time(o.startMs)},${time(o.endMs)},s$style,,0,0,0,,{\\an$an\\pos($x,$y)$fade}${escape(o.text)}\n")
            }
        }
    }

    /** `H:MM:SS.cc`, centiseconds rounded down. */
    fun time(ms: Long): String {
        val cs = maxOf(0, ms) / 10
        val s = cs / 100
        return "${s / 3600}:${pad(s / 60 % 60)}:${pad(s % 60)}.${pad(cs % 100)}"
    }

    /** `#RRGGBB` / `#AARRGGBB` → `&HAABBGGRR` (ASS alpha 00 = opaque); anything else is white. */
    fun assColor(color: String): String {
        val hex = color.removePrefix("#").uppercase()
        if (!hex.all { it in "0123456789ABCDEF" } || (hex.length != 6 && hex.length != 8)) return "&H00FFFFFF"
        val alpha = if (hex.length == 8) 255 - hex.substring(0, 2).toInt(16) else 0
        val rgb = hex.takeLast(6)
        return "&H" + hex2(alpha) + rgb.substring(4, 6) + rgb.substring(2, 4) + rgb.substring(0, 2)
    }

    /**
     * User text → ASS text: `\n` → `\N`; `{` / `}` → libass escapes `\{` / `\}` (else they open an override block);
     * a backslash gets a word joiner after it so it never forms `\N`, `\n`, `\h` with the next letter.
     */
    fun escape(text: String): String = buildString {
        for (c in text.replace("\r\n", "\n")) {
            when (c) {
                '\n' -> append("\\N")
                '\\' -> append("\\\u2060")
                '{' -> append("\\{")
                '}' -> append("\\}")
                else -> append(c)
            }
        }
    }

    private fun fontSize(o: Overlay, assScale: (String) -> Double?): Int =
        maxOf(1, kotlin.math.round(o.sizePx * (assScale(o.font) ?: 1.0)).toInt())

    /** The style an overlay uses; the Style lines and each Dialogue's `sN` come from this one key. */
    private fun key(o: Overlay, assScale: (String) -> Double?) =
        StyleKey(o.font, fontSize(o, assScale), assColor(o.color), o.box, o.strokeWidth, assColor(o.strokeColour))

    private data class StyleKey(val font: String, val size: Int, val color: String, val box: Boolean = false, val strokeWidth: Int = 0, val strokeColour: String = "&H00000000")

    private fun pad(v: Long) = v.toString().padStart(2, '0')

    private fun hex2(v: Int) = v.toString(16).uppercase().padStart(2, '0')

    private const val MARGIN_PERCENT = 8

    /** Black at about 40 % see-through for caption boxes. */
    private const val BOX_COLOUR = "&H60000000"
}
