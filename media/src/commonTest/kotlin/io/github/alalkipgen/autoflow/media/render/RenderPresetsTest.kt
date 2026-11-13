package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.Ratio
import kotlin.test.Test
import kotlin.test.assertEquals

class RenderPresetsTest {
    private fun size(o: Output) = "${o.width}x${o.height}"

    @Test
    fun previewIs540pAndExportIs1080pPerRatio() {
        assertEquals("960x540", size(RenderPresets.preview(Ratio.R16_9)))
        assertEquals("1920x1080", size(RenderPresets.export(Ratio.R16_9)))
        assertEquals("540x960", size(RenderPresets.preview(Ratio.R9_16)))
        assertEquals("1080x1920", size(RenderPresets.export(Ratio.R9_16)))
        assertEquals("540x540", size(RenderPresets.preview(Ratio.R1_1)))
        assertEquals("1080x1080", size(RenderPresets.export(Ratio.R1_1)))
        assertEquals("1080x1350", size(RenderPresets.export(Ratio.R4_5)))
        assertEquals("1260x540", size(RenderPresets.preview(Ratio.R21_9)))
    }

    @Test
    fun qualitiesMatchTheSpecEncoderSettings() {
        val e = RenderPresets.export(Ratio.R16_9)
        assertEquals(Quality.EXPORT, e.quality)
        assertEquals(listOf("slow", "18", "192k"), listOf(e.quality.x264Preset, "${e.quality.crf}", e.quality.audioBitrate))
        val p = RenderPresets.of(Ratio.R9_16, Quality.PREVIEW)
        assertEquals(listOf("ultrafast", "23"), listOf(p.quality.x264Preset, "${p.quality.crf}"))
        assertEquals(24, p.fps)
        assertEquals(listOf("yt1080", "tiktok540", "ig1x1-1080"), listOf(e.preset, p.preset, RenderPresets.export(Ratio.R1_1).preset))
    }

    @Test
    fun exportAtUsesTheChosenShortSide() {
        assertEquals("1280x720", size(RenderPresets.exportAt(Ratio.R16_9, 720)))
        assertEquals("1440x2560", size(RenderPresets.exportAt(Ratio.R9_16, 1440)))
        assertEquals("tiktok1440", RenderPresets.exportAt(Ratio.R9_16, 1440).preset)
        assertEquals(Quality.EXPORT, RenderPresets.exportAt(Ratio.R1_1, 721).quality)
        assertEquals("720x720", size(RenderPresets.exportAt(Ratio.R1_1, 721))) // even
    }
}
