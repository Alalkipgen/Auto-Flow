package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.model.CoreJson
import kotlin.test.Test
import kotlin.test.assertEquals

/** Export quality (5B-9, studio-v2 §9). */
class ExportQualityTest {
    private fun movie(w: Int, h: Int) = RecapMovie("source/movie.mp4", 1, 60_000, w, h)

    @Test
    fun autoFollowsTheMovieUpTo1080() {
        assertEquals(720, ExportQuality.AUTO.shortSidePx(movie(1280, 720)))
        assertEquals(1080, ExportQuality.AUTO.shortSidePx(movie(3840, 2160)))
        assertEquals(1080, ExportQuality.AUTO.shortSidePx(movie(1080, 1920)))
        assertEquals(1080, ExportQuality.AUTO.shortSidePx(movie(0, 0))) // unknown size
        assertEquals(1080, ExportQuality.AUTO.shortSidePx(null))
        assertEquals(360, ExportQuality.AUTO.shortSidePx(movie(320, 240)))
        assertEquals(480, ExportQuality.AUTO.shortSidePx(movie(853, 481))) // even
    }

    @Test
    fun fixedQualitiesIgnoreTheMovie() {
        assertEquals(listOf(720, 1080, 1440), listOf(ExportQuality.P720, ExportQuality.P1080, ExportQuality.P1440).map { it.shortSidePx(movie(1280, 720)) })
    }

    @Test
    fun serialisedAsInTheSpec() {
        assertEquals("\"1440\"", CoreJson.encodeToString(ExportQuality.serializer(), ExportQuality.P1440))
        assertEquals(ExportQuality.AUTO, CoreJson.decodeFromString(ExportQuality.serializer(), "\"AUTO\""))
        assertEquals(ExportQuality.AUTO, Recap().quality)
    }
}
