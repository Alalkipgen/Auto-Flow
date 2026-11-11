package io.github.alalkipgen.autoflow.media.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FontInfoTest {
    @Test
    fun readsFamilyAndLibassScale() {
        val font = sfnt(unitsPerEm = 1000, winAscent = 1520, winDescent = 664, names = listOf(Name(3, 1, "Noto Sans Myanmar")))
        assertEquals(FontInfo("Noto Sans Myanmar", 2.184), FontInfo.parse(font))
    }

    @Test
    fun prefersTheTypographicFamilyAndReadsMacRoman() {
        val font = sfnt(2048, 2600, 930, listOf(Name(1, 1, "Pyidaungsu Regular"), Name(3, 16, "Pyidaungsu")))
        assertEquals("Pyidaungsu", FontInfo.parse(font)?.family)
        assertEquals("Pyi", FontInfo.parse(sfnt(2048, 2600, 930, listOf(Name(1, 1, "Pyi"))))?.family)
        assertEquals(3530.0 / 2048, FontInfo.parse(font)!!.assScale)
    }

    @Test
    fun rejectsCollectionsAndDamagedFiles() {
        assertNull(FontInfo.parse("ttcf\u0000\u0001".encodeToByteArray()))
        assertNull(FontInfo.parse(ByteArray(3)))
        assertNull(FontInfo.parse(sfnt(1000, 1, 1, listOf(Name(3, 1, "X"))).copyOf(40)))
    }

    private class Name(val platform: Int, val id: Int, val text: String)

    /** A minimal sfnt: offset table + `head`, `OS/2`, `name` (only the fields FontInfo reads are meaningful). */
    private fun sfnt(unitsPerEm: Int, winAscent: Int, winDescent: Int, names: List<Name>): ByteArray {
        val head = ByteArray(54).also { put16(it, 18, unitsPerEm) }
        val os2 = ByteArray(78).also { put16(it, 74, winAscent); put16(it, 76, winDescent) }
        val encoded = names.map { n -> if (n.platform == 1) n.text.encodeToByteArray() else utf16(n.text) }
        val strings = 6 + 12 * names.size
        val name = ByteArray(strings + encoded.sumOf { it.size })
        put16(name, 2, names.size)
        put16(name, 4, strings)
        var off = 0
        names.forEachIndexed { i, n ->
            val r = 6 + 12 * i
            put16(name, r, n.platform)
            put16(name, r + 6, n.id)
            put16(name, r + 8, encoded[i].size)
            put16(name, r + 10, off)
            encoded[i].copyInto(name, strings + off)
            off += encoded[i].size
        }
        val tables = listOf("OS/2" to os2, "head" to head, "name" to name)
        val out = ByteArray(12 + 16 * tables.size + tables.sumOf { it.second.size })
        put16(out, 0, 1)
        put16(out, 4, tables.size)
        var at = 12 + 16 * tables.size
        tables.forEachIndexed { i, (tag, data) ->
            val rec = 12 + 16 * i
            tag.encodeToByteArray().copyInto(out, rec)
            put16(out, rec + 10, at)
            data.copyInto(out, at)
            at += data.size
        }
        return out
    }

    private fun utf16(s: String) = ByteArray(s.length * 2).also { b -> s.forEachIndexed { i, c -> put16(b, 2 * i, c.code) } }

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v shr 8).toByte()
        b[i + 1] = v.toByte()
    }
}
