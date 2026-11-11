package io.github.alalkipgen.autoflow.ui.media

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.media.render.Clip
import io.github.alalkipgen.autoflow.media.render.RenderGraph
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderPresets
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Desktop Export: one render at a time in order, progress, cancel queued / running, no ffmpeg → a clear failure. */
class InProcessRenderQueueTest {
    private val fs = InMemoryFileSystem().apply { createDirectories("/p/artifacts") }
    private val settings = SettingsStore(fs, "/a/settings.json")
    private val a = ArtifactId("vd_aaaaaaaaaaaa")
    private val ffmpegCalls = mutableListOf<String>()
    private var gate: CompletableDeferred<Unit>? = null

    private class Locator(val ffmpeg: String?) : ToolLocator {
        override fun findFfmpeg(): String? = ffmpeg
        override fun findVlc(): String? = null
    }

    /** ffprobe: a silent 8 s video; ffmpeg: reports half way, waits for [gate] when set, writes its output file. */
    private val runner = ToolRunner { cmd, _, _, _, onLine ->
        if (cmd[0].endsWith("ffprobe")) {
            ToolExec.Done(0, """{"streams":[{"codec_type":"video","codec_name":"h264","width":1080,"height":1920}],"format":{"duration":"8"}}""", emptyList(), 1, false)
        } else {
            ffmpegCalls += cmd.last()
            onLine("out_time_us=4000000")
            onLine("progress=continue")
            gate?.await()
            fs.writeDurably(cmd.last(), "mp4".encodeToByteArray())
            ToolExec.Done(0, "", emptyList(), 900, false)
        }
    }

    private fun job(name: String) = RenderJob(
        title = name,
        graph = RenderGraph(clips = listOf(Clip(a, trimOutMs = 8_000)), output = RenderPresets.preview(Ratio.R9_16)),
        workDir = "/p",
        files = mapOf(a.value to "artifacts/vd_aaaaaaaaaaaa.mp4"),
        songPath = null,
        fontsDir = "fonts",
        outputName = "exports/$name",
    )

    @Test
    fun rendersOneAtATimeInOrder() = runTest {
        val q = InProcessRenderQueue(fs, MediaTools(settings, Locator("/bin/ffmpeg"), runner), backgroundScope)
        gate = CompletableDeferred()
        val first = q.enqueue(job("preview"))
        val second = q.enqueue(job("export"))
        runCurrent()
        assertIs<RenderState.Running>(q.observe(first).first())
        assertEquals(RenderState.Queued, q.observe(second).first(), "the second waits for the first")
        assertEquals(1, ffmpegCalls.size)

        gate!!.complete(Unit)
        runCurrent()
        val done = assertIs<RenderState.Done>(q.observe(first).first())
        assertEquals("/p/exports/preview.mp4", done.output)
        assertIs<RenderState.Done>(q.observe(second).first())
        assertEquals(listOf("/p/exports/preview.part.mp4", "/p/exports/export.part.mp4"), ffmpegCalls)
        assertEquals("mp4", fs.read("/p/exports/export.mp4").decodeToString())
    }

    @Test
    fun cancelSkipsAQueuedJobAndStopsARunningOne() = runTest {
        val q = InProcessRenderQueue(fs, MediaTools(settings, Locator("/bin/ffmpeg"), runner), backgroundScope)
        gate = CompletableDeferred()
        val running = q.enqueue(job("one"))
        val queued = q.enqueue(job("two"))
        val third = q.enqueue(job("three"))
        runCurrent()
        q.cancel(queued)
        assertEquals(RenderState.Cancelled, q.observe(queued).first())
        q.cancel(running)
        runCurrent()
        assertEquals(RenderState.Cancelled, q.observe(running).first())
        assertEquals(false, fs.exists("/p/exports/one.mp4"), "a stopped render leaves no mp4")

        gate!!.complete(Unit)
        runCurrent()
        assertIs<RenderState.Done>(q.observe(third).first(), "the job behind keeps its place")
        assertEquals(listOf("/p/exports/one.part.mp4", "/p/exports/three.part.mp4"), ffmpegCalls)
    }

    @Test
    fun withoutFfmpegTheRenderSaysWhereToSetIt() = runTest {
        val q = InProcessRenderQueue(fs, MediaTools(settings, Locator(null), runner), backgroundScope)
        val id = q.enqueue(job("x"))
        runCurrent()
        assertEquals(RenderState.Failed(InProcessRenderQueue.NO_FFMPEG), q.observe(id).first())
    }
}
