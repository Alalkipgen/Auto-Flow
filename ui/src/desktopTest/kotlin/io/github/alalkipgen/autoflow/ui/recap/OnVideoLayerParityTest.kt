package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.render.*
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.export.ExportHarness
import io.github.alalkipgen.autoflow.ui.export.RecapFrames
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

/** Real pointer -> explicit Save -> production compositor / Exact Frame / completed MP4 decoded from the start.
 * The static capture is not decoder synchronization proof; OnVideoTimelinePlaybackRuntimeTest owns that gate. */
@OptIn(ExperimentalTestApi::class)
class OnVideoLayerParityTest {
    @Test fun landscapeBlurLetterbox() = compare("16x9-blur", Ratio.R16_9, emptyList(), LayerType.BLUR, BackgroundMode.COLOUR)
    @Test fun portraitImageMirrorZoom() = compare("9x16-image", Ratio.R9_16, listOf(Look.MIRROR, Look.ZOOM), LayerType.IMAGE, BackgroundMode.IMAGE)
    @Test fun squareTextZoomBlurBackground() = compare("1x1-text", Ratio.R1_1, listOf(Look.ZOOM), LayerType.TEXT, BackgroundMode.BLUR)
    @Test fun portraitBlurEveryLook() = compare("9x16-blur", Ratio.R9_16, Look.entries, LayerType.BLUR, BackgroundMode.IMAGE)
    @Test fun landscapeImageMirrorWarmBlurBackground() = compare("16x9-image", Ratio.R16_9, listOf(Look.MIRROR, Look.WARM), LayerType.IMAGE, BackgroundMode.BLUR)
    @Test fun squareTextFill() = compare("1x1-fill-text", Ratio.R1_1, listOf(Look.FILL), LayerType.TEXT, BackgroundMode.COLOUR)

    private fun bounds(a: BufferedImage, b: BufferedImage): List<Float>? {
        var l = a.width; var t = a.height; var r = -1; var d = -1
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getRGB(x, y); val q = b.getRGB(x, y)
            if (listOf(0, 8, 16).maxOf { abs((p ushr it and 255) - (q ushr it and 255)) } > 32) {
                l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x); d = maxOf(d, y)
            }
        }
        return if (r < 0) null else listOf(l.toFloat() / a.width, t.toFloat() / a.height, (r + 1f) / a.width, (d + 1f) / a.height)
    }
    private fun compare(name: String, ratio: Ratio, looks: List<Look>, type: LayerType, background: BackgroundMode) = ExportHarness().use { h ->
        val evidence = onVideoEvidence("parity-$name"); val initial = h.session.open!!.project.recap!!
        // Uniform testsrc2 colour regions hide blur edges. Use the SAME edge-rich, temporally marked
        // source as the existing S10 oracle, in this test's disposable source/ (never artifacts/).
        val movie = File(h.fixture.dir, initial.movie!!.file)
        val patches = listOf("red", "lime", "blue", "yellow", "magenta").mapIndexed { i, c ->
            "drawbox=0:0:80:60:color=$c:t=fill:enable='gte(t,${i * 2})*lt(t,${i * 2 + 2})'"
        }.joinToString(",")
        val args = listOf(System.getenv("AUTOFLOW_FFMPEG") ?: "ffmpeg", "-v", "error", "-nostdin", "-y", "-f", "lavfi", "-i",
            "color=gray:s=320x180:r=24:d=10", "-vf", "geq=lum='if(mod(floor(X/4)+floor(Y/4),2),200,50)':cb=128:cr=128,$patches",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", movie.path)
        File(evidence, "fixture-command.txt").writeText(args.joinToString("\n"))
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText(); File(evidence, "fixture.log").writeText(log)
        assertEquals(0, process.waitFor(), log)
        // Match the actual Compose font bytes, rather than comparing Pyidaungsu with ExportHarness' Noto.
        val fonts = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "fonts") }
            .first { File(it, "Pyidaungsu-Regular.ttf.b64").isFile }
        val fontFile = File(h.root, "Pyidaungsu-Regular.ttf").apply { writeBytes(Base64.getMimeDecoder().decode(File(fonts, "Pyidaungsu-Regular.ttf.b64").readBytes())) }
        h.settings.update { it.copy(fontPaths = listOf(fontFile.path)) }
        val bg = initial.layers.first().copy(params = initial.layers.first().params.copy(mode = background))
        val layer = when (type) {
            LayerType.BLUR -> initial.layers.single { it.type == type }.copy(rect = listOf(.3f, .35f, .3f, .25f), params = LayerParams(sigma = 14f))
            LayerType.IMAGE -> initial.layers.single { it.type == type }.copy(rect = listOf(.25f, .28f, .28f, .15f), params = initial.layers.single { it.type == type }.params.copy(opacity = 1f))
            else -> initial.layers.single { it.type == type }.copy(rect = listOf(.15f, .27f, .6f, .22f), params = LayerParams(textMy = "LAYER မြန်မာစာ"))
        }.copy(fromMs = 1000, toMs = 3000)
        val recap = initial.copy(movie = initial.movie!!.copy(sizeBytes = movie.length()), looks = looks, layers = listOf(bg, layer), visual = initial.visual.copy(outputRatio = ratio))
        assertIs<CoreResult.Ok<*>>(h.session.save(h.session.open!!.project.copy(recap = recap))); h.recap.refresh()
        val before = h.bytes(); val at = 1500L; val out = RenderPresets.preview(ratio)
        val cues = assertNotNull(h.recap.previewSource.resolve(h.recap.state.value)).timeline.captions
        val source = onVideoDecode(File(h.fixture.dir, recap.movie!!.file), 3500, File(evidence, "source.png")).toComposeImageBitmap()
        val images = NativePreviewAssets(h.fs).load(h.fixture.dir.path, recap); val e = h.recap.layerEditor
        val vw = if (ratio == Ratio.R9_16) 270 else 360; val vh = if (ratio == Ratio.R16_9) 203 else if (ratio == Ratio.R9_16) 480 else 360
        var bare by mutableStateOf(false)
        lateinit var preview: BufferedImage; lateinit var base: BufferedImage
        runDesktopComposeUiTest(width = 412, height = 760) {
            setContent { CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family) {
                val open by h.current.state.collectAsState(); val r = open!!.project.recap!!
                val shown = if (bare) r.copy(layers = r.layers.filterNot { it.id == layer.id }) else r
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    NativeLayerPreview(source, shown, out, at, cues, images, e, true,
                        Modifier.size(vw.dp, vh.dp).testTag("on-video-frame"))
                    RecapLayerControls(r, true, h.recap, studio = true)
                }
            } }
            onNodeWithTag("layer-edit-${layer.id}").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("on-video-frame").performScrollTo().assertIsDisplayed()
            val old = e.value()!!.rect
            onNodeWithTag("layer-move-handle").assertIsDisplayed().performTouchInput {
                val p = Offset(width * .2f, height * .2f); down(p); moveTo(p + Offset(vw * .05f, vh * .04f)); up()
            }
            waitForIdle()
            assertTrue(abs(e.value()!!.rect[0] - old[0] - .05f) < .01f)
            onNodeWithTag("layer-resize-handle").assertIsDisplayed().performMouseInput {
                moveTo(center); press(); moveTo(center + Offset(-vw * .03f, -vh * .02f)); release()
            }
            waitForIdle(); val draft = e.value()!!
            assertTrue(draft.rect[2] < old[2]); assertEquals(before, h.bytes(), "no output or project writes before Save")
            ImageIO.write(onNodeWithTag("on-video-frame").captureToImage().toAwtImage(), "png", File(evidence, "unsaved-handles.png"))
            onNodeWithTag("layer-save").performScrollTo().assertIsDisplayed().assertIsEnabled().performMouseInput { click() }
            waitForIdle(); assertNull(e.state.value.draft)
            val saved = h.session.open!!.project.recap!!
            assertEquals(draft, saved.layers.single { it.id == layer.id }); assertEquals(recap.copy(layers = saved.layers), saved)
            onNodeWithTag("on-video-frame").performScrollTo().assertIsDisplayed()
            onNodeWithTag("layer-move-handle").assertDoesNotExist()
            preview = onNodeWithTag("on-video-frame").captureToImage().toAwtImage()
            bare = true; waitForIdle(); base = onNodeWithTag("on-video-frame").captureToImage().toAwtImage()
        }
        val saved = h.session.open!!; val noLayer = saved.copy(project = saved.project.copy(recap = saved.project.recap!!.copy(layers = listOf(bg))))
        val runner = RenderJobRunner(h.fs, FfmpegRunner(h.media.runner!!, h.media.ffmpeg()!!), MediaProbe(h.media.runner!!, h.media.ffprobe()!!))
        val font = h.frames.font(); assertNull(h.frames.installFont(saved, font))
        // Captions are deliberately identical in each pair. Their separate production painter stays last.
        fun job(open: io.github.alalkipgen.autoflow.ui.projects.OpenProject, label: String) = assertNotNull(h.frames.job(open, Quality.PREVIEW, font?.family, "exports/on-video-$label", "on-video parity"))
        val fullJob = job(saved, "saved"); val bareJob = job(noLayer, "baseline")
        val video = assertIs<RenderState.Done>(runBlocking { runner.run(fullJob) }).output
        val bareVideo = assertIs<RenderState.Done>(runBlocking { runner.run(bareJob) }).output
        val exported = onVideoDecode(File(video), at, File(evidence, "mp4-saved.png"))
        val exportedBase = onVideoDecode(File(bareVideo), at, File(evidence, "mp4-baseline.png"))
        val exact = assertIs<RecapFrames.Result.Ok>(runBlocking { h.frames.frame(at, image = ".cache/frames/on-video.png", open = saved) }).frame
        val exactBase = assertIs<RecapFrames.Result.Ok>(runBlocking { h.frames.frame(at, image = ".cache/frames/on-video-baseline.png", open = noLayer) }).frame
        val exactImage = ImageIO.read(exact.bytes.inputStream()); val exactBare = ImageIO.read(exactBase.bytes.inputStream())
        ImageIO.write(preview, "png", File(evidence, "preview-saved.png")); ImageIO.write(base, "png", File(evidence, "preview-baseline.png"))
        File(evidence, "exact-saved.png").writeBytes(exact.bytes); File(evidence, "exact-baseline.png").writeBytes(exactBase.bytes)
        val pb = assertNotNull(bounds(preview, base), "production preview has changed pixels")
        val report = StringBuilder("$name at=$at source=3500; real pointer move+resize+explicit Save; unmodified production jobs; completed 540p MP4 and Exact Frame; actual captions retained in every pair\npreview=$pb\n")
        try {
            for ((label, a, b) in listOf(Triple("Exact Frame", exactImage, exactBare), Triple("MP4", exported, exportedBase))) {
                val eb = assertNotNull(bounds(a, b), "$label has changed pixels")
                val delta = pb.zip(eb).maxOf { abs(it.first - it.second) }
                report.append("$label=$eb maximumEdgeDelta=$delta\n")
                assertTrue(delta <= .02f, "$name $label edge mismatch $delta > 2%")
            }
        } finally { File(evidence, "parity.txt").writeText(report.toString()) }
    }
}
