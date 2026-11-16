package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.media.render.*
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import org.junit.AssumptionViolatedException
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

/** Extended S10: captures the PRODUCTION native output surface, not shared geometry or a test-side renderer.
 * Real ffmpeg/libass native-v2 exports; 3 clips / 2 stable blocks with non-identity source timestamps.
 * Pixel geometry tolerance ±2% of output edge; RGB MAE <= 0.08 (not bit-identical text/blur/YUV).
 * Static parity is NOT live synchronization evidence: TimelinePlaybackRuntimeTest owns that gate. */
@OptIn(ExperimentalTestApi::class)
class NativeOutputParityTest {
    @Test fun landscapeNativeColourAndTimedLayers() = compare("native-16x9-colour", Ratio.R16_9, emptyList(), BackgroundMode.COLOUR)
    @Test fun portraitMirrorZoomNativeImage() = compare("native-9x16-mirror-zoom-image", Ratio.R9_16, listOf(Look.MIRROR, Look.ZOOM), BackgroundMode.IMAGE)
    @Test fun squareZoomNativeBlur() = compare("native-1x1-zoom-blur", Ratio.R1_1, listOf(Look.ZOOM), BackgroundMode.BLUR)
    @Test fun portraitMirrorZoomFillWarmIgnoresBackground() = compare("native-9x16-all-looks", Ratio.R9_16, Look.entries, BackgroundMode.IMAGE)
    @Test fun landscapeMirrorWarmBlur() = compare("native-16x9-mirror-warm-blur", Ratio.R16_9, listOf(Look.MIRROR, Look.WARM), BackgroundMode.BLUR)
    @Test fun squareFillAndOrderedNativeLayers() = compare("native-1x1-fill", Ratio.R1_1, listOf(Look.FILL), BackgroundMode.COLOUR)
    @Test fun portraitZoomLegacyImageBehindNativeLayers() = compare("native-9x16-legacy-image", Ratio.R9_16, listOf(Look.ZOOM), null)

    @Test fun imageOccludesEarlierTextAndBlurReadsCompositedImageWhileCaptionsRemainLast() = compare("native-overlap-order", Ratio.R16_9, emptyList(), BackgroundMode.COLOUR, overlap = true)

    private val bin get() = System.getenv("AUTOFLOW_FFMPEG") ?: "ffmpeg"
    private fun command(args: List<String>) {
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor(), text.takeLast(2500))
    }
    private fun frame(file: File, ms: Long, target: File): BufferedImage {
        command(listOf(bin, "-v", "error", "-nostdin", "-y", "-ss", "${ms / 1000.0}", "-i", file.path, "-frames:v", "1", target.path))
        return ImageIO.read(target)
    }
    private fun bounds(a: BufferedImage, b: BufferedImage, area: List<Float>, threshold: Int = 32): List<Float>? {
        val w = a.width; val h = a.height
        var left = w; var top = h; var right = -1; var bottom = -1
        for (y in (area[1] * h).toInt().coerceAtLeast(0) until ((area[1] + area[3]) * h).toInt().coerceAtMost(h))
            for (x in (area[0] * w).toInt().coerceAtLeast(0) until ((area[0] + area[2]) * w).toInt().coerceAtMost(w)) {
                val p = a.getRGB(x, y); val q = b.getRGB(x, y)
                if (listOf(0, 8, 16).maxOf { abs((p ushr it and 255) - (q ushr it and 255)) } > threshold) {
                    left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
                }
            }
        return if (right < 0) null else listOf(left.toFloat() / w, top.toFloat() / h, (right + 1f) / w, (bottom + 1f) / h)
    }
    private fun compare(name: String, ratio: Ratio, looks: List<Look>, mode: BackgroundMode?, overlap: Boolean = false) {
        if (runCatching { ProcessBuilder(bin, "-version").start().waitFor() == 0 }.getOrDefault(false).not()) {
            if (System.getenv("AUTOFLOW_EXPECT_FFMPEG") == "1") fail("ffmpeg required by S10")
            throw AssumptionViolatedException("NativeOutputParityTest not executed: ffmpeg unavailable")
        }
        val root = Files.createTempDirectory("s10-native").toFile()
        val evidence = File(System.getenv("AUTOFLOW_S10_DIR") ?: "build/reports/s10", name).apply { mkdirs() }
        val report = StringBuilder("case=$name; geometry edge tolerance=0.02; RGB MAE limit=0.08; 24fps export\n")
        try {
            val built = QaRecapFixture.create(root)
            val dir = built.dir; val fs = JvmFs(); val store = ArtifactStore(fs, dir.path)
            val original = built.project.recap!!
            val movieFile = File(dir, original.movie!!.file)
            val patches = listOf("red", "lime", "blue", "yellow", "magenta").mapIndexed { i, c -> "drawbox=0:0:80:60:color=$c:t=fill:enable='gte(t,${i * 2})*lt(t,${i * 2 + 2})'" }.joinToString(",")
            command(listOf(bin, "-v", "error", "-nostdin", "-y", "-f", "lavfi", "-i", "color=gray:s=320x180:r=24:d=10", "-vf", "geq=lum='if(mod(floor(X/4)+floor(Y/4),2),200,50)':cb=128:cr=128,$patches", "-c:v", "libx264", "-pix_fmt", "yuv420p", movieFile.path))
            val imageId = original.layers.first { it.type == LayerType.IMAGE }.params.image!!
            val bg = original.layers.first().copy(rect = emptyList(), params = LayerParams(mode = mode ?: BackgroundMode.IMAGE, image = imageId, colour = "#203040"))
            val image = Layer("image", LayerType.IMAGE, if (overlap) listOf(0f, 0f, 1f, 1f) else listOf(.70f, .08f, .18f, .08f), 1000, 3000, LayerParams(image = imageId, opacity = if (overlap) 1f else .8f))
            val blur = Layer("blur", LayerType.BLUR, listOf(.38f, .42f, .20f, .12f), 1000, 3000, LayerParams(sigma = 12f, feather = .01f))
            val text = Layer("text", LayerType.TEXT, listOf(.10f, .68f, .60f, .15f), 1000, 3000, LayerParams(textMy = "LAYER မြန်မာစာ"))
            val border = Layer("border", LayerType.BORDER, rect = emptyList(), fromMs = 1000, toMs = 3000, params = LayerParams(px = 8, colour = "#00FFFF"))
            fun artifact(ref: String): String? = (store.get(ArtifactId(ref)) as? CoreResult.Ok)?.value?.let { File(dir, "artifacts/${it.file}").path }
            val backgroundPath = artifact(imageId)!!
            val recap = original.copy(looks = looks, layers = listOf(text, image, blur, border) + if (mode == null) emptyList() else listOf(bg),
                visual = original.visual.copy(outputRatio = ratio, masks = emptyList(), background = FrameBackground.IMAGE, backgroundImageFile = File(backgroundPath).relativeTo(dir).path.replace('\\', '/')))
            val bare = recap.copy(layers = recap.layers.filter { it.type == LayerType.BACKGROUND }, captions = recap.captions.copy(burn = false))
            val noCaps = recap.copy(captions = recap.captions.copy(burn = false))
            val out = RenderPresets.preview(ratio)
            val fontDir = File(root, "fonts").apply { mkdirs() }
            val fonts = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "fonts/Pyidaungsu-Regular.ttf.b64") }.first { it.isFile }
            val ttf = Base64.getMimeDecoder().decode(fonts.readBytes())
            File(fontDir, "Pyidaungsu-Regular.ttf").writeBytes(ttf)
            val info = FontInfo.parse(ttf)!!
            val takeFile: (BlockTake) -> String? = { artifact(it.audio.value) }
            val timeline = RecapRender.timeline(recap, built.story, takeFile, out)
            assertEquals(listOf(2000L, 6000L, 4000L), timeline.clips.map { it.sourceFromMs })
            fun render(r: RecapV2, tag: String): File {
                val file = File(root, "$tag.mp4"); val ass = File(root, "$tag.ass")
                val graph = RecapRender.graph(r, built.story, ratio, Quality.PREVIEW, takeFile, info.family).let { it.copy(voice = it.voice?.copy(loudnessLufs = null, polish = false), backgroundImageFile = backgroundPath) }
                val compiled = assertIs<RenderCompiler.Compiled.Ok>(RenderCompiler.compile(graph, RenderCompiler.Env(
                    resolve = { null }, songPath = null, assPath = ass.path, fontsDir = fontDir.path, outputPath = file.path,
                    assScale = { font -> info.assScale.takeIf { font == info.family } }, placeholderFont = info.family,
                    resolveSource = { RenderCompiler.ClipMedia(File(dir, it).path, false) }, voicePath = { it }, resolveLayerImage = ::artifact)))
                compiled.ass?.let { ass.writeText(it) }; compiled.layerAssFiles.forEach { (p, text) -> File(p).writeText(text) }
                command(listOf(bin) + compiled.args)
                return file
            }
            val fullVideo = render(recap, "full"); val nativeVideo = render(noCaps, "native"); val bareVideo = render(bare, "bare")
            val withoutText = noCaps.copy(layers = noCaps.layers.filter { it.type != LayerType.TEXT })
            val noTextVideo = if (overlap) render(withoutText, "no-text") else null
            val assets = NativePreviewAssets(fs).load(dir.path, recap)
            val times = listOf(150L, 750L, 1500L, 2500L, 3500L, 4500L, 5300L)
            var picture by mutableStateOf<ImageBitmap?>(null); var current by mutableStateOf(recap); var time by mutableLongStateOf(0)
            val vw = if (ratio == Ratio.R9_16) 270 else 480; val vh = if (ratio == Ratio.R16_9) 270 else 480
            runDesktopComposeUiTest(width = 500, height = 640) {
                setContent { CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family) { picture?.let { NativeOutputPicture(it, current, out, time, timeline.captions, assets, Modifier.size(vw.dp, vh.dp)) } } }
                fun capture(r: RecapV2): BufferedImage {
                    current = r; waitForIdle()
                    return onNodeWithContentDescription("Native output-frame proxy").captureToImage().toAwtImage()
                }
                fun geometry(label: String, a: BufferedImage, b: BufferedImage, c: BufferedImage, d: BufferedImage, roi: List<Float>) {
                    val p = assertNotNull(bounds(a, b, roi), "$name $time $label preview pixels not found")
                    val e = assertNotNull(bounds(c, d, roi), "$name $time $label export pixels not found")
                    val delta = p.zip(e).maxOf { abs(it.first - it.second) }
                    report.append("$time\t$label\tpreview=$p\texport=$e\tdelta=$delta\n")
                    assertTrue(delta <= .02f, "$name $time $label edge difference $delta > 2%")
                }
                for (ms in times) {
                    time = ms
                    val sourceMs = timeline.clipAt(ms)!!.sourceAt(ms)
                    picture = frame(movieFile, sourceMs, File(root, "source.png")).toComposeImageBitmap()
                    val full = capture(recap); val native = capture(noCaps); val base = capture(bare)
                    val ef = frame(fullVideo, ms, File(root, "ef.png")); val en = frame(nativeVideo, ms, File(root, "en.png")); val eb = frame(bareVideo, ms, File(root, "eb.png"))
                    val scaled = BufferedImage(vw, vh, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { drawImage(ef, 0, 0, vw, vh, null); dispose() } }
                    val side = BufferedImage(vw * 2, vh, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { drawImage(full, 0, 0, null); drawImage(ef, vw, 0, vw, vh, null); dispose() } }
                    ImageIO.write(side, "png", File(evidence, "$ms.png"))
                    var sum = 0L
                    for (y in 0 until vh) for (x in 0 until vw) for (shift in listOf(0, 8, 16)) sum += abs((full.getRGB(x, y) ushr shift and 255) - (scaled.getRGB(x, y) ushr shift and 255))
                    val mae = sum.toDouble() / (vw * vh * 3 * 255)
                    report.append("$ms\tsource=$sourceMs\tcaptions=${timeline.captionsAt(ms).map { it.text }}\tRGB_MAE=$mae\n")
                    assertTrue(mae <= .08, "$name $ms RGB MAE=$mae > 0.08")
                    if (ms in 1000..3000 && overlap) {
                        val hidden = capture(withoutText)
                        val hiddenExport = frame(noTextVideo!!, ms, File(root, "hidden.png"))
                        assertNull(bounds(native, hidden, listOf(0f, 0f, 1f, 1f), 48), "IMAGE must occlude the earlier TEXT in actual Compose output")
                        assertNull(bounds(en, hiddenExport, listOf(0f, 0f, 1f, 1f), 48), "IMAGE must occlude the earlier TEXT in actual ffmpeg output")
                        report.append("$ms\tTEXT occluded by later opaque IMAGE in BOTH renders; blur reads that image\n")
                    } else if (ms in 1000..3000) {
                        geometry("IMAGE", native, base, en, eb, listOf(.67f, .05f, .25f, .40f))
                        geometry("BLUR", native, base, en, eb, listOf(.35f, .40f, .25f, .16f))
                        geometry("TEXT", native, base, en, eb, listOf(.06f, .64f, .68f, .22f))
                        geometry("BORDER", native, base, en, eb, listOf(0f, 0f, 1f, .05f))
                    } else {
                        assertNull(bounds(native, base, listOf(0f, 0f, 1f, 1f), 48), "all native effects inactive at $ms")
                    }
                    if (timeline.captionsAt(ms).isNotEmpty()) geometry("CAPTION", full, native, ef, en, listOf(0f, .84f, 1f, .16f))
                    else assertNull(bounds(full, native, listOf(0f, 0f, 1f, 1f), 48), "no stale caption at $ms")

                }
            }
        } finally { File(evidence, "metrics.tsv").writeText(report.toString()); root.deleteRecursively() }
    }
}
