package io.github.alalkipgen.autoflow.core.dubbing

import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.DubMask
import io.github.alalkipgen.autoflow.core.model.DubMaskKind
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.recap.MaskStrength
import kotlin.test.*

/** D-107 (specs/dubbing.md §2 Style): the dub's blur / colour boxes and where the Burmese subtitles sit on them. */
class DubMasksTest {
    @Test fun stripsAndCornersToggleAndCustomBoxesAddUp() {
        var m = DubMasks.toggle(emptyList(), DubMaskKind.BOTTOM)
        assertEquals(listOf(DubMask(DubMaskKind.BOTTOM, DubMaskKind.BOTTOM.rect)), m)
        m = DubMasks.toggle(m, DubMaskKind.LOGO_BR)
        m = DubMasks.toggle(m, DubMaskKind.CUSTOM)
        m = DubMasks.toggle(m, DubMaskKind.CUSTOM)
        assertEquals(4, m.size)
        assertNotEquals(m[2].rect, m[3].rect, "a second custom box does not hide the first")
        m = DubMasks.toggle(m, DubMaskKind.BOTTOM)
        assertEquals(listOf(DubMaskKind.LOGO_BR, DubMaskKind.CUSTOM, DubMaskKind.CUSTOM), m.map { it.kind })
        assertEquals(2, DubMasks.remove(m, 0).size)
    }

    @Test fun boxesStayInThePictureAndStripsStayFullWidth() {
        val strip = listOf(DubMask(DubMaskKind.BOTTOM, DubMaskKind.BOTTOM.rect))
        val moved = DubMasks.move(strip, 0, .3f, .5f)
        assertEquals(listOf(0f, .83f, 1f, .17f), moved[0].rect, "a strip moves only up / down and stays inside")
        val wide = DubMasks.resize(strip, 0, .2f, .04f)
        assertEquals(0f, wide[0].rect[0]); assertEquals(1f, wide[0].rect[2])
        val box = listOf(DubMask(DubMaskKind.LOGO_TL, DubMaskKind.LOGO_TL.rect))
        assertEquals(listOf(0f, 0f, .22f, .13f), DubMasks.move(box, 0, -.5f, -.5f)[0].rect)
        assertEquals(DubMasks.MIN_SIZE, DubMasks.resize(box, 0, -1f, -1f)[0].rect[2])
        assertEquals(listOf(.5f, .5f, .2f, .1f), DubMasks.setRect(box, 0, listOf(.5f, .5f, .2f, .1f))[0].rect)
        assertEquals(box, DubMasks.setRect(box, 0, listOf(Float.NaN, 0f, 1f, 1f)), "not a rect = no change")
    }

    @Test fun aBoxIsBlurredOrFilledWithAColour() {
        val box = listOf(DubMask(DubMaskKind.LOGO_TR, DubMaskKind.LOGO_TR.rect))
        assertEquals("#FF0000", DubMasks.fill(box, 0, "#ff0000")[0].fill)
        assertNull(DubMasks.fill(box, 0, "red")[0].fill, "not a colour = blurred")
        assertNull(DubMasks.fill(DubMasks.fill(box, 0, "#000000"), 0, null)[0].fill)
    }

    @Test fun subtitlesSitOnTheLowestBoxInTheLowerHalf() {
        val bottom = DubMask(DubMaskKind.BOTTOM, DubMaskKind.BOTTOM.rect)
        val top = DubMask(DubMaskKind.TOP, DubMaskKind.TOP.rect)
        assertEquals(.885f, DubMasks.captionY(DubbingTool(masks = listOf(top, bottom)))!!, 1e-4f)
        assertNull(DubMasks.captionY(DubbingTool(masks = listOf(top))), "no box in the lower half = the usual place")
        assertNull(DubMasks.captionY(DubbingTool(masks = listOf(bottom), captionsOnBox = false)))
        assertNull(DubMasks.captionY(null))
    }

    @Test fun theBlurFollowsThePictureHeight() {
        assertEquals(32, DubMasks.sigma(MaskStrength.STRONG, 1080))
        assertEquals(10, DubMasks.sigma(MaskStrength.STRONG, 360))
        assertEquals(4, DubMasks.sigma(MaskStrength.LIGHT, 240))
    }

    @Test fun oldProjectsReadAndWriteAsBefore() {
        val old = CoreJson.encodeToString(DubbingTool.serializer(), DubbingTool())
        assertFalse("masks" in old || "blurStrength" in old || "captionsOnBox" in old, old)
        val tool = DubbingTool(masks = listOf(DubMask(DubMaskKind.CUSTOM, listOf(.1f, .2f, .3f, .4f), "#FFFFFF")), blurStrength = MaskStrength.LIGHT,
            captionsOnBox = false)
        assertEquals(tool, CoreJson.decodeFromString(DubbingTool.serializer(), CoreJson.encodeToString(DubbingTool.serializer(), tool)))
    }
}
