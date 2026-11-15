package io.github.alalkipgen.autoflow.core.recap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewGeometryTest {

    private val eps = 1e-5f
    private fun approx(a: Float, b: Float) = kotlin.math.abs(a - b) < eps

    @Test
    fun clamp_validRect_unchanged() {
        val rect = listOf(0.1f, 0.1f, 0.5f, 0.5f)
        val clamped = PreviewGeometry.clamp(rect)
        assertEquals(rect, clamped)
    }

    @Test
    fun clamp_overEdge_clamped() {
        // x=0.8, w=0.5 => x should become 0.5 (1 - 0.5)
        val rect = listOf(0.8f, 0.0f, 0.5f, 0.5f)
        val clamped = PreviewGeometry.clamp(rect)
        assertTrue(approx(0.5f, clamped[0]))
        assertTrue(approx(0.5f, clamped[2]))
    }

    @Test
    fun clamp_tooSmall_raisedToMin() {
        val rect = listOf(0.0f, 0.0f, 0.01f, 0.01f)
        val clamped = PreviewGeometry.clamp(rect)
        assertTrue(clamped[2] >= 0.05f)
        assertTrue(clamped[3] >= 0.05f)
    }

    @Test
    fun drag_movesRect() {
        val rect = listOf(0.1f, 0.1f, 0.3f, 0.3f)
        val moved = PreviewGeometry.drag(rect, 0.2f, 0.1f)
        assertTrue(approx(0.3f, moved[0]))
        assertTrue(approx(0.2f, moved[1]))
    }

    @Test
    fun drag_clampsToBoundary() {
        val rect = listOf(0.0f, 0.0f, 0.5f, 0.5f)
        val moved = PreviewGeometry.drag(rect, -0.5f, -0.5f)
        assertTrue(approx(0.0f, moved[0]))
        assertTrue(approx(0.0f, moved[1]))
    }

    @Test
    fun pinchResize_scalesRect() {
        val rect = listOf(0.0f, 0.0f, 0.4f, 0.4f)
        val scaled = PreviewGeometry.pinchResize(rect, 1.5f, 2f)
        assertTrue(approx(0.6f, scaled[2]))
        assertTrue(approx(0.8f, scaled[3]))
    }

    @Test
    fun toPixels_correctConversion() {
        val rect = listOf(0.0f, 0.5f, 1.0f, 0.5f)
        val px = PreviewGeometry.toPixels(rect, 1920f, 1080f)
        assertTrue(approx(0f, px[0]))      // left
        assertTrue(approx(540f, px[1]))    // top
        assertTrue(approx(1920f, px[2]))   // right
        assertTrue(approx(1080f, px[3]))   // bottom
    }

    @Test
    fun fromPixels_roundTrip() {
        val rect = listOf(0.1f, 0.2f, 0.6f, 0.4f)
        val px = PreviewGeometry.toPixels(rect, 1080f, 1920f)
        val back = PreviewGeometry.fromPixels(px, 1080f, 1920f)
        assertTrue(approx(rect[0], back[0]))
        assertTrue(approx(rect[1], back[1]))
        assertTrue(approx(rect[2], back[2]))
        assertTrue(approx(rect[3], back[3]))
    }

    @Test
    fun isActive_layerVisible_returnsTrue() {
        val layer = Layer(id = "l_01", type = LayerType.TEXT, fromMs = 1000, toMs = 5000)
        assertTrue(PreviewGeometry.isActive(layer, 1000))
        assertTrue(PreviewGeometry.isActive(layer, 3000))
        assertTrue(PreviewGeometry.isActive(layer, 5000))
    }

    @Test
    fun isActive_beforeStart_returnsFalse() {
        val layer = Layer(id = "l_01", type = LayerType.BLUR, fromMs = 2000)
        assertFalse(PreviewGeometry.isActive(layer, 1999))
    }

    @Test
    fun isActive_afterEnd_returnsFalse() {
        val layer = Layer(id = "l_01", type = LayerType.IMAGE, fromMs = 0, toMs = 3000)
        assertFalse(PreviewGeometry.isActive(layer, 3001))
    }

    @Test
    fun isActive_nullToMs_alwaysActive() {
        val layer = Layer(id = "l_01", type = LayerType.BACKGROUND, fromMs = 0, toMs = null)
        assertTrue(PreviewGeometry.isActive(layer, 999_999_999))
    }

    @Test
    fun activeLayers_filtersCorrectly() {
        val layers = listOf(
            Layer("l_1", LayerType.BLUR, fromMs = 0, toMs = 2000),
            Layer("l_2", LayerType.TEXT, fromMs = 1000, toMs = null),
            Layer("l_3", LayerType.IMAGE, fromMs = 5000, toMs = null),
        )
        val active = PreviewGeometry.activeLayers(layers, 1500)
        assertEquals(2, active.size)
        assertTrue(active.any { it.id == "l_1" })
        assertTrue(active.any { it.id == "l_2" })
    }

    // ── 5A-8 drag / pinch ────────────────────────────────────────────────────

    @Test
    fun hit_picksTopmostRectUnderThePoint() {
        val strip = listOf(0f, 0.8f, 1f, 0.17f)
        val logo = listOf(0.7f, 0.75f, 0.2f, 0.2f)
        assertEquals(1, PreviewGeometry.hit(listOf(strip, logo), 0.8f, 0.85f), "overlap → the one drawn last")
        assertEquals(0, PreviewGeometry.hit(listOf(strip, logo), 0.2f, 0.9f))
        assertEquals(null, PreviewGeometry.hit(listOf(strip, logo), 0.2f, 0.5f))
        assertEquals(0, PreviewGeometry.hit(listOf(strip), 0.5f, 0.79f, slop = 0.02f), "slop grabs a thin strip")
    }

    @Test
    fun transform_dragMovesAndStopsAtTheEdge() {
        val r = PreviewGeometry.transform(listOf(0.1f, 0.1f, 0.2f, 0.2f), dx = 0.3f, dy = 0.05f, zoom = 1f, cx = 0f, cy = 0f)
        assertTrue(approx(0.4f, r[0]) && approx(0.15f, r[1]) && approx(0.2f, r[2]) && approx(0.2f, r[3]), "$r")
        val edge = PreviewGeometry.transform(listOf(0.1f, 0.1f, 0.2f, 0.2f), dx = 2f, dy = 2f, zoom = 1f, cx = 0f, cy = 0f)
        assertTrue(approx(0.8f, edge[0]) && approx(0.8f, edge[1]), "kept inside: $edge")
    }

    @Test
    fun transform_pinchKeepsThePointUnderTheFingers() {
        // Box 0.2..0.6 × 0.2..0.6, pinch ×1.5 around (0.3, 0.3): that point stays put, the box grows away from it.
        val r = PreviewGeometry.transform(listOf(0.2f, 0.2f, 0.4f, 0.4f), dx = 0f, dy = 0f, zoom = 1.5f, cx = 0.3f, cy = 0.3f)
        assertTrue(approx(0.15f, r[0]) && approx(0.15f, r[1]) && approx(0.6f, r[2]) && approx(0.6f, r[3]), "$r")
        // The relative position of the centre point inside the box is unchanged (1/4 of the way in).
        assertTrue(approx(0.25f, (0.3f - r[0]) / r[2]), "$r")
    }

    @Test
    fun transform_pinchInStopsAtTheMinimumAndAStripStaysFullWidth() {
        val tiny = PreviewGeometry.transform(listOf(0.4f, 0.4f, 0.1f, 0.1f), 0f, 0f, zoom = 0.1f, cx = 0.45f, cy = 0.45f)
        assertTrue(approx(0.05f, tiny[2]) && approx(0.05f, tiny[3]), "$tiny")
        val strip = PreviewGeometry.transform(listOf(0f, 0.8f, 1f, 0.17f), 0f, 0f, zoom = 1.2f, cx = 0.5f, cy = 0.885f)
        assertTrue(approx(0f, strip[0]) && approx(1f, strip[2]), "strip stays full width: $strip")
        assertTrue(strip[3] > 0.2f && strip[1] + strip[3] <= 1f + eps, "taller, still inside: $strip")
    }

    @Test
    fun transform_ignoresABrokenZoom() {
        val r = listOf(0.1f, 0.1f, 0.3f, 0.3f)
        assertEquals(r, PreviewGeometry.transform(r, 0f, 0f, Float.NaN, 0.5f, 0.5f))
        assertEquals(r, PreviewGeometry.transform(r, 0f, 0f, 0f, 0.5f, 0.5f))
    }
}
