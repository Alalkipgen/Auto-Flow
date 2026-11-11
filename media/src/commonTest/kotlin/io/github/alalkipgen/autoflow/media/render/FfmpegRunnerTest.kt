package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.media.render.FfmpegRunner.Outcome
import io.github.alalkipgen.autoflow.media.tool.FakeToolRunner
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class FfmpegRunnerTest {
    private fun block(us: Long, speed: String, end: Boolean = false) = listOf(
        "frame=120", "fps=48.0", "bitrate= 701.6kbits/s", "out_time_us=$us", "out_time_ms=$us", "out_time=00:00:05.000000",
        "speed=$speed", "progress=${if (end) "end" else "continue"}",
    )

    @Test
    fun progressBlocksBecomeFractions() {
        val p = FfmpegProgress(durationMs = 10_000)
        val out = (block(2_500_000, " 1.5x") + block(10_200_000, "1.1x", end = true)).mapNotNull(p::feed)
        assertEquals(listOf(FfmpegProgress.Progress(2_500, 0.25, 1.5, false), FfmpegProgress.Progress(10_200, 1.0, 1.1, true)), out)
    }

    @Test
    fun statsLineAlsoCounts() {
        val p = FfmpegProgress(durationMs = 20_000)
        val r = p.feed("frame=  240 fps= 60 q=28.0 size=    1024KiB time=00:00:05.12 bitrate=1638.4kbits/s speed=2.03x    ")
        assertEquals(FfmpegProgress.Progress(5_120, 0.256, 2.03, false), r)
        assertEquals(null, p.feed("[libx264 @ 0x1] frame I:2 Avg QP:20.00"))
        assertEquals(null, p.feed("out_time_us=N/A"))
    }

    @Test
    fun renderAddsProgressFlagsAndReportsRss() = runTest {
        val tools = FakeToolRunner(block(4_000_000, "2x") + listOf("bench: utime=1.0s stime=0.1s rtime=2.0s", "bench: maxrss=226304KiB") + block(8_000_000, "2x", true))
        val seen = mutableListOf<Double>()
        val r = FfmpegRunner(tools, "/lib/libffmpeg.so").render(listOf("-i", "a.mp4", "out.mp4"), "/work", durationMs = 8_000) { seen += it.fraction }
        assertEquals(Outcome.Rendered(1_234, 226_304), r)
        assertEquals(listOf(0.5, 1.0), seen)
        assertEquals(listOf("/lib/libffmpeg.so", "-progress", "pipe:1", "-nostats", "-benchmark", "-i", "a.mp4", "out.mp4"), tools.commands.single())
        assertEquals(30 * 60_000L + 160_000, tools.timeouts.single())
    }

    @Test
    fun failureKeepsTheErrorLinesNotTheProgressKeys() = runTest {
        val lines = listOf("[ass @ 0x1] Unable to open fonts/x.ass", "Error initializing filters") + block(0, "0x", true)
        val tools = FakeToolRunner(lines) { ToolExec.Done(234, "", lines, 50, false) }
        val r = assertIs<Outcome.Failed>(FfmpegRunner(tools, "ffmpeg").render(emptyList(), "/w", 1_000))
        assertEquals(listOf("[ass @ 0x1] Unable to open fonts/x.ass", "Error initializing filters"), r.tail)
        assertEquals(234, r.exitCode)
        val refused = FfmpegRunner(FakeToolRunner { ToolExec.CannotStart("EACCES") }, "ffmpeg").render(emptyList(), "/w", 1_000)
        assertEquals(Outcome.CannotStart("EACCES"), refused)
        val slow = FfmpegRunner(FakeToolRunner { ToolExec.Done(255, "", emptyList(), 9, true) }, "ffmpeg").render(emptyList(), "/w", 1_000)
        assertEquals(true, assertIs<Outcome.Failed>(slow).timedOut)
    }
}
