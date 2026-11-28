package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.BackgroundMode
import io.github.alalkipgen.autoflow.core.recap.Layer
import io.github.alalkipgen.autoflow.core.recap.LayerParams
import io.github.alalkipgen.autoflow.core.recap.LayerType
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

/**
 * The layer matrix of audit F08 / F12 with a real ffmpeg through the production [RenderJobRunner] (compile, `.ass`,
 * ffmpeg, rename): every layer type at 16:9, 9:16 and 1:1 over a 4:3 movie (so every ratio has bars), each mp4
 * checked for its length and streams and its decoded pixels compared with the plain render — the region the layer
 * owns must change (or show the layer's colour) and the rest must not.
 */
class LayerMatrixFfmpegTest {
    private data class Frame(val ratio: Ratio, val w: Int, val h: Int) {
        /** The 4:3 movie fitted inside the frame. */
        val fw = minOf(w, h * 4 / 3); val fh = minOf(h, w * 3 / 4)
        val fx = (w - fw) / 2; val fy = (h - fh) / 2
        /** The middle of the first bar (left when pillarboxed, top when letterboxed). */
        val bar = if (fx > 0) fx / 2 to h / 2 else w / 2 to fy / 2
    }

    private val frames = listOf(Frame(Ratio.R16_9, 320, 180), Frame(Ratio.R9_16, 180, 320), Frame(Ratio.R1_1, 240, 240))
    private val logo = "im_aaaaaaaaaaa1"
    private val backdrop = "im_aaaaaaaaaaa2"

    private fun fixture(ffmpeg: String, dir: File) {
        File(dir, "source").mkdirs(); File(dir, "artifacts").mkdirs()
        RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "testsrc2=s=320x240:r=12:d=3",
            "-f", "lavfi", "-i", "sine=f=500:sample_rate=48000:d=3", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
            "source/movie.mp4"), dir)
        // A red 40 × 20 logo and an orange backdrop, as image artifacts.
        RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "color=c=0xFF0000:s=40x20:d=1", "-frames:v", "1", "artifacts/$logo.png"), dir)
        RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "color=c=0xFF8000:s=64x64:d=1", "-frames:v", "1", "artifacts/$backdrop.png"), dir)
    }

    private fun graph(f: Frame, layers: List<Layer>, clipMs: List<Long> = listOf(1_000), crossfadeMs: Int = 0) = RenderGraph(
        clips = clipMs.mapIndexed { k, ms ->
            Clip(null, trimInMs = 500, trimOutMs = 500 + ms, source = "source/movie.mp4",
                transition = if (k > 0 && crossfadeMs > 0) Transition.CROSSFADE else Transition.CUT, transitionMs = if (k > 0) crossfadeMs else 0)
        },
        output = Output(f.ratio, f.w, f.h, 12, Quality.EXPORT, "matrix"),
        layers = layers,
    )

    private fun job(dir: File, name: String, graph: RenderGraph) = RenderJob(
        title = name, graph = graph, workDir = dir.path,
        files = mapOf(logo to "artifacts/$logo.png", backdrop to "artifacts/$backdrop.png"),
        fontsDir = "fonts", outputName = "exports/$name",
    )

    /** Renders through the production runner; checks Done, the file, its length and its video stream. */
    private fun render(ffmpeg: String, ffprobe: String, dir: File, name: String, graph: RenderGraph): File {
        val state = runBlocking { RealFfmpeg.runner(ffmpeg, ffprobe).run(job(dir, name, graph)) }
        assertIs<RenderState.Done>(state, "$name: $state")
        val mp4 = File(dir, "exports/$name.mp4")
        assertTrue(mp4.length() > 0, "$name: no output")
        val ms = RealFfmpeg.durationMs(ffprobe, mp4)
        assertTrue(abs(ms - graph.durationMs) <= 100, "$name: $ms ms, expected ${graph.durationMs}")
        val streams = RealFfmpeg.streams(ffprobe, mp4)
        assertTrue(streams.any { it == "video,${graph.output.width},${graph.output.height}" } && streams.any { it.startsWith("audio") }, "$name: $streams")
        println("MATRIX $name: Done, $ms ms, $streams")
        return mp4
    }

    /** Pixels that clearly differ (summed RGB difference > 90) between [a] and [b]. */
    private fun changedPixels(a: BufferedImage, b: BufferedImage): List<Pair<Int, Int>> = buildList {
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getRGB(x, y); val q = b.getRGB(x, y)
            if (intArrayOf(16, 8, 0).sumOf { s -> abs(((p shr s) and 0xFF) - ((q shr s) and 0xFF)) } > 90) add(x to y)
        }
    }

    private fun near(p: Triple<Int, Int, Int>, r: Int, g: Int, b: Int, tol: Int = 40) =
        abs(p.first - r) <= tol && abs(p.second - g) <= tol && abs(p.third - b) <= tol

    @Test fun everyLayerRendersWhereItBelongsAtEveryRatio() {
        val (ffmpeg, ffprobe) = RealFfmpeg.orSkip("LayerMatrixFfmpegTest") ?: return
        val dir = RealFfmpeg.tempDir("layer-matrix")
        try {
            fixture(ffmpeg, dir)
            val font = RealFfmpeg.anyFont()
            val family = font?.let { File(dir, "fonts").mkdirs(); it.copyTo(File(dir, "fonts/${it.name}")); FontInfo.parse(it.readBytes())!!.family }
            if (family == null && System.getenv("AUTOFLOW_EXPECT_FFMPEG") == "1") fail("no .ttf font for the TEXT layer")
            for (f in frames) {
                val id = f.ratio.name
                val at = 500L
                val shot = { name: String, layers: List<Layer> -> RealFfmpeg.frame(ffmpeg, render(ffmpeg, ffprobe, dir, "${id}_$name", graph(f, layers)), at, dir) }
                val base = shot("baseline", emptyList())
                // Baseline: black bars around the movie.
                assertTrue(near(RealFfmpeg.rgb(base, f.bar.first, f.bar.second), 0, 0, 0, 20), "$id baseline bar ${RealFfmpeg.rgb(base, f.bar.first, f.bar.second)}")

                // BLUR (hard, feathered) over a band across the movie: the band changes, the rows above it do not.
                val band = listOf(0f, 0.4f, 1f, 0.2f)
                val y0 = (0.4f * f.h).toInt(); val y1 = y0 + (0.2f * f.h).toInt(); val mid = (y0 + y1) / 2
                val mx0 = f.fx + 4; val mx1 = f.fx + f.fw - 4
                val hard = shot("blur_hard", listOf(Layer("b", LayerType.BLUR, rect = band, params = LayerParams(sigma = 10f))))
                val soft = shot("blur_feather", listOf(Layer("b", LayerType.BLUR, rect = band, params = LayerParams(sigma = 10f, feather = 0.03f))))
                val dHard = RealFfmpeg.diff(hard, base, mx0, mid - 2, mx1, mid + 2)
                val dAbove = RealFfmpeg.diff(hard, base, mx0, f.fy + 2, mx1, minOf(y0 - 4, f.fy + 8))
                val dSoftMid = RealFfmpeg.diff(soft, hard, mx0, mid - 2, mx1, mid + 2)
                val dSoftEdge = RealFfmpeg.diff(soft, base, mx0, y0, mx1, y0 + 1)
                val dHardEdge = RealFfmpeg.diff(hard, base, mx0, y0, mx1, y0 + 1)
                println("MATRIX $id blur: hard band Δ=$dHard above Δ=$dAbove; feather centre vs hard Δ=$dSoftMid, edge row vs plain Δ=$dSoftEdge (hard edge Δ=$dHardEdge)")
                assertTrue(dHard > 8, "$id: hard blur must change the band ($dHard)")
                assertTrue(dAbove < 3, "$id: pixels above the band must not change ($dAbove)")
                assertTrue(dSoftMid < 3, "$id: feathered centre = full blur ($dSoftMid)")
                assertTrue(dSoftEdge < dHardEdge / 2, "$id: the feathered edge shows the picture ($dSoftEdge vs hard $dHardEdge)")

                // IMAGE: the red logo scaled to the rect width (aspect kept) at the rect's corner.
                val img = shot("image", listOf(Layer("i", LayerType.IMAGE, rect = listOf(0.1f, 0.1f, 0.35f, 0.2f), params = LayerParams(image = logo))))
                val lw = (0.35f * f.w).toInt(); val lx = (0.1f * f.w).toInt(); val ly = (0.1f * f.h).toInt()
                val inLogo = RealFfmpeg.rgb(img, lx + lw / 2, ly + lw / 4)
                assertTrue(near(inLogo, 255, 0, 0, 60), "$id image: logo pixel $inLogo")
                val below = ly + lw / 2 + 6
                assertTrue(RealFfmpeg.diff(img, base, lx, below, lx + lw, below + 4) < 3, "$id image: under the logo the picture is unchanged")

                // BACKGROUND blur / image / colour: the bars show it; the movie on top is unchanged.
                for ((mode, expect) in listOf(BackgroundMode.BLUR to null, BackgroundMode.IMAGE to Triple(255, 128, 0), BackgroundMode.COLOUR to Triple(0, 255, 0))) {
                    val pic = shot("background_${mode.name.lowercase()}", listOf(Layer("bg", LayerType.BACKGROUND,
                        params = LayerParams(mode = mode, image = backdrop.takeIf { mode == BackgroundMode.IMAGE }, colour = "#00FF00"))))
                    val bar = RealFfmpeg.rgb(pic, f.bar.first, f.bar.second)
                    val movie = RealFfmpeg.diff(pic, base, f.fx + f.fw / 4, f.fy + f.fh / 4, f.fx + 3 * f.fw / 4, f.fy + 3 * f.fh / 4)
                    println("MATRIX $id background ${mode.name}: bar $bar, movie Δ=$movie")
                    if (expect == null) assertTrue(bar.first + bar.second + bar.third > 90, "$id background blur: bar still black $bar")
                    else assertTrue(near(bar, expect.first, expect.second, expect.third, 45), "$id background ${mode.name}: bar $bar, expected $expect")
                    assertTrue(movie < 3, "$id background ${mode.name}: the movie must stay on top ($movie)")
                }

                // TEXT on a small top-left rect: drawn inside it (centred on it), not at the frame's middle (the old x).
                if (family != null) {
                    val rx0 = (0.04f * f.w).toInt(); val ry0 = (0.04f * f.h).toInt(); val rx1 = (0.44f * f.w).toInt(); val ry1 = (0.2f * f.h).toInt()
                    val text = shot("text", listOf(Layer("t", LayerType.TEXT, rect = listOf(0.04f, 0.04f, 0.4f, 0.16f), params = LayerParams(textMy = "AUDIT", style = family))))
                    val changed = changedPixels(text, base)
                    val cx = changed.map { it.first }.average(); val cy = changed.map { it.second }.average()
                    val outside = changed.count { (x, y) -> x !in (rx0 - 3)..(rx1 + 3) || y !in (ry0 - 3)..(ry1 + 3) }
                    println("MATRIX $id text: ${changed.size} px changed, centre (${"%.1f".format(cx)}, ${"%.1f".format(cy)}) for rect centre (${(rx0 + rx1) / 2}, ${(ry0 + ry1) / 2}), $outside outside the rect")
                    assertTrue(changed.size > 40, "$id text: nothing drawn (${changed.size} px)")
                    assertTrue(abs(cx - (rx0 + rx1) / 2.0) < (rx1 - rx0) / 6.0 && abs(cy - (ry0 + ry1) / 2.0) < (ry1 - ry0) / 4.0, "$id text: centred at ($cx, $cy), not on the rect")
                    assertTrue(outside <= changed.size / 50, "$id text: $outside of ${changed.size} changed pixels outside the rect")
                }

                // BORDER: magenta on all four edges, the middle unchanged.
                val border = shot("border", listOf(Layer("o", LayerType.BORDER, params = LayerParams(px = 8, colour = "#FF00FF"))))
                for ((x, y) in listOf(3 to f.h / 2, f.w - 4 to f.h / 2, f.w / 2 to 3, f.w / 2 to f.h - 4)) {
                    assertTrue(near(RealFfmpeg.rgb(border, x, y, 4), 255, 0, 255, 50), "$id border at ($x, $y): ${RealFfmpeg.rgb(border, x, y, 4)}")
                }
                assertTrue(RealFfmpeg.diff(border, base, f.w / 3, f.h / 3, 2 * f.w / 3, 2 * f.h / 3) < 3, "$id border: the middle changed")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun backgroundForPartOfTheStripShowsOnlyThen() {
        val (ffmpeg, ffprobe) = RealFfmpeg.orSkip("LayerMatrixFfmpegTest") ?: return
        val dir = RealFfmpeg.tempDir("layer-window")
        try {
            fixture(ffmpeg, dir)
            val f = frames[0]
            val green = Layer("bg", LayerType.BACKGROUND, fromMs = 1_500, params = LayerParams(mode = BackgroundMode.COLOUR, colour = "#00FF00"))
            val mp4 = render(ffmpeg, ffprobe, dir, "window", graph(f, listOf(green), clipMs = listOf(1_000, 1_000)))
            val bar = { ms: Long -> RealFfmpeg.rgb(RealFfmpeg.frame(ffmpeg, mp4, ms, dir), f.bar.first, f.bar.second) }
            assertTrue(near(bar(500), 0, 0, 0, 20), "clip 1: bars ${bar(500)}")
            assertTrue(near(bar(1_250), 0, 0, 0, 20), "clip 2 before 1.5 s: bars ${bar(1_250)}")
            assertTrue(near(bar(1_750), 0, 255, 0, 45), "clip 2 after 1.5 s: bars ${bar(1_750)}")
            // With a crossfade (xfade needs a constant frame rate out of the overlay) the strip still renders whole.
            render(ffmpeg, ffprobe, dir, "window_xfade", graph(f, listOf(green), clipMs = listOf(1_000, 1_000), crossfadeMs = 250))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * A clip over a BACKGROUND image ends at its own length. The still (1/24) and the movie (1/12288) used to reach
     * overlay in different time bases; when the movie's end reached overlay last, FFmpeg 8 / 9 handed that end time on
     * unconverted (1.5 s read as 768 s), tpad / fps padded the clip to it and the next clips fell past the export's
     * end: a frozen picture (the NativeOutputParityTest flake). Which end arrives last depends on thread timing; this
     * 24 fps movie with a 1.5 s clip hit it 10 / 10 times with FFmpeg 8.0.1 and about half the time with 8.1 / 9.0,
     * so the clip is rendered five times. Only clip 0's picture is counted, frame by frame, without the export's -t
     * that hides extra frames. FFmpeg 7 never had the fault, so there this passes either way.
     */
    @Test fun imageBackgroundClipEndsOnTime() {
        val (ffmpeg, _) = RealFfmpeg.orSkip("LayerMatrixFfmpegTest") ?: return
        val dir = RealFfmpeg.tempDir("layer-bg-end")
        try {
            fixture(ffmpeg, dir)
            RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "testsrc2=s=320x180:r=24:d=4",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "source/movie24.mp4"), dir)
            val layer = Layer("bg", LayerType.BACKGROUND, params = LayerParams(mode = BackgroundMode.IMAGE, image = backdrop))
            val graph = RenderGraph(
                clips = listOf(Clip(null, trimInMs = 500, trimOutMs = 2_000, source = "source/movie24.mp4")),
                output = Output(Ratio.R9_16, 180, 320, 24, Quality.EXPORT, "end"),
                layers = listOf(layer),
            )
            val env = RenderCompiler.Env(
                resolve = { null }, songPath = null, assPath = "exports/end.ass", fontsDir = "fonts", outputPath = "exports/end.mp4",
                resolveSource = { RenderCompiler.ClipMedia(File(dir, it).path, hasAudio = false) },
                resolveLayerImage = { File(dir, "artifacts/$it.png").path },
            )
            val r = assertIs<RenderCompiler.Compiled.Ok>(RenderCompiler.compile(graph, env))
            val at = r.args.indexOf("-filter_complex")
            val chains = r.args[at + 1].split(';')
            val clip0 = chains.subList(0, chains.indexOfFirst { it.endsWith("[v0]") } + 1).joinToString(";")
            val crc = File(dir, "clip0.crc")
            val counts = (1..5).map {
                RealFfmpeg.ok(listOf(ffmpeg) + r.args.subList(0, at) + listOf("-filter_complex", clip0, "-map", "[v0]", "-f", "framecrc", crc.path), dir)
                crc.readLines().count { it.isNotBlank() && !it.startsWith("#") }
            }
            println("MATRIX background image clip 0 (1.5 s at 24 fps), frames per run: $counts")
            assertEquals(List(5) { 36 }, counts, "more than 36 frames = overlay's end time was read in the wrong time base")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun realRenderJobMustResolveImageArtifactFromFilesMap() {
        val (ffmpeg, ffprobe) = RealFfmpeg.orSkip("LayerMatrixFfmpegTest") ?: return
        val dir = RealFfmpeg.tempDir("layer-image-id")
        try {
            fixture(ffmpeg, dir)
            val f = frames[0]
            val layer = Layer("logo", LayerType.IMAGE, rect = listOf(0.1f, 0.1f, 0.35f, 0.2f), params = LayerParams(image = logo))
            // The id resolves through job.files to artifacts/<id>.png and the logo is in the picture.
            val pic = RealFfmpeg.frame(ffmpeg, render(ffmpeg, ffprobe, dir, "logo", graph(f, listOf(layer))), 500, dir)
            val lw = (0.35f * f.w).toInt()
            assertTrue(near(RealFfmpeg.rgb(pic, (0.1f * f.w).toInt() + lw / 2, (0.1f * f.h).toInt() + lw / 4), 255, 0, 0, 60))
            // An id that is not in the files map (and no such file) fails the job: no mp4, a reason naming the image.
            val missing = layer.copy(params = LayerParams(image = "im_bbbbbbbbbbbb"))
            val state = runBlocking { RealFfmpeg.runner(ffmpeg, ffprobe).run(job(dir, "missing", graph(f, listOf(missing)))) }
            val failed = assertIs<RenderState.Failed>(state)
            assertEquals("layer logo: the image im_bbbbbbbbbbbb is missing", failed.reason)
            assertTrue(!File(dir, "exports/missing.mp4").exists() && !File(dir, "exports/missing.part.mp4").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
