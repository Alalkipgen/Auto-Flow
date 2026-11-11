package io.github.alalkipgen.autoflow.media.render

/**
 * Family name and libass size factor of a TrueType/OpenType font file (S4). libass sizes a font so that
 * `usWinAscent + usWinDescent` equals the ASS `Fontsize`, so an em of `sizePx` needs
 * `Fontsize = sizePx × assScale` with `assScale = (usWinAscent + usWinDescent) / unitsPerEm`
 * (Noto Sans Myanmar 2.184, Pyidaungsu 1.724). Only the `name`, `head` and `OS/2` tables are read.
 */
data class FontInfo(val family: String, val assScale: Double) {
    companion object {
        /** Null for anything that is not a single sfnt font with those tables (collections, damaged files). */
        fun parse(bytes: ByteArray): FontInfo? = runCatching { read(Bytes(bytes)) }.getOrNull()

        /** OS/2 `usWeightClass` (400 regular, 700 bold) of a font [parse] accepts; null otherwise. */
        fun weightClass(bytes: ByteArray): Int? = runCatching {
            val b = Bytes(bytes)
            if (read(b) == null) return null
            val os2 = (0 until b.u16(4)).map { 12 + 16 * it }.first { b.tag(it) == "OS/2" }
            b.u16(b.u32(os2 + 8).toInt() + 4)
        }.getOrNull()

        private fun read(b: Bytes): FontInfo? {
            val version = b.u32(0)
            if (version != 0x00010000L && version != 0x4F54544FL /* OTTO */ && version != 0x74727565L /* true */) return null
            val tables = (0 until b.u16(4)).associate { i ->
                val rec = 12 + 16 * i
                b.tag(rec) to b.u32(rec + 8).toInt()
            }
            val head = tables["head"] ?: return null
            val os2 = tables["OS/2"] ?: return null
            val name = tables["name"] ?: return null
            val unitsPerEm = b.u16(head + 18)
            if (unitsPerEm == 0) return null
            val scale = (b.u16(os2 + 74) + b.u16(os2 + 76)).toDouble() / unitsPerEm
            val family = family(b, name) ?: return null
            return FontInfo(family, scale)
        }

        /** nameID 16 (typographic family), else 1 (family); Windows Unicode (UTF-16BE) records first, then Mac Roman. */
        private fun family(b: Bytes, table: Int): String? {
            val count = b.u16(table + 2)
            val strings = table + b.u16(table + 4)
            val records = (0 until count).map { i ->
                val r = table + 6 + 12 * i
                NameRecord(b.u16(r), b.u16(r + 6), strings + b.u16(r + 10), b.u16(r + 8))
            }
            for (id in listOf(16, 1)) {
                records.firstOrNull { it.nameId == id && (it.platform == 3 || it.platform == 0) }?.let { r ->
                    return buildString { for (k in 0 until r.length / 2) append(b.u16(r.offset + 2 * k).toChar()) }
                }
                records.firstOrNull { it.nameId == id && it.platform == 1 }?.let { r ->
                    return buildString { for (k in 0 until r.length) append(b.u8(r.offset + k).toChar()) }
                }
            }
            return null
        }

        private data class NameRecord(val platform: Int, val nameId: Int, val offset: Int, val length: Int)

        private class Bytes(val a: ByteArray) {
            fun u8(i: Int): Int = a[i].toInt() and 0xFF
            fun u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
            fun u32(i: Int): Long = (u16(i).toLong() shl 16) or u16(i + 2).toLong()
            fun tag(i: Int): String = (0 until 4).map { u8(i + it).toChar() }.joinToString("")
        }
    }
}
