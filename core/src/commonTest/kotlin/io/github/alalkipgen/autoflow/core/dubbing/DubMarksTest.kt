package io.github.alalkipgen.autoflow.core.dubbing

import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.DubWatermark
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.recap.WatermarkAnchor
import kotlin.math.abs
import kotlin.test.*

/** D-108 (specs/dubbing.md §2 Style): where the dub's watermark goes — the same numbers for the preview and the export. */
class DubMarksTest {
    @Test fun widthIsEvenPixelsOfTheVideoWithinRecapsLimits() {
        assertEquals(422, DubMarks.widthPx(22, 1920))
        assertEquals(1056, DubMarks.widthPx(80, 1920), "at most 55 %")
        assertEquals(54, DubMarks.widthPx(1, 1080), "at least 5 %")
        assertEquals(70, DubMarks.widthPx(22, 320))
        assertTrue(listOf(13, 22, 35, 41).all { DubMarks.widthPx(it, 1279) % 2 == 0 })
        assertEquals(2, DubMarks.widthPx(5, 0))
    }

    @Test fun originMatchesTheOverlayExpressions() {
        val (w, h) = .22f to .1f
        fun near(a: Pair<Float, Float>, x: Float, y: Float) = abs(a.first - x) < 1e-5f && abs(a.second - y) < 1e-5f
        assertTrue(near(DubMarks.origin(WatermarkAnchor.TOP_LEFT, w, h), .02f, .02f))
        assertTrue(near(DubMarks.origin(WatermarkAnchor.TOP_RIGHT, w, h), .76f, .02f))
        assertTrue(near(DubMarks.origin(WatermarkAnchor.BOTTOM_LEFT, w, h), .02f, .88f))
        assertTrue(near(DubMarks.origin(WatermarkAnchor.BOTTOM_RIGHT, w, h), .76f, .88f))
        assertTrue(near(DubMarks.origin(WatermarkAnchor.CENTER, w, h), .39f, .45f))
        assertEquals("main_w-overlay_w-main_w*0.02", DubMarks.overlayX(WatermarkAnchor.BOTTOM_RIGHT))
        assertEquals("main_h-overlay_h-main_h*0.02", DubMarks.overlayY(WatermarkAnchor.BOTTOM_RIGHT))
        assertEquals("main_w*0.02", DubMarks.overlayX(WatermarkAnchor.TOP_LEFT))
        assertEquals("(main_h-overlay_h)/2", DubMarks.overlayY(WatermarkAnchor.CENTER))
    }

    @Test fun theCopyIsNamedByItsBytes() {
        assertEquals("png", DubMarks.ext("/pick/Logo.PNG"))
        assertEquals("webp", DubMarks.ext("files/a.webp"))
        assertEquals("png", DubMarks.ext("files/a.gif"))
        assertEquals("library/watermark-0123456789ab.jpg", DubMarks.file("0123456789abcdef", "jpg"))
    }

    @Test fun noWatermarkWritesNothingNew() {
        val json = CoreJson.encodeToString(DubbingTool.serializer(), DubbingTool())
        assertFalse("watermark" in json, json)
        val tool = DubbingTool(watermark = DubWatermark("library/watermark-abc.png", WatermarkAnchor.TOP_LEFT, 13))
        assertEquals(tool, CoreJson.decodeFromString(DubbingTool.serializer(), CoreJson.encodeToString(DubbingTool.serializer(), tool)))
    }
}
