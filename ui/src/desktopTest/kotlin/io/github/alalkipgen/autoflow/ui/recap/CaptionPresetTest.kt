package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.media.render.*
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class CaptionPresetTest {
    @Test fun supportedLooksMapOnlyExistingRendererFields() {
        assertEquals(listOf("CLEAN", "BOXED", "OUTLINE"), CaptionLookPreset.entries.map { it.name })
        assertEquals(CaptionStyle(), CaptionLookPreset.CLEAN.style)
        assertEquals(CaptionStyle(box = true), CaptionLookPreset.BOXED.style)
        assertEquals(CaptionStyle(strokeWidth = 3), CaptionLookPreset.OUTLINE.style)
        for (preset in CaptionLookPreset.entries) {
            assertEquals(preset, matchingCaptionPreset(preset.style))
            val json = CoreJson.encodeToString(CaptionStyle.serializer(), preset.style)
            assertTrue(CoreJson.parseToJsonElement(json).jsonObject.keys.all {
                it in setOf("size", "colour", "box", "customColour", "customSizeFactor", "strokeWidth", "strokeColour")
            })
            assertEquals(preset.style, CoreJson.decodeFromString(CaptionStyle.serializer(), json))
        }
    }
    @Test fun explicitManualOverridesAreNotMislabelledAsAStoredPreset() {
        for (style in listOf(CaptionStyle(size = CaptionSize.S), CaptionStyle(colour = "#FFE14D"),
            CaptionStyle(customColour = "#FFFFFF"), CaptionStyle(customSizeFactor = 1f),
            CaptionStyle(strokeWidth = 6), CaptionStyle(strokeColour = "#FF4444")))
            assertNull(matchingCaptionPreset(style), style.toString())
    }
    @Test fun extraLargeUsesExistingLChunkSizeAndCustomFactorWithoutChangingOtherFields() {
        val before = CaptionStyle(size = CaptionSize.S, colour = "#FFE14D", box = true,
            customColour = "#AABBCC", customSizeFactor = .7f, strokeWidth = 6, strokeColour = "#FF4444")
        val after = captionExtraLarge(before)
        assertEquals(before.copy(size = CaptionSize.L, customSizeFactor = 1.4f), after)
        assertEquals(1.4, after.effectiveSizeFactor, .00001)
        assertNull(matchingCaptionPreset(after))
        val output = RenderPresets.preview(Ratio.R16_9)
        assertEquals(42, RecapRender.captionSizePx(output, after))
        assertEquals(RecapRender.lineChars(output, CaptionStyle(size = CaptionSize.L)), RecapRender.lineChars(output, after))
    }
    @Test fun assRetainsRealAutoOutlineBoxAndUnsupportedBoldContract() {
        val output = RenderPresets.preview(Ratio.R16_9)
        for (preset in CaptionLookPreset.entries) {
            val overlay = RecapRender.captionOverlay(RecapV2(captionStyle = preset.style), output, SrtCue(0, 1000, "QA မြန်မာ"), "Noto Sans Myanmar")
            val ass = AssWriter.write(listOf(overlay), output) { null }
            val columns = ass.lineSequence().first { it.startsWith("Format: Name,") }.substringAfter(": ").split(',').map { it.trim() }
            val values = ass.lineSequence().first { it.startsWith("Style:") }.substringAfter(": ").split(',')
            val style = columns.zip(values).toMap()
            assertEquals("0", style["Bold"], "font weight is not configurable; no fake Bold choice")
            assertEquals(if (preset == CaptionLookPreset.BOXED) "3" else "1", style["BorderStyle"])
            assertEquals(if (preset == CaptionLookPreset.BOXED) "5" else if (preset == CaptionLookPreset.OUTLINE) "3" else "2", style["Outline"])
        }
    }
}
