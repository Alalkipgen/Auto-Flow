package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.Ratio
import kotlin.test.Test
import kotlin.test.assertEquals

class AssWriterTest {
    private val noto = "Noto Sans Myanmar"

    @Test
    fun writesOneStylePerFontSizeColourAndPositionedDialogues() {
        val overlays = listOf(
            Overlay("ကမ္ဘာ ကြွက်\nညှိ ဉာဏ်", 5_000, 8_000, noto, 64, position = Pos.BOTTOM, anim = Anim.FADE),
            Overlay("Title", 0, 2_000, noto, 64, color = "#80FFCC00", position = Pos.TOP),
            Overlay("မိုး", 2_000, 2_400, noto, 64, position = Pos.CENTER, anim = Anim.FADE),
        )
        val ass = AssWriter.write(overlays, RenderPresets.export(Ratio.R9_16)) { if (it == noto) 2.184 else null }
        val expected = """
            [Script Info]
            ; Written by Auto Flow from the render graph; edits are overwritten on the next render.
            ScriptType: v4.00+
            PlayResX: 1080
            PlayResY: 1920
            WrapStyle: 2
            ScaledBorderAndShadow: yes

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: s1,Noto Sans Myanmar,140,&H00FFFFFF,&H00FFFFFF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,5,0,2,0,0,0,1
            Style: s2,Noto Sans Myanmar,140,&H7F00CCFF,&H7F00CCFF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,5,0,2,0,0,0,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:00.00,0:00:02.00,s2,,0,0,0,,{\an8\pos(540,153)}Title
            Dialogue: 0,0:00:02.00,0:00:02.40,s1,,0,0,0,,{\an5\pos(540,960)\fad(200,200)}မိုး
            Dialogue: 0,0:00:05.00,0:00:08.00,s1,,0,0,0,,{\an2\pos(540,1767)\fad(300,300)}ကမ္ဘာ ကြွက်\Nညှိ ဉာဏ်

        """.trimIndent()
        assertEquals(expected, ass)
    }

    @Test
    fun unknownFontUsesScaleOne() {
        val ass = AssWriter.write(listOf(Overlay("a", 0, 1_000, "Other, Font", 50)), RenderPresets.preview(Ratio.R16_9)) { null }
        check("Style: s1,Other  Font,50," in ass) { ass }
        check("PlayResX: 960\nPlayResY: 540\n" in ass) { ass }
    }

    @Test
    fun timesAreCentiseconds() {
        assertEquals("0:00:00.00", AssWriter.time(0))
        assertEquals("0:00:01.23", AssWriter.time(1_239))
        assertEquals("1:02:03.40", AssWriter.time(3_723_400))
    }

    @Test
    fun coloursBecomeAssBgrWithInvertedAlpha() {
        assertEquals("&H000000FF", AssWriter.assColor("#FF0000"))
        assertEquals("&H00FFFFFF", AssWriter.assColor("white"))
        assertEquals("&HFF563412", AssWriter.assColor("#00123456"))
        assertEquals("&H00563412", AssWriter.assColor("#ff123456"))
    }

    @Test
    fun textCannotOpenOverrideBlocksOrEscapes() {
        assertEquals("a\\{b\\}c\\N\\\u2060Nd", AssWriter.escape("a{b}c\r\n\\Nd"))
    }
}
