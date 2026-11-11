package io.github.alalkipgen.autoflow.media.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FontInfoTest {
    @Test
    fun readsFamilyAndLibassScale() {
        val font = Sfnt.font(unitsPerEm = 1000, winAscent = 1520, winDescent = 664, names = listOf(Sfnt.Name(3, 1, "Noto Sans Myanmar")))
        assertEquals(FontInfo("Noto Sans Myanmar", 2.184), FontInfo.parse(font))
    }

    @Test
    fun prefersTheTypographicFamilyAndReadsMacRoman() {
        val font = Sfnt.font(2048, 2600, 930, listOf(Sfnt.Name(1, 1, "Pyidaungsu Regular"), Sfnt.Name(3, 16, "Pyidaungsu")))
        assertEquals("Pyidaungsu", FontInfo.parse(font)?.family)
        assertEquals("Pyi", FontInfo.parse(Sfnt.font(2048, 2600, 930, listOf(Sfnt.Name(1, 1, "Pyi"))))?.family)
        assertEquals(3530.0 / 2048, FontInfo.parse(font)!!.assScale)
    }

    @Test
    fun readsTheWeightClass() {
        val names = listOf(Sfnt.Name(3, 1, "Noto Sans Myanmar"))
        assertEquals(400, FontInfo.weightClass(Sfnt.font(1000, 1520, 664, names)))
        assertEquals(700, FontInfo.weightClass(Sfnt.font(1000, 1520, 664, names, weight = 700)))
        assertNull(FontInfo.weightClass(ByteArray(3)))
    }

    @Test
    fun rejectsCollectionsAndDamagedFiles() {
        assertNull(FontInfo.parse("ttcf\u0000\u0001".encodeToByteArray()))
        assertNull(FontInfo.parse(ByteArray(3)))
        assertNull(FontInfo.parse(Sfnt.font(1000, 1, 1, listOf(Sfnt.Name(3, 1, "X"))).copyOf(40)))
    }
}
