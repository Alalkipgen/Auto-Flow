package io.github.alalkipgen.autoflow.core.subtitles

import io.github.alalkipgen.autoflow.core.recap.SrtCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTime

class SrtImportTest {
    private val srt = "1\r\n00:00:01,000 --> 00:00:02,500\r\n<i>Café</i> “quoted”\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nမင်္ဂလာပါ\r\n"

    private fun ok(r: SrtImportResult) = assertIs<SrtImportResult.Ok>(r)

    private fun utf16(text: String, littleEndian: Boolean): ByteArray {
        val out = ArrayList<Byte>()
        out += if (littleEndian) listOf(0xFF.toByte(), 0xFE.toByte()) else listOf(0xFE.toByte(), 0xFF.toByte())
        for (c in text) {
            val hi = (c.code shr 8).toByte(); val lo = (c.code and 0xFF).toByte()
            out += if (littleEndian) listOf(lo, hi) else listOf(hi, lo)
        }
        return out.toByteArray()
    }

    @Test
    fun everySupportedEncodingGivesTheSameCues() {
        val expected = listOf(SrtCue(1_000, 2_500, "Café “quoted”"), SrtCue(3_000, 4_000, "မင်္ဂလာပါ"))
        val utf8 = srt.encodeToByteArray()
        assertEquals(expected to SrtEncoding.UTF8, ok(SrtImport.read(utf8)).let { it.cues to it.encoding })
        assertEquals(SrtEncoding.UTF8_BOM, ok(SrtImport.read(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8)).encoding)
        assertEquals(expected to SrtEncoding.UTF16LE_BOM, ok(SrtImport.read(utf16(srt, true))).let { it.cues to it.encoding })
        assertEquals(expected to SrtEncoding.UTF16BE_BOM, ok(SrtImport.read(utf16(srt, false))).let { it.cues to it.encoding })
    }

    @Test
    fun invalidUtf8IsReadAsWindows1252() {
        val latin = "1\n00:00:01,000 --> 00:00:02,000\nCaf".encodeToByteArray() + byteArrayOf(0xE9.toByte(), 0x20, 0x93.toByte(), 0x78, 0x94.toByte(), 0x80.toByte())
        val r = ok(SrtImport.read(latin))
        assertEquals(SrtEncoding.WINDOWS_1252, r.encoding)
        assertEquals("Café “x”€", r.cues.single().text)
    }

    @Test
    fun emptyAndBrokenFilesSaveNothingAndSayWhere() {
        assertEquals(SrtImportResult.Empty, SrtImport.read(byteArrayOf()))
        assertEquals(SrtImportResult.Empty, SrtImport.read(" \n\n \r\n".encodeToByteArray()))
        val badTime = "1\n00:00:01,000 --> 00:00:02,000\nA\n\n7\n00:00:03,000 -> 00:00:04,000\nB\n"
        assertEquals(SrtImportResult.Broken(7), SrtImport.parse(badTime))
        val backwards = "1\n00:00:01,000 --> 00:00:02,000\nA\n\n2\n00:00:05,000 --> 00:00:04,000\nB\n"
        assertEquals(SrtImportResult.Broken(2), SrtImport.parse(backwards))
        val unnumbered = "00:00:01,000 --> 00:00:02,000\nA\n\njust some text\n"
        assertEquals(SrtImportResult.Broken(2), SrtImport.parse(unnumbered))
    }

    @Test
    fun aLongFileImportsQuickly() {
        val text = buildString { for (i in 0 until 2_000) append("${i + 1}\n${stamp(i * 2_000L)} --> ${stamp(i * 2_000L + 1_500)}\nLine $i\n\n") }
        lateinit var r: SrtImportResult.Ok
        val took = measureTime { r = ok(SrtImport.parse(text)) }
        assertEquals(2_000, r.cues.size)
        val doc = SubtitleOps.fromCues(r.cues, SubtitleDoc.SOURCE_SRT)
        assertEquals("c2000" to 2_001, doc.cues.last().id to doc.nextId)
        assertTrue(took.inWholeSeconds < 5, "took $took")
    }

    @Test
    fun zawgyiIsDetectedButUnicodeAndEnglishAreNot() {
        assertTrue(Zawgyi.likely("\u1031\u107E\u1000\u102C\u1004\u1039")) // ေၾကာင္ (Zawgyi "cat")
        assertTrue(Zawgyi.likely("ဒီည ထ\u103C\u1000\u1039သ\u103C\u102C\u1038ရမယ\u1039။ \u1031နာက\u1039 \u107F\u1015\u102E")) // Zawgyi spelling (ေနာက္ ၿပီ)
        assertFalse(Zawgyi.likely("ဒီည ထွက်သွားရမယ်။"))
        assertFalse(Zawgyi.likely("ကြောင် မြန်မာ မင်္ဂလာပါ"))
        assertFalse(Zawgyi.likely("We have to leave tonight."))
        val zawgyiFile = "1\n00:00:01,000 --> 00:00:02,000\n\u1031\u107E\u1000\u102C\u1004\u1039\n"
        assertTrue(ok(SrtImport.parse(zawgyiFile)).zawgyiLikely)
        assertFalse(ok(SrtImport.parse(srt)).zawgyiLikely)
    }

    private fun stamp(ms: Long) = io.github.alalkipgen.autoflow.core.recap.Srt.stamp(ms)
}
