package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.media.audio.LoudnessMeasurement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 5B-4: the record a finished render leaves beside its mp4, read back after a restart. */
class RenderResultTest {
    private val fs = InMemoryFileSystem().apply {
        createDirectories("/p/exports")
        writeDurably("/p/exports/export.mp4", ByteArray(1234))
    }
    private val m = LoudnessMeasurement(-29.7, -18.2, 3.1, -40.0, 0.4)

    private fun write(r: RenderResult) = fs.writeDurably("/p/exports/export.result.json", r.encode())

    @Test
    fun twoPassAndFallbackReadBackExactly() {
        val two = RenderResult("export.mp4", 60_000, 9_000, 1234, RenderState.Loudness(-14.0, m, null))
        write(two)
        assertEquals(two, RenderResult.read(fs, "/p/exports/export.mp4"))
        assertTrue(RenderResult.read(fs, "/p/exports/export.mp4")!!.loudness!!.twoPass)
        val one = two.copy(loudness = RenderState.Loudness(-14.0, null, "the loudness could not be measured, so one-pass loudnorm was used"))
        write(one)
        val back = RenderResult.read(fs, "/p/exports/export.mp4")!!
        assertEquals(one, back)
        assertFalse(back.loudness!!.twoPass)
        assertEquals("/p/exports/export.result.json", RenderResult.path("/p/exports/export.mp4"))
    }

    @Test
    fun missingStaleForeignOrDamagedRecordsAreNotShown() {
        assertNull(RenderResult.read(fs, "/p/exports/export.mp4"), "no record")
        write(RenderResult("export.mp4", 60_000, 9_000, 999, null))
        assertNull(RenderResult.read(fs, "/p/exports/export.mp4"), "size differs: another video")
        write(RenderResult("preview.mp4", 60_000, 9_000, 1234, null))
        assertNull(RenderResult.read(fs, "/p/exports/export.mp4"), "a record of another file")
        fs.writeDurably("/p/exports/export.result.json", "{not json".encodeToByteArray())
        assertNull(RenderResult.read(fs, "/p/exports/export.mp4"), "damaged JSON")
        write(RenderResult("export.mp4", 60_000, 9_000, 1234, null))
        assertNull(RenderResult.read(fs, "/p/exports/preview.mp4"), "no video")
    }

    @Test
    fun nonFiniteNumbersNeverReadAsTwoPass() {
        // JSON cannot carry NaN; a hand-edited or damaged record with huge numbers decodes to Infinity.
        val text = RenderResult("export.mp4", 60_000, 9_000, 1234, RenderState.Loudness(-14.0, m, null)).encode().decodeToString()
            .replace("-29.7", "-1e999")
        fs.writeDurably("/p/exports/export.result.json", text.encodeToByteArray())
        val r = RenderResult.read(fs, "/p/exports/export.mp4")
        // Either the decoder refuses the number (no record shown) or the numbers are dropped with a note.
        assertFalse(r?.loudness?.twoPass == true, "a damaged record read as two-pass: $r")
        r?.loudness?.let { assertEquals(RenderResult.UNREADABLE, it.note) }
    }
}
