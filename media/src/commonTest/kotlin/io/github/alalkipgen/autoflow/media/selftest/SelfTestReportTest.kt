package io.github.alalkipgen.autoflow.media.selftest

import io.github.alalkipgen.autoflow.media.FfmpegCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelfTestReportTest {
    private val caps = FfmpegCapabilities("9.0.2", "--enable-libass", setOf("ass", "xfade"), setOf("libx264", "aac"))
    private val ok = RenderOutcome.Ok(1_572_864, "h264 540x960 + aac, 16.0 s", "/x/r1.png")
    private val failed = RenderOutcome.Failed("exit 1", 1, "ffmpeg -i a.mp4 out.mp4", "line 1\n[Parsed_ass_0] fontselect: failed\n")

    private fun ffmpeg(vararg outcomes: RenderOutcome) =
        outcomes.mapIndexed { i, o -> RenderResult("r${i + 1}", "Render ${i + 1}", Engine.FFMPEG, 12_345, 180_000, "maxrss", o) }

    private fun report(renders: List<RenderResult>, execError: String? = null) = SelfTestReport(
        startedAt = "2026-11-11 09:30",
        totalMs = 75_000,
        sections = listOf(SelfTestReport.Section("Device", listOf("Model" to "Pixel 7", "Android" to "15 (SDK 35)"))),
        capabilities = if (execError == null) caps else null,
        execError = execError,
        renders = renders,
        notes = listOf("Screen was off for part of the run"),
    )

    @Test
    fun verdicts() {
        assertEquals(Verdict.FFMPEG_OK, report(ffmpeg(ok, ok, ok)).verdict())
        assertEquals(Verdict.FFMPEG_PARTIAL, report(ffmpeg(ok, failed, ok)).verdict())
        assertEquals(Verdict.FAIL, report(ffmpeg(failed, failed, failed)).verdict())
        assertEquals(Verdict.INCOMPLETE, report(ffmpeg(ok, RenderOutcome.Cancelled)).verdict())
        assertEquals(Verdict.INCOMPLETE, report(emptyList()).verdict())
        val media3 = RenderResult("m1", "Media3", Engine.MEDIA3, 9_000, null, null, ok)
        assertEquals(Verdict.MEDIA3_ONLY, report(listOf(media3), execError = "error=13, Permission denied").verdict())
        // Media3 alone (run by hand) is not an ffmpeg pass.
        assertEquals(Verdict.MEDIA3_ONLY, report(listOf(media3)).verdict())
    }

    @Test
    fun markdownHasEverySectionAndATableRowPerRender() {
        val md = report(ffmpeg(ok, failed, ok)).toMarkdown()
        assertTrue(md.startsWith("# Auto Flow self-test: PARTIAL"), md)
        assertTrue("## Device\n- Model: Pixel 7\n- Android: 15 (SDK 35)\n" in md, md)
        assertTrue("- ass (libass): yes, xfade: yes, libx264: yes, aac: yes → overlay backend LIBASS" in md, md)
        assertTrue("| Render 1 | ffmpeg | ok | 12.3 s | 175 MB (maxrss) | 1.5 MB, h264 540x960 + aac, 16.0 s |" in md, md)
        assertEquals(3, md.lines().count { it.matches(Regex("""\| Render \d .*""")) })
        assertTrue("Started 2026-11-11 09:30, took 75.0 s." in md)
        assertTrue("- Screen was off for part of the run" in md)
    }

    @Test
    fun failuresCarryTheCommandAndLogTail() {
        val md = report(ffmpeg(ok, failed, ok)).toMarkdown()
        assertTrue("### r2 (ffmpeg) failed\n- reason: exit 1\n- exit code: 1\n- command: `ffmpeg -i a.mp4 out.mp4`\n```\nline 1\n[Parsed_ass_0] fontselect: failed\n```" in md, md)
        assertFalse("### r1" in md)
    }

    @Test
    fun execFailureIsReportedWithoutCapabilities() {
        val md = report(emptyList(), execError = "Cannot run program: error=13, Permission denied").toMarkdown()
        assertTrue("- exec FAILED: Cannot run program: error=13, Permission denied\n- not available" in md, md)
        assertTrue("## Renders\n- none" in md)
    }

    @Test
    fun formatting() {
        assertEquals("0.0 s", SelfTestReport.seconds(0))
        assertEquals("61.9 s", SelfTestReport.seconds(61_999))
        assertEquals("25.0 MB", SelfTestReport.mb(25L * 1024 * 1024))
    }
}
