package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.model.CoreJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 5B-2 Picture: blur boxes on the movie, their edits, captions on the lowest box, movie → output mapping. */
class RecapVisualTest {
    @Test
    fun presetsToggleOnAndOffInAFixedOrderAndShareTheStrength() {
        var v = RecapVisual()
        v = RecapVisuals.toggle(v, MaskPreset.LOGO_TR)
        v = RecapVisuals.toggle(v, MaskPreset.BOTTOM)
        assertEquals(listOf(MaskPreset.BOTTOM, MaskPreset.LOGO_TR), v.masks.map { it.preset })
        assertEquals(MaskPreset.BOTTOM.rect, v.masks.first().rect)
        v = RecapVisuals.strength(v, MaskStrength.LIGHT)
        assertTrue(v.masks.all { it.strength == MaskStrength.LIGHT })
        v = RecapVisuals.toggle(v, MaskPreset.TOP)
        assertEquals(MaskStrength.LIGHT, v.masks.first { it.preset == MaskPreset.TOP }.strength)
        v = RecapVisuals.toggle(v, MaskPreset.BOTTOM)
        assertEquals(listOf(MaskPreset.TOP, MaskPreset.LOGO_TR), v.masks.map { it.preset })
    }

    @Test
    fun movingAndResizingStayInsideThePicture() {
        var v = RecapVisuals.toggle(RecapVisual(), MaskPreset.BOTTOM)
        v = RecapVisuals.move(v, MaskPreset.BOTTOM, -0.02f)
        assertEquals(listOf(0f, 0.78f, 1f, 0.17f), v.masks[0].rect)
        repeat(10) { v = RecapVisuals.move(v, MaskPreset.BOTTOM, 0.02f) }
        assertEquals(listOf(0f, 0.83f, 1f, 0.17f), v.masks[0].rect)
        v = RecapVisuals.resize(v, MaskPreset.BOTTOM, 0.04f)
        assertEquals(listOf(0f, 0.79f, 1f, 0.21f), v.masks[0].rect)
        repeat(20) { v = RecapVisuals.resize(v, MaskPreset.BOTTOM, -0.04f) }
        assertEquals(RecapVisuals.MIN_SIZE, v.masks[0].rect[3])
    }

    @Test
    fun captionsSitOnTheLowestBoxInTheLowerHalfOnly() {
        val top = RecapMask(MaskPreset.TOP, MaskPreset.TOP.rect)
        val bottom = RecapMask(MaskPreset.BOTTOM, MaskPreset.BOTTOM.rect)
        assertNull(RecapVisual(masks = listOf(top)).captionMask)
        assertEquals(bottom, RecapVisual(masks = listOf(top, bottom)).captionMask)
        assertNull(RecapVisual(masks = listOf(top, bottom), captionsOnMask = false).captionMask)
    }

    @Test
    fun movieHeightsMapIntoTheOutputFrame() {
        // 16:9 movie in 9:16 with bars: the picture is 1080×608 from y 656 of 1920.
        assertEquals(0.5f, RecapVisuals.outputY(0.5f, 1920, 1080, 1080, 1920, zoom = false, fill = false), 0.001f)
        assertEquals((656f + 608f * 0.9f) / 1920f, RecapVisuals.outputY(0.9f, 1920, 1080, 1080, 1920, zoom = false, fill = false), 0.002f)
        // Same shape: unchanged; zoom moves points away from the centre.
        assertEquals(0.9f, RecapVisuals.outputY(0.9f, 1920, 1080, 1920, 1080, zoom = false, fill = false), 0.001f)
        assertEquals((0.9f - 0.05f) / 0.9f, RecapVisuals.outputY(0.9f, 1920, 1080, 1920, 1080, zoom = true, fill = false), 0.001f)
        // Fill: 16:9 covering 9:16 keeps the full height.
        assertEquals(0.9f, RecapVisuals.outputY(0.9f, 1920, 1080, 1080, 1920, zoom = false, fill = true), 0.001f)
        // Unknown size: as if the same shape.
        assertEquals(0.9f, RecapVisuals.outputY(0.9f, 0, 0, 1080, 1920, zoom = false, fill = false), 0.001f)
    }

    @Test
    fun oldRecapsReadWithoutPictureSettings() {
        val r = CoreJson.decodeFromString(Recap.serializer(), """{"rightsConfirmed":true}""")
        assertEquals(RecapVisual(), r.visual)
        val back = CoreJson.decodeFromString(Recap.serializer(), CoreJson.encodeToString(Recap.serializer(), r.copy(visual = RecapVisuals.toggle(RecapVisual(), MaskPreset.BOTTOM))))
        assertEquals(MaskPreset.BOTTOM, back.visual.masks.single().preset)
    }

    @Test
    fun setRectPlacesOneBoxInsideThePicture() {
        val v = RecapVisuals.toggle(RecapVisuals.toggle(RecapVisual(), MaskPreset.BOTTOM), MaskPreset.LOGO_TR)
        val moved = RecapVisuals.setRect(v, MaskPreset.LOGO_TR, listOf(0.9f, -0.1f, 0.3f, 0.1f))
        assertEquals(listOf(0.7f, 0f, 0.3f, 0.1f), moved.masks.single { it.preset == MaskPreset.LOGO_TR }.rect)
        assertEquals(MaskPreset.BOTTOM.rect, moved.masks.single { it.preset == MaskPreset.BOTTOM }.rect, "other box untouched")
        assertEquals(v, RecapVisuals.setRect(v, MaskPreset.LOGO_TR, listOf(Float.NaN, 0f, 0.1f, 0.1f)), "broken rect ignored")
        assertEquals(v, RecapVisuals.setRect(v, MaskPreset.TOP, listOf(0f, 0f, 0.5f, 0.5f)), "no such box: nothing changes")
    }
    @Test
    fun customBoxGoesAnywhereAndCarriesTheCaptionsOnlyInTheLowerHalf() {
        assertEquals(listOf(MaskPreset.CUSTOM), MaskPreset.entries.filterNot { it.isStrip || it.name.startsWith("LOGO") })
        assertEquals(setOf(MaskPreset.BOTTOM, MaskPreset.TOP), MaskPreset.entries.filter { it.isStrip }.toSet())
        var v = RecapVisuals.toggle(RecapVisual(), MaskPreset.CUSTOM)
        assertEquals(listOf(0.3f, 0.3f, 0.4f, 0.2f), v.masks.single().rect)
        assertNull(v.captionMask, "starts above the middle: captions stay in their usual place")
        v = RecapVisuals.moveX(v, MaskPreset.CUSTOM, -0.02f)
        assertEquals(listOf(0.28f, 0.3f, 0.4f, 0.2f), v.masks.single().rect)
        repeat(20) { v = RecapVisuals.moveX(v, MaskPreset.CUSTOM, -0.02f) }
        assertEquals(listOf(0f, 0.3f, 0.4f, 0.2f), v.masks.single().rect, "kept inside on the left")
        v = RecapVisuals.resizeW(v, MaskPreset.CUSTOM, 0.04f)
        assertEquals(listOf(0f, 0.3f, 0.44f, 0.2f), v.masks.single().rect)
        repeat(40) { v = RecapVisuals.moveX(v, MaskPreset.CUSTOM, 0.02f) }
        assertEquals(listOf(0.56f, 0.3f, 0.44f, 0.2f), v.masks.single().rect, "kept inside on the right")
        repeat(30) { v = RecapVisuals.resizeW(v, MaskPreset.CUSTOM, -0.04f) }
        val narrow = v.masks.single().rect
        assertEquals(RecapVisuals.MIN_SIZE, narrow[2])
        assertTrue(narrow[0] >= 0f && narrow[0] + narrow[2] <= 1.0001f, "narrowest box inside: $narrow")
        repeat(40) { v = RecapVisuals.move(v, MaskPreset.CUSTOM, 0.02f) }
        assertEquals(0.8f, v.masks.single().rect[1], "moved down to the bottom edge")
        assertEquals(v.masks.single(), v.captionMask, "in the lower half it carries the captions")
        val withTop = RecapVisuals.toggle(v, MaskPreset.TOP)
        assertEquals(listOf(MaskPreset.TOP, MaskPreset.CUSTOM), withTop.masks.map { it.preset })
        assertEquals(MaskPreset.CUSTOM, withTop.captionMask?.preset)
        // Saved and read back by name.
        val json = CoreJson.encodeToString(RecapV2.serializer(), RecapV2(visual = v))
        assertTrue("\"CUSTOM\"" in json, json)
        assertEquals(v, CoreJson.decodeFromString(RecapV2.serializer(), json).visual)
    }
}
