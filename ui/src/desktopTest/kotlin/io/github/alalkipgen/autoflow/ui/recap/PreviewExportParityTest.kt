package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.CaptionStyle
import io.github.alalkipgen.autoflow.core.recap.FrameBackground
import io.github.alalkipgen.autoflow.core.recap.MaskPreset
import io.github.alalkipgen.autoflow.core.recap.RecapMask
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.RecapVisual
import io.github.alalkipgen.autoflow.core.recap.SrtCue
import io.github.alalkipgen.autoflow.media.render.Clip
import io.github.alalkipgen.autoflow.media.render.FontInfo
import io.github.alalkipgen.autoflow.media.render.Output
import io.github.alalkipgen.autoflow.media.render.RecapRender
import io.github.alalkipgen.autoflow.media.render.RenderCompiler
import io.github.alalkipgen.autoflow.media.render.RenderGraph
import io.github.alalkipgen.autoflow.media.render.RenderPresets
import io.github.alalkipgen.autoflow.media.render.VoiceTrack
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * S10 (D-65, PLAN spikes): the Recap preview (Compose, [RecapPreviewPicture]) against the export (the real
 * [RenderCompiler] graph run by a real ffmpeg / libass) for five caption / blur-box cases over 16:9, 9:16, 1:1 and a
 * 4:3 movie. Both sides draw with the bundled Pyidaungsu font; boxes are found as the pixels that change when the boxes
 * (or the caption) are added, never from the numbers the code was given. Every edge must agree within ±2 % of the
 * output frame. Needs ffmpeg like the other real-ffmpeg tests (CI sets `AUTOFLOW_EXPECT_FFMPEG=1`); writes side-by-side
 * evidence to `build/reports/s10/`.
 */
@OptIn(ExperimentalTestApi::class)
class PreviewExportParityTest {
    private data class Case(
        val name: String,
        val movieW: Int,
        val movieH: Int,
        val ratio: Ratio,
        val background: FrameBackground,
        val masks: List<RecapMask>,
        val style: CaptionStyle = CaptionStyle(),
    )

    private val cases = listOf(
        Case("1-16x9-bottom-strip-captions", 640, 360, Ratio.R16_9, FrameBackground.BARS, listOf(RecapMask(MaskPreset.BOTTOM, MaskPreset.BOTTOM.rect))),
        Case("2-9x16-blurred-bg-bottom-strip", 640, 360, Ratio.R9_16, FrameBackground.BLUR, listOf(RecapMask(MaskPreset.BOTTOM, MaskPreset.BOTTOM.rect))),
        Case("3-1x1-custom-box-lower-left", 640, 360, Ratio.R1_1, FrameBackground.BARS, listOf(RecapMask(MaskPreset.CUSTOM, listOf(0.06f, 0.6f, 0.44f, 0.22f)))),
        Case(
            "4-16x9-top-strip-logo-usual-place-outline", 640, 360, Ratio.R16_9, FrameBackground.BARS,
            listOf(RecapMask(MaskPreset.TOP, MaskPreset.TOP.rect), RecapMask(MaskPreset.LOGO_TR, listOf(0.76f, 0.3f, 0.2f, 0.14f))),
            CaptionStyle(strokeWidth = 3, strokeColour = "#000000"),
        ),
        Case(
            "5-4x3-in-16x9-colour-custom-box-boxed", 480, 360, Ratio.R16_9, FrameBackground.COLOUR,
            listOf(RecapMask(MaskPreset.CUSTOM, listOf(0.5f, 0.64f, 0.44f, 0.2f))), CaptionStyle(box = true),
        ),
    )

    private val captionText = "မိုးရွာနေတဲ့ ညမှာ"

    private val ffmpeg: String? by lazy {
        listOfNotNull(System.getenv("AUTOFLOW_FFMPEG"), "ffmpeg")
            .firstOrNull { runCatching { exec(listOf(it, "-hide_banner", "-version")).first == 0 }.getOrDefault(false) }
    }

    private fun exec(cmd: List<String>, dir: File? = null): Pair<Int, String> {
        val p = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(180, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to "timeout\n$text" }
        return p.exitValue() to text
    }

    /** A normalised box `[left, top, right, bottom]`. */
    private data class Box4(val l: Float, val t: Float, val r: Float, val b: Float) {
        val cx get() = (l + r) / 2
        val cy get() = (t + b) / 2
        override fun toString() = "[%.4f, %.4f, %.4f, %.4f]".format(l, t, r, b)
    }

    /** Bounding box of the pixels that differ by more than [threshold] on some channel, inside [win] (pixels). */
    private fun changed(a: BufferedImage, b: BufferedImage, threshold: Int, win: IntArray = intArrayOf(0, 0, a.width, a.height)): IntArray? {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var bo = -1
        for (y in max(0, win[1]) until minOf(a.height, win[3])) for (x in max(0, win[0]) until minOf(a.width, win[2])) {
            val p = a.getRGB(x, y); val q = b.getRGB(x, y)
            val d = intArrayOf(16, 8, 0).maxOf { s -> abs(((p shr s) and 0xFF) - ((q shr s) and 0xFF)) }
            if (d > threshold) { l = minOf(l, x); t = minOf(t, y); r = max(r, x + 1); bo = max(bo, y + 1) }
        }
        return if (r < 0) null else intArrayOf(l, t, r, bo)
    }

    /** Where the movie picture sits in the output frame (fit, centred: bars / blurred / colour background). */
    private fun picture(c: Case, out: Output): FloatArray {
        val s = minOf(out.width / c.movieW.toFloat(), out.height / c.movieH.toFloat())
        val pw = c.movieW * s; val ph = c.movieH * s
        return floatArrayOf((out.width - pw) / 2, (out.height - ph) / 2, pw, ph)
    }

    @Test
    fun previewAndExportAgreeWithinTwoPercentOfTheFrame() {
        val bin = ffmpeg ?: run {
            if (System.getenv("AUTOFLOW_EXPECT_FFMPEG") == "1") fail("AUTOFLOW_EXPECT_FFMPEG=1 but no ffmpeg on PATH / AUTOFLOW_FFMPEG")
            throw org.junit.AssumptionViolatedException("PreviewExportParityTest not executed: no ffmpeg binary")
        }
        val dir = Files.createTempDirectory("s10").toFile()
        val evidence = File(System.getenv("AUTOFLOW_S10_DIR") ?: "build/reports/s10").apply { mkdirs() }
        val report = StringBuilder("case\tbox\tpreview(frame 0-1)\texport(frame 0-1)\tmax edge diff (% of frame)\n")
        val problems = mutableListOf<String>()
        try {
            val fontsDir = File(dir, "fonts").apply { mkdirs() }
            val repoFonts = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "fonts") }.first { File(it, "Pyidaungsu-Regular.ttf.b64").isFile }
            val ttf = Base64.getMimeDecoder().decode(File(repoFonts, "Pyidaungsu-Regular.ttf.b64").readBytes())
            File(fontsDir, "Pyidaungsu-Regular.ttf").writeBytes(ttf)
            val info = assertNotNullFont(FontInfo.parse(ttf))

            // The export side first: one real render per case and variant (full / boxes only / bare), frame at 1.0 s.
            data class Rendered(val out: Output, val recap: RecapV2, val full: BufferedImage, val boxes: BufferedImage, val bare: BufferedImage, val frame: BufferedImage)
            val rendered = cases.map { c ->
                val src = File(dir, "source/${c.name}.mp4").apply { parentFile.mkdirs() }
                val (c0, l0) = exec(listOf(bin, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "color=c=gray:s=${c.movieW}x${c.movieH}:r=10:d=2",
                    "-vf", "geq=lum='if(mod(floor(X/6)+floor(Y/6),2),210,50)':cb=128:cr=128", "-c:v", "libx264", "-pix_fmt", "yuv420p", src.path))
                assertEquals(0, c0, l0.takeLast(2000))
                val framePng = File(dir, "source/${c.name}.png")
                assertEquals(0, exec(listOf(bin, "-hide_banner", "-y", "-nostdin", "-ss", "1.0", "-i", src.path, "-frames:v", "1", framePng.path)).first)
                val recap = RecapV2(
                    movie = RecapMovie(src.path, src.length(), 2_000, c.movieW, c.movieH, hasAudio = false),
                    visual = RecapVisual(masks = c.masks, background = c.background, backgroundColour = "#204060", outputRatio = c.ratio),
                    captionStyle = c.style,
                )
                val out = RenderPresets.preview(c.ratio)
                val overlay = RecapRender.captionOverlay(recap, out, SrtCue(0, 2_000, captionText), info.family)
                val graph = RenderGraph(
                    clips = listOf(Clip(null, trimInMs = 0, trimOutMs = 2_000, source = src.path)),
                    overlays = listOf(overlay),
                    output = out,
                    voice = VoiceTrack(emptyList(), clipsVolume = 0.0),
                    looks = recap.looks,
                    masks = recap.visual.masks,
                    background = recap.visual.background,
                    backgroundColour = recap.visual.backgroundColour,
                )
                fun render(g: RenderGraph, tag: String): BufferedImage {
                    val mp4 = File(dir, "${c.name}-$tag.mp4")
                    val compiled = RenderCompiler.compile(g, RenderCompiler.Env(
                        resolve = { null }, songPath = null, assPath = File(dir, "${c.name}-$tag.ass").path, fontsDir = fontsDir.path,
                        outputPath = mp4.path, assScale = { f -> info.assScale.takeIf { f == info.family } },
                        resolveSource = { RenderCompiler.ClipMedia(it, hasAudio = false) },
                    ))
                    val ok = compiled as? RenderCompiler.Compiled.Ok ?: fail("compile failed for ${c.name}/$tag: $compiled")
                    ok.ass?.let { File(dir, "${c.name}-$tag.ass").writeText(it) }
                    val (code, log) = exec(listOf(bin) + ok.args, dir)
                    assertEquals(0, code, log.takeLast(3000))
                    val png = File(dir, "${c.name}-$tag.png")
                    assertEquals(0, exec(listOf(bin, "-hide_banner", "-y", "-nostdin", "-ss", "1.0", "-i", mp4.path, "-frames:v", "1", png.path)).first)
                    return ImageIO.read(png)
                }
                Rendered(out, recap, render(graph, "full"), render(graph.copy(overlays = emptyList()), "boxes"),
                    render(graph.copy(overlays = emptyList(), masks = emptyList()), "bare"), ImageIO.read(framePng))
            }

            // The preview side: the same recap drawn by Compose at a phone-like 480 px wide picture.
            var current by mutableStateOf<Triple<RecapV2, Output, String?>?>(null)
            var image by mutableStateOf<ImageBitmap?>(null)
            var size by mutableStateOf(480 to 270)
            runDesktopComposeUiTest(width = 480, height = 480) {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalUiFontFamily provides io.github.alalkipgen.autoflow.ui.BurmeseTestFont.family) {
                        Box(Modifier.size(size.first.dp, size.second.dp)) {
                            current?.let { (recap, out, caption) -> RecapPreviewPicture(recap, out, image, caption, Modifier.fillMaxSize()) }
                        }
                    }
                }
                fun capture(recap: RecapV2, out: Output, caption: String?): BufferedImage {
                    current = Triple(recap, out, caption)
                    waitForIdle()
                    val all = onRoot().captureToImage().toAwtImage()
                    return all.getSubimage(0, 0, size.first, size.second)
                }
                cases.zip(rendered).forEach { (c, r) ->
                    size = 480 to (480 * c.movieH / c.movieW)
                    image = r.frame.toComposeImageBitmap()
                    val full = capture(r.recap, r.out, captionText)
                    val boxes = capture(r.recap, r.out, null)
                    val bare = capture(r.recap.copy(visual = r.recap.visual.copy(masks = emptyList())), r.out, null)
                    val (px, py, pw, ph) = picture(c, r.out).let { listOf(it[0], it[1], it[2], it[3]) }
                    val fw = r.out.width.toFloat(); val fh = r.out.height.toFloat()
                    // Preview pixels → output frame 0–1 (the preview shows the movie picture only).
                    fun previewToFrame(b: IntArray) = Box4(
                        (px + b[0] / size.first.toFloat() * pw) / fw, (py + b[1] / size.second.toFloat() * ph) / fh,
                        (px + b[2] / size.first.toFloat() * pw) / fw, (py + b[3] / size.second.toFloat() * ph) / fh,
                    )
                    fun exportToFrame(b: IntArray) = Box4(b[0] / fw, b[1] / fh, b[2] / fw, b[3] / fh)
                    fun compare(label: String, p: Box4?, e: Box4?) {
                        if (p == null || e == null) { problems += "${c.name} $label: not found (preview=$p export=$e)"; return }
                        val d = maxOf(abs(p.l - e.l), abs(p.t - e.t), abs(p.r - e.r), abs(p.b - e.b))
                        report.append("${c.name}\t$label\t$p\t$e\t${"%.2f".format(d * 100)}\n")
                        if (d > 0.02f) problems += "${c.name} $label: preview $p vs export $e differ by ${"%.2f".format(d * 100)} % of the frame"
                    }
                    c.masks.forEach { m ->
                        val rr = m.rect
                        // Search window: the box ± 5 % of the picture, in each side's pixels.
                        fun win(w: Float, h: Float, ox: Float, oy: Float) = intArrayOf(
                            (ox + (rr[0] - 0.05f) * w).toInt(), (oy + (rr[1] - 0.05f) * h).toInt(),
                            (ox + (rr[0] + rr[2] + 0.05f) * w).toInt() + 1, (oy + (rr[1] + rr[3] + 0.05f) * h).toInt() + 1,
                        )
                        val p = changed(boxes, bare, 24, win(size.first.toFloat(), size.second.toFloat(), 0f, 0f))?.let(::previewToFrame)
                        val e = changed(r.boxes, r.bare, 24, win(pw, ph, px, py))?.let(::exportToFrame)
                        compare("blur ${m.preset}", p, e)
                    }
                    val pc = changed(full, boxes, 48)?.let(::previewToFrame)
                    val ec = changed(r.full, r.boxes, 48)?.let(::exportToFrame)
                    compare("caption", pc, ec)
                    if (pc != null && ec != null) {
                        val dc = maxOf(abs(pc.cx - ec.cx), abs(pc.cy - ec.cy))
                        report.append("${c.name}\tcaption centre\t(%.4f, %.4f)\t(%.4f, %.4f)\t%.2f\n".format(pc.cx, pc.cy, ec.cx, ec.cy, dc * 100))
                    }
                    // Evidence: preview (scaled into its place in the frame) beside the export frame.
                    val side = BufferedImage(r.out.width * 2 + 8, r.out.height, BufferedImage.TYPE_INT_RGB)
                    side.createGraphics().apply {
                        drawImage(full, px.toInt(), py.toInt(), pw.toInt(), ph.toInt(), null)
                        drawImage(r.full, r.out.width + 8, 0, null)
                        dispose()
                    }
                    ImageIO.write(side, "png", File(evidence, "${c.name}.png"))
                }
            }
        } finally {
            File(evidence, "s10-report.tsv").writeText(report.toString())
            println("S10 PreviewExportParityTest\n$report")
            dir.deleteRecursively()
        }
        assertEquals(emptyList<String>(), problems)
        assertTrue(report.lines().count { it.contains("\tcaption\t") } == cases.size, "a caption was measured in every case")
    }

    private fun assertNotNullFont(info: FontInfo?): FontInfo = info ?: fail("Pyidaungsu did not parse")
}
