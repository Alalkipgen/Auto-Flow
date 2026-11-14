package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.BackgroundMode
import io.github.alalkipgen.autoflow.core.recap.Layer
import io.github.alalkipgen.autoflow.core.recap.LayerParams
import io.github.alalkipgen.autoflow.core.recap.LayerType
import io.github.alalkipgen.autoflow.media.render.RenderCompiler.ClipMedia
import io.github.alalkipgen.autoflow.media.render.RenderCompiler.Compiled
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Golden tests for layer compilation (spec §4, D-37): every layer type × 16:9 / 9:16 / 1:1.
 * We compile a minimal RenderGraph that includes each layer type and check the
 * filter-complex string for the expected ffmpeg constructs.
 */
class LayerCompileTest {
    private val clip = ArtifactId("vd_aaaaaaaaaaaa")
    private val imgId = "im_bbbbbbbbbbbb"
    private val imgPath = "artifacts/im_bbbbbbbbbbbb.jpg"

    private fun env(ratio: Ratio) = RenderCompiler.Env(
        resolve = { if (it == clip) ClipMedia("artifacts/vd_aaaaaaaaaaaa.mp4", hasAudio = false) else null },
        songPath = null,
        assPath = "exports/out.ass",
        fontsDir = "fonts",
        outputPath = "exports/out.mp4",
        resolveLayerImage = { if (it == imgId) imgPath else null },
    )

    private fun compile(ratio: Ratio, vararg layers: Layer): Compiled.Ok {
        val g = RenderGraph(
            clips = listOf(Clip(clip, trimOutMs = 10_000)),
            output = RenderPresets.export(ratio),
            layers = layers.toList(),
        )
        return assertIs<Compiled.Ok>(RenderCompiler.compile(g, env(ratio)))
    }

    private fun filterComplex(r: Compiled.Ok): String {
        val args = r.args
        return args[args.indexOf("-filter_complex") + 1]
    }

    // ── BLUR ──────────────────────────────────────────────────────────────────

    @Test fun blurLayer_16x9() { blurLayer(Ratio.R16_9) }
    @Test fun blurLayer_9x16() { blurLayer(Ratio.R9_16) }
    @Test fun blurLayer_1x1()  { blurLayer(Ratio.R1_1) }

    private fun blurLayer(ratio: Ratio) {
        val layer = Layer("l1", LayerType.BLUR, rect = listOf(0f, 0.82f, 1f, 0.18f),
            params = LayerParams(sigma = 20f))
        val r = compile(ratio, layer)
        val fc = filterComplex(r)
        assertContains(fc, "gblur=sigma=20")
        assertContains(fc, "overlay=")
        assertContains(fc, "between(t,")
        assertContains(fc, "[vlayer]")
    }

    // ── IMAGE ─────────────────────────────────────────────────────────────────

    @Test fun imageLayer_16x9() { imageLayer(Ratio.R16_9) }
    @Test fun imageLayer_9x16() { imageLayer(Ratio.R9_16) }
    @Test fun imageLayer_1x1()  { imageLayer(Ratio.R1_1) }

    private fun imageLayer(ratio: Ratio) {
        val layer = Layer("l1", LayerType.IMAGE, rect = listOf(0.1f, 0.1f, 0.3f, 0.3f),
            params = LayerParams(image = imgId, opacity = 0.8f))
        val r = compile(ratio, layer)
        val fc = filterComplex(r)
        assertContains(fc, "overlay=")
        assertContains(fc, "[vlayer]")
        // The image file path should appear in the args
        assertContains(r.args, imgPath)
    }

    @Test fun imageMissing_relaysStream() {
        val layer = Layer("l1", LayerType.IMAGE, params = LayerParams(image = "im_missing"))
        val r = compile(Ratio.R16_9, layer)
        val fc = filterComplex(r)
        // Should not crash; stream relayed with null
        assertContains(fc, "[vlayer]")
    }

    // ── TEXT ──────────────────────────────────────────────────────────────────

    @Test fun textLayer_16x9() { textLayer(Ratio.R16_9) }
    @Test fun textLayer_9x16() { textLayer(Ratio.R9_16) }
    @Test fun textLayer_1x1()  { textLayer(Ratio.R1_1) }

    private fun textLayer(ratio: Ratio) {
        val layer = Layer("l1", LayerType.TEXT,
            params = LayerParams(textMy = "မင်္ဂလာပါ", style = "Pyidaungsu"))
        val r = compile(ratio, layer)
        // TEXT layers generate ASS events; the ASS file must be non-null
        assertNotNull(r.ass, "TEXT layer must produce an ASS file")
        assertContains(r.ass, "မင်္ဂလာပါ")
        assertContains(filterComplex(r), "[vlayer]")
    }

    // ── BORDER ────────────────────────────────────────────────────────────────

    @Test fun borderLayer_16x9() { borderLayer(Ratio.R16_9) }
    @Test fun borderLayer_9x16() { borderLayer(Ratio.R9_16) }
    @Test fun borderLayer_1x1()  { borderLayer(Ratio.R1_1) }

    private fun borderLayer(ratio: Ratio) {
        val layer = Layer("l1", LayerType.BORDER, params = LayerParams(px = 8, colour = "#FF0000"))
        val r = compile(ratio, layer)
        val fc = filterComplex(r)
        // Four drawbox calls
        val count = fc.split("drawbox=").size - 1
        assertTrue(count >= 4, "Expected 4 drawbox calls, got $count")
        assertContains(fc, "[vlayer]")
    }

    // ── BACKGROUND ────────────────────────────────────────────────────────────

    @Test fun backgroundBlur_16x9() { backgroundBlur(Ratio.R16_9) }
    @Test fun backgroundBlur_9x16() { backgroundBlur(Ratio.R9_16) }
    @Test fun backgroundBlur_1x1()  { backgroundBlur(Ratio.R1_1) }

    private fun backgroundBlur(ratio: Ratio) {
        val layer = Layer("l1", LayerType.BACKGROUND, params = LayerParams(mode = BackgroundMode.BLUR))
        val r = compile(ratio, layer)
        val fc = filterComplex(r)
        assertContains(fc, "gblur=sigma=30")
        assertContains(fc, "[vlayer]")
    }

    @Test fun backgroundColour_16x9() {
        val layer = Layer("l1", LayerType.BACKGROUND, params = LayerParams(mode = BackgroundMode.COLOUR, colour = "#1A1A2E"))
        val r = compile(Ratio.R16_9, layer)
        val fc = filterComplex(r)
        assertContains(fc, "0x1A1A2E")
        assertContains(fc, "[vlayer]")
    }

    @Test fun backgroundImage_9x16() {
        val layer = Layer("l1", LayerType.BACKGROUND, params = LayerParams(mode = BackgroundMode.IMAGE, image = imgId))
        val r = compile(Ratio.R9_16, layer)
        val fc = filterComplex(r)
        assertContains(fc, "[vlayer]")
        assertContains(r.args, imgPath)
    }

    // ── Layering order ────────────────────────────────────────────────────────

    @Test fun multipleLayersInOrder() {
        val blur = Layer("l1", LayerType.BLUR, rect = listOf(0f, 0.8f, 1f, 0.2f))
        val border = Layer("l2", LayerType.BORDER, params = LayerParams(px = 4, colour = "#000000"))
        val r = compile(Ratio.R9_16, blur, border)
        val fc = filterComplex(r)
        // blur before border in the chain
        val blurIdx = fc.indexOf("gblur=")
        val borderIdx = fc.indexOf("drawbox=")
        assertTrue(blurIdx < borderIdx, "blur should precede border in the filtergraph")
        assertContains(fc, "[vlayer]")
    }

    // ── No layers ─────────────────────────────────────────────────────────────

    @Test fun noLayers_usesVseq() {
        val r = compile(Ratio.R16_9)
        val fc = filterComplex(r)
        // Without layers the stream goes directly [vseq]→[vout]
        assertContains(fc, "[vseq]")
        assertTrue(!fc.contains("[vlayer]"), "No vlayer label without layers")
    }
}
