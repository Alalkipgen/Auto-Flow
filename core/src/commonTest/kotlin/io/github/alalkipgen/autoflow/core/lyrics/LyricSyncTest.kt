package io.github.alalkipgen.autoflow.core.lyrics

import io.github.alalkipgen.autoflow.core.model.CoreJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LyricSyncTest {
    private val lyrics = "[Verse]\n  မိုးတွေရွာ  \n\nည\n[Chorus 2]\nနောက်ဆုံး\n"

    @Test
    fun linesSkipBlanksAndSectionMarkers() {
        assertEquals(listOf("မိုးတွေရွာ", "ည", "နောက်ဆုံး"), LyricSync.lines(lyrics))
        assertEquals(listOf("[not a marker"), LyricSync.lines("[not a marker"))
    }

    @Test
    fun tapsMarkStartsThenTheLastEnd() {
        var s = TapSync(LyricSync.lines(lyrics))
        assertEquals("မိုးတွေရွာ", s.nextLine)
        assertNull(s.currentLine)
        s = s.tap(1_000)
        assertSame(s, s.tap(1_100), "a double tap is ignored")
        s = s.tap(4_000).tap(30_000)
        assertTrue(s.complete)
        assertEquals("နောက်ဆုံး", s.currentLine)
        assertNull(s.nextLine)
        assertEquals(
            listOf(LyricLine("မိုးတွေရွာ", 1_000, 4_000), LyricLine("ည", 4_000, 14_000), LyricLine("နောက်ဆုံး", 30_000, 36_000)),
            s.timings(),
            "an instrumental gap caps a line at 10 s; the last line gets 6 s",
        )
        assertEquals(33_000, s.timings(songMs = 33_000).last().endMs, "cut at the song end")
        s = s.tap(32_500)
        assertNull(s.currentLine)
        assertEquals(32_500, s.timings().last().endMs)
        assertSame(s, s.tap(40_000), "nothing left to mark")
        s = s.undo()
        assertEquals(36_000, s.timings().last().endMs)
        s = s.undo()
        assertFalse(s.complete)
        assertEquals("နောက်ဆုံး", s.nextLine)
        assertEquals(emptyList(), s.restart().starts)
    }

    @Test
    fun resumeOnlyWhenTheLinesStillMatch() {
        val lines = LyricSync.lines(lyrics)
        val saved = LyricTimings(lines = TapSync(lines, listOf(1, 2_000, 3_000), 4_000).timings())
        assertEquals(TapSync(lines, listOf(1, 2_000, 3_000), 4_000), TapSync.resume(lines, saved))
        assertEquals(TapSync(lines.drop(1)), TapSync.resume(lines.drop(1), saved))
        assertTrue(LyricSync.matches(saved, lyrics))
        assertFalse(LyricSync.matches(saved, "$lyrics\nnew line"))
    }

    @Test
    fun timingsJsonShape() {
        val t = LyricTimings(lines = listOf(LyricLine("a", 0, 1_000)))
        val json = CoreJson.encodeToString(LyricTimings.serializer(), t)
        assertEquals("""{"schemaVersion":1,"source":"tap","lines":[{"text":"a","startMs":0,"endMs":1000}]}""", json.replace(Regex("\\s"), ""))
        assertEquals(t, CoreJson.decodeFromString(LyricTimings.serializer(), json))
    }
}
