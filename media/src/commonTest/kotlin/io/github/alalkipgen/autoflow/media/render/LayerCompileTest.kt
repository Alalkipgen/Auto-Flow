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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
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

    @Test fun imageMissing_failsTheRender() {
        // Audit F08: a missing image is an explicit failure, never a render that quietly leaves the layer out.
        val layer = Layer("l1", LayerType.IMAGE, params = LayerParams(image = "im_missing"))
        val g = RenderGraph(clips = listOf(Clip(clip, trimOutMs = 10_000)), output = RenderPresets.export(Ratio.R16_9), layers = listOf(layer))
        val invalid = assertIs<Compiled.Invalid>(RenderCompiler.compile(g, env(Ratio.R16_9)))
        assertEquals("layer l1: the image im_missing is missing", invalid.reason)
        val none = g.copy(layers = listOf(layer.copy(params = LayerParams(image = null))))
        assertEquals("layer l1: no image is chosen", assertIs<Compiled.Invalid>(RenderCompiler.compile(none, env(Ratio.R16_9))).reason)
        val bg = g.copy(layers = listOf(Layer("b", LayerType.BACKGROUND, params = LayerParams(mode = BackgroundMode.IMAGE, image = "im_missing"))))
        assertEquals("layer b: the image im_missing is missing", assertIs<Compiled.Invalid>(RenderCompiler.compile(bg, env(Ratio.R16_9))).reason)
    }

    // ── TEXT ──────────────────────────────────────────────────────────────────

    @Test fun textLayer_16x9() { textLayer(Ratio.R16_9) }
    @Test fun textLayer_9x16() { textLayer(Ratio.R9_16) }
    @Test fun textLayer_1x1()  { textLayer(Ratio.R1_1) }

    private fun textLayer(ratio: Ratio) {
        val layer = Layer("l1", LayerType.TEXT,
            params = LayerParams(textMy = "မင်္ဂလာပါ", style = "Pyidaungsu"))
        val r = compile(ratio, layer)
        // TEXT has its own sidecar at its list position; it must not leak into the final captions pass.
        assertNull(r.ass)
        assertContains(r.layerAssFiles.values.single(), "မင်္ဂလာပါ")
        assertContains(filterComplex(r), "[vlayer]")
    }

    @Test fun textLayer_onItsRect() {
        // 5B-10 thumbnail title: a rect smaller than the frame puts the text on the rect's centre line, sized to it.
        val layer = Layer("t", LayerType.TEXT, rect = listOf(0.05f, 0.05f, 0.9f, 0.2f),
            params = LayerParams(textMy = "ခေါင်းစဉ်", style = "Pyidaungsu"))
        val ass = compile(Ratio.R9_16, layer).layerAssFiles.values.single()
        // 1080 × 1920: centre line 0.15 × 1920 = 288; one line in 0.2 × 1920 = 384 px → 384 / 1.5 = 256 px tall, but
        // 7 spacing Burmese letters × 0.6 em must fit 0.9 × 1080 = 972 px → 231 px.
        assertContains(ass, "{\\an5\\pos(540,288)}ခေါင်းစဉ်")
        assertContains(ass, "Style: s1,Pyidaungsu,231,")
    }

    @Test fun textLayer_offCentreRect_usesItsXAndTheCaptionFont() {
        // Audit F08: the rect's x counts too (the text was always at the frame's middle); no style = the captions' font.
        val layer = Layer("t", LayerType.TEXT, rect = listOf(0.02f, 0.02f, 0.3f, 0.15f), params = LayerParams(textMy = "AUDIT"))
        val caption = Overlay("cap", 0, 1_000, "Padauk", 60)
        val g = RenderGraph(clips = listOf(Clip(clip, trimOutMs = 10_000)), output = RenderPresets.export(Ratio.R16_9),
            overlays = listOf(caption), layers = listOf(layer))
        val compiled = assertIs<Compiled.Ok>(RenderCompiler.compile(g, env(Ratio.R16_9)))
        assertContains(assertNotNull(compiled.ass), "cap")
        assertFalse(compiled.ass.contains("AUDIT"), "Only real captions belong in the final pass")
        val ass = compiled.layerAssFiles.values.single()
        // 1920 × 1080: centre (0.17 × 1920, 0.095 × 1080) = (326, 102); 0.15 × 1080 / 1.5 = 108 px.
        assertContains(ass, "{\\an5\\pos(326,102)}AUDIT")
        assertContains(ass, "Style: s1,Padauk,108,")
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
    // Audit F08 / F12: a BACKGROUND is composed inside each clip's own chain, behind the fitted picture (the pixels
    // are checked with a real ffmpeg in LayerMatrixFfmpegTest); it is not painted over the finished strip.

    /** The chain that ends in the clip's picture `[v0]`. */
    private fun clipChain(fc: String) = fc.split(";").takeWhile { !it.endsWith("[v0]") }.joinToString(";") + ";" +
        fc.split(";").first { it.endsWith("[v0]") }

    @Test fun backgroundBlur_16x9() { backgroundBlur(Ratio.R16_9) }
    @Test fun backgroundBlur_9x16() { backgroundBlur(Ratio.R9_16) }
    @Test fun backgroundBlur_1x1()  { backgroundBlur(Ratio.R1_1) }

    private fun backgroundBlur(ratio: Ratio) {
        val layer = Layer("l1", LayerType.BACKGROUND, params = LayerParams(mode = BackgroundMode.BLUR))
        val r = compile(ratio, layer)
        val fc = filterComplex(r)
        val out = RenderPresets.export(ratio)
        assertContains(clipChain(fc), "gblur=")
        assertContains(clipChain(fc), "scale=${out.width}:${out.height}:force_original_aspect_ratio=decrease")
        // Nothing is drawn over the strip, so there is no layer stage.
        assertTrue("[vlayer]" !in fc, fc)
    }

    @Test fun backgroundColour_16x9() {
        val layer = Layer("l1", LayerType.BACKGROUND, params = LayerParams(mode = BackgroundMode.COLOUR, colour = "#1A1A2E"))
        val fc = filterComplex(compile(Ratio.R16_9, layer))
        assertContains(clipChain(fc), "pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=0x1A1A2E")
        // F12: no split output is left unconnected.
        assertTrue("split" !in fc, fc)
    }

    @Test fun backgroundImage_9x16() {
        val layer = Layer("l1", LayerType.BACKGROUND, params = LayerParams(mode = BackgroundMode.IMAGE, image = imgId))
        val r = compile(Ratio.R9_16, layer)
        val fc = filterComplex(r)
        assertContains(r.args, imgPath)
        val i = r.args.indexOf(imgPath)
        assertEquals(listOf("-loop", "1", "-t", "10.000", "-i"), r.args.subList(i - 5, i))
        assertContains(fc, "[1:v]scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920")
    }

    /**
     * The still (1/fps) and the movie (e.g. 1/12288) reach overlay in one time base. FFmpeg 8 / 9 hand overlay's end
     * time on unconverted otherwise (2 s → 1024 s), and the next clips fell past the export's end (frozen picture).
     * The real-ffmpeg check is LayerMatrixFfmpegTest.imageBackgroundClipEndsOnTime.
     */
    @Test fun backgroundImage_overlayInputsShareOneTimeBase() {
        val layer = Layer("l1", LayerType.BACKGROUND, params = LayerParams(mode = BackgroundMode.IMAGE, image = imgId))
        val fc = filterComplex(compile(Ratio.R9_16, layer))
        val bg = assertNotNull(Regex("""setsar=1,settb=AVTB\[(lbg[0-9_]+)]""").find(fc), fc).groupValues[1]
        val fg = assertNotNull(Regex("""setsar=1,settb=AVTB\[(lfg[0-9_]+)]""").find(fc), fc).groupValues[1]
        assertContains(fc, "[$bg][$fg]overlay=(main_w-overlay_w)/2:(main_h-overlay_h)/2:shortest=1,")
    }

    @Test fun backgroundForPartOfAClip_isShownOnlyInItsWindow() {
        val layer = Layer("l1", LayerType.BACKGROUND, fromMs = 4_000, toMs = 6_000, params = LayerParams(mode = BackgroundMode.COLOUR, colour = "#00FF00"))
        val fc = filterComplex(compile(Ratio.R16_9, layer))
        // Two whole pictures of the clip (bars / green), the green one over the other from 4 s to 6 s of the clip.
        assertContains(fc, "[0:v]split=2[vs0_0][vs0_1]")
        assertContains(fc, "[vf0_0][vf0_1]overlay=0:0:enable='between(t,4.000,6.000)',fps=24[v0]")
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

    // ── Audit regression tests (audit items 2 & 3) ────────────────────────────

    /**
     * Audit item 2: BACKGROUND-IMAGE filtergraph must NOT feed two inputs into scale.
     * Old code: `[$prev][idx:v]scale=...` → scale got 2 inputs → ffmpeg exit 234.
     */
    @Test fun backgroundImage_scaleHasOneInput() {
        val layer = Layer("l1", LayerType.BACKGROUND,
            params = LayerParams(mode = BackgroundMode.IMAGE, image = imgId))
        val r = compile(Ratio.R9_16, layer)
        val fc = filterComplex(r)
        // The scale filter must be preceded by exactly one stream label (the image input index).
        // Pattern `[prev][N:v]scale` would have TWO labels before scale — must NOT exist.
        val twoInputsBeforeScale = Regex("""\[[^\]]+]\[[^\]]+]scale""").containsMatchIn(fc)
        assertTrue(!twoInputsBeforeScale,
            "scale must have exactly one input label (not two). Graph: $fc")
        assertContains(fc, "overlay=")
        assertContains(r.args, imgPath)
    }

    /**
     * Audit item 3: BLUR feather must not emit vignette with unsupported x0/y0/x1/y1 options.
     * Old code: `vignette=angle=PI/2:x0=...:y0=...:x1=...:y1=...` → ffmpeg "Option not found".
     * New code: geq-based per-pixel alpha fade. The pixels are checked with a real ffmpeg in
     * LayerFfmpegRenderTest (desktopTest).
     */
    @Test fun blurFeather_noVignetteXY() {
        val layer = Layer("l1", LayerType.BLUR,
            rect = listOf(0f, 0.5f, 1f, 0.3f),
            params = LayerParams(sigma = 10f, feather = 0.1f))
        val r = compile(Ratio.R9_16, layer)
        val fc = filterComplex(r)
        assertTrue(!fc.contains("vignette="), "vignette must not appear in feather graph. Graph: $fc")
        assertTrue(!fc.contains("x0=") && !fc.contains("x1="),
            "Unsupported vignette x0/x1 options must not appear. Graph: $fc")
        assertTrue(fc.contains("geq="), "geq must be used for feather. Graph: $fc")
        assertTrue(!fc.contains("luma="), "geq has no `luma` option (it is `lum`). Graph: $fc")
        assertContains(fc, "format=yuva420p")
        assertContains(fc, "[vlayer]")
    }
}
