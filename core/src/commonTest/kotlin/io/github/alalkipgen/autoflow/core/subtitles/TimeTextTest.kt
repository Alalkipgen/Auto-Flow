package io.github.alalkipgen.autoflow.core.subtitles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeTextTest {
    @Test
    fun parsesBothShapes() {
        assertEquals(3_723_400L, TimeText.parse("1:02:03.4"))
        assertEquals(3_723_456L, TimeText.parse("01:02:03,456"))
        assertEquals(123_050L, TimeText.parse("02:03.05"))
        assertEquals(4_500L, TimeText.parse(" 0:04.5 "))
        assertEquals(75 * 60_000L, TimeText.parse("75:00")) // minutes without hours may pass 59
        for (bad in listOf("", "abc", "1:2", "0:60.0", "1:60:00", "1.5", "-0:01.0", "0:01.0000")) assertNull(TimeText.parse(bad), bad)
    }

    @Test
    fun showsTenthsRoundedDown() {
        assertEquals("0:04.5", TimeText.show(4_599))
        assertEquals("12:03.0", TimeText.show(723_000))
        assertEquals("1:02:03.4", TimeText.show(3_723_456))
        assertEquals("0:00.0", TimeText.show(-5))
        assertEquals(4_500L, TimeText.parse(TimeText.show(4_567)))
    }
}
