package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.tool.FakeToolRunner
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MediaTaskJobTest {
    private val fs = InMemoryFileSystem().apply { createDirectories("/p") }
    private val task = MediaTaskJob(MediaTaskJob.SUBTITLE_BURN, listOf("-i", "source/movie.mp4", "-c:v", "libx264", MediaTaskJob.PART), "exports/clip-burned.mp4", 8_000)

    /** ffmpeg writes the file it is given as the last argument (exit 0), or fails. */
    private fun tools(exit: Int = 0) = FakeToolRunner(listOf("out_time_us=4000000", "progress=continue")) { cmd ->
        if (exit == 0) fs.writeDurably(cmd.last(), "mp4".encodeToByteArray())
        ToolExec.Done(exit, "", emptyList(), 500, false)
    }

    private fun runner(t: FakeToolRunner) = RenderJobRunner(fs, FfmpegRunner(t, "ffmpeg"), MediaProbe(t, "ffprobe"))

    @Test
    fun runsIntoThePartFileThenRenamesWithProgress() = runTest {
        val t = tools()
        val progress = mutableListOf<Double>()
        val done = runner(t).run(task.toRenderJob("Burn", "/p")) { progress += it.fraction }
        assertEquals(RenderState.Done("/p/exports/clip-burned.mp4", 500, 8_000, null), done)
        assertEquals(listOf("ffmpeg", "-progress", "pipe:1", "-nostats", "-benchmark", "-i", "source/movie.mp4", "-c:v", "libx264",
            "/p/exports/clip-burned.part.mp4"), t.commands.single())
        assertEquals(listOf(0.5), progress)
        assertFalse(fs.exists("/p/exports/clip-burned.part.mp4"))
        assertEquals("/p/exports/clip-burned.part.mp4", RenderJobRunner.partPath(task.toRenderJob("Burn", "/p")))

        val again = tools()
        assertIs<RenderState.Done>(runner(again).run(task.toRenderJob("Burn", "/p")))
        assertTrue(again.commands.isEmpty(), "an output already there is the result; nothing is overwritten")
    }

    @Test
    fun aFailureLeavesNoOutput() = runTest {
        val failed = runner(tools(exit = 1)).run(task.toRenderJob("Burn", "/p"))
        assertIs<RenderState.Failed>(failed)
        assertFalse(fs.exists("/p/exports/clip-burned.mp4"))
    }

    @Test
    fun partPathsAndJobFiles() {
        assertEquals("/a/out.part.m4a", MediaTaskJob("x", emptyList(), "/a/out.m4a", 1).partPath("/p"))
        assertEquals("/p/dir.v2/out.part", MediaTaskJob("x", emptyList(), "dir.v2/out", 1).partPath("/p"))
        val job = task.toRenderJob("Burn", "/p")
        assertEquals(job, CoreJson.decodeFromString(RenderJob.serializer(), CoreJson.encodeToString(RenderJob.serializer(), job)))
        val graphJob = job.copy(mediaTask = null)
        assertFalse("mediaTask" in CoreJson.encodeToString(RenderJob.serializer(), graphJob), "graph jobs are written as before")
    }
}
