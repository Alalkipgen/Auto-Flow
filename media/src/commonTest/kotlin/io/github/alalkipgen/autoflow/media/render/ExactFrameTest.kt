package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.Layer
import io.github.alalkipgen.autoflow.core.recap.LayerType
import io.github.alalkipgen.autoflow.core.recap.MaskPreset
import io.github.alalkipgen.autoflow.core.recap.RecapMask
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.tool.FakeToolRunner
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** "Exact frame" (D-42, 5A-8): the export graph cut to one clip, then one still with `-ss … -frames:v 1`. */
class ExactFrameTest {
    private val out = RenderPresets.preview(Ratio.R9_16)
    private val movie = "source/movie.mp4"
    private val graph = RenderGraph(
        clips = listOf(
            Clip(null, trimInMs = 10_000, trimOutMs = 13_000, source = movie),
            Clip(null, trimInMs = 40_000, trimOutMs = 44_000, source = movie),
            Clip(null, trimInMs = 70_000, trimOutMs = 72_000, source = movie),
        ),
        overlays = listOf(
            Overlay("one", 500, 2_500, "Noto Sans Myanmar", 40),
            Overlay("two", 3_400, 6_800, "Noto Sans Myanmar", 40),
            Overlay("three", 7_200, 8_800, "Noto Sans Myanmar", 40),
        ),
        output = out,
        voice = VoiceTrack(listOf(VoiceCue("artifacts/t1.wav", 200), VoiceCue("artifacts/t2.wav", 3_200)), clipsVolume = 0.2),
        layers = listOf(Layer("w", LayerType.BORDER, fromMs = 0, toMs = null), Layer("late", LayerType.BORDER, fromMs = 8_000, toMs = 9_000)),
        masks = listOf(RecapMask(MaskPreset.BOTTOM, MaskPreset.BOTTOM.rect)),
    )

    @Test
    fun windowKeepsOnlyTheClipUnderTheTimeInItsOwnTime() {
        val w = ExactFrame.window(graph, 4_000) // clip 2 runs 3 000 – 7 000 in the output
        assertEquals(1, w.clipIndex)
        assertEquals(1_000, w.seekMs)
        assertEquals(listOf(graph.clips[1]), w.graph.clips)
        assertEquals(listOf(Overlay("two", 400, 3_800, "Noto Sans Myanmar", 40)), w.graph.overlays)
        assertEquals(listOf(Layer("w", LayerType.BORDER, fromMs = 0, toMs = null)), w.graph.layers, "the 8–9 s layer is not in this clip")
        assertEquals(graph.masks, w.graph.masks, "blur boxes, looks and background are per clip and stay")
        assertNull(w.graph.audio)
        assertEquals(VoiceTrack(emptyList(), clipsVolume = 0.0), w.graph.voice, "no sound is decoded for a still")
    }

    @Test
    fun windowAtTheEdges() {
        assertEquals(0, ExactFrame.window(graph, 0).clipIndex)
        assertEquals(1, ExactFrame.window(graph, 3_000).clipIndex, "a cut belongs to the next clip")
        val end = ExactFrame.window(graph, 60_000)
        assertEquals(2, end.clipIndex)
        assertEquals(1_999, end.seekMs, "past the end → the last frame")
        assertEquals(listOf(Overlay("three", 200, 1_800, "Noto Sans Myanmar", 40)), end.graph.overlays)
        assertEquals(listOf(Layer("late", LayerType.BORDER, fromMs = 1_000, toMs = 2_000)), end.graph.layers.drop(1))
    }

    @Test
    fun crossfadesKeepTheWholeGraph() {
        val faded = graph.copy(clips = graph.clips.mapIndexed { i, c -> if (i == 1) c.copy(transition = Transition.CROSSFADE, transitionMs = 500) else c })
        val w = ExactFrame.window(faded, 4_000)
        assertNull(w.clipIndex)
        assertEquals(4_000, w.seekMs)
        assertEquals(faded.clips, w.graph.clips)
        assertEquals(faded.overlays, w.graph.overlays)
    }

    @Test
    fun stillArgsAreTheExportGraphWithOneFrameOut() {
        val w = ExactFrame.window(graph, 4_000)
        val env = RenderCompiler.Env(
            resolve = { null }, songPath = null, assPath = "/p/.cache/frames/4000.png.ass", fontsDir = "/p/fonts",
            outputPath = "/p/.cache/frames/4000.part.png",
            resolveSource = { RenderCompiler.ClipMedia("/p/$it", hasAudio = true) },
        )
        val compiled = assertIs<RenderCompiler.Compiled.Ok>(RenderCompiler.compile(w.graph, env))
        val args = ExactFrame.stillArgs(compiled.args, w.seekMs, "/p/.cache/frames/4000.part.png")
        assertEquals(listOf("-ss", "40.000", "-t", "4.000", "-i", "/p/source/movie.mp4"),
            args.subList(args.indexOf("-threads") + 2, args.indexOf("-filter_complex")), "one movie range, input-side seek")
        val fc = args[args.indexOf("-filter_complex") + 1]
        assertTrue("gblur" in fc && "ass=filename=" in fc, "same blur boxes and captions as the export: $fc")
        assertTrue(fc.endsWith(";[aout]anullsink"), fc)
        assertEquals(listOf("-map", "[vout]", "-an", "-ss", "1.000", "-frames:v", "1", "-update", "1", "/p/.cache/frames/4000.part.png"),
            args.takeLast(10))
        assertFalse("libx264" in args || "[aout]" in args || "-movflags" in args, "$args")
        val jpg = ExactFrame.stillArgs(compiled.args, 0, "/p/exports/export.thumb.jpg")
        assertEquals(listOf("-q:v", "2", "/p/exports/export.thumb.jpg"), jpg.takeLast(3))
    }

    @Test
    fun runnerWritesThePartImageThenRenames() = runTest {
        val fs = InMemoryFileSystem().apply {
            createDirectories("/p/source"); writeDurably("/p/source/movie.mp4", "mp4".encodeToByteArray())
        }
        val tools = FakeToolRunner(listOf("bench: maxrss=1000KiB")) { cmd ->
            fs.writeDurably(cmd.last(), "png".encodeToByteArray())
            ToolExec.Done(0, "", emptyList(), 120, false)
        }
        val job = RenderJob(title = "R", graph = graph, workDir = "/p", files = emptyMap(), fontsDir = "fonts", outputName = "exports/export")
        val r = RenderJobRunner(fs, FfmpegRunner(tools, "ffmpeg"), MediaProbe(tools, "ffprobe")).frame(job, 4_000, ".cache/frames/4000.png")
        assertEquals("/p/.cache/frames/4000.png", assertIs<RenderState.Done>(r).output)
        val cmd = tools.commands.single()
        assertEquals("ffmpeg", cmd[0], "no ffprobe: a still needs no sound")
        assertEquals("/p/.cache/frames/4000.part.png", cmd.last())
        assertTrue(fs.exists("/p/.cache/frames/4000.png.ass"), "captions file written for libass")
        assertFalse(fs.exists("/p/.cache/frames/4000.part.png"))
        assertFalse(fs.exists("/p/exports/export.mp4"), "the export is not touched")
    }
}
