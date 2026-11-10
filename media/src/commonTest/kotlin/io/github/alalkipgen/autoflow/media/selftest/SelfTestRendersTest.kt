package io.github.alalkipgen.autoflow.media.selftest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelfTestRendersTest {
    private val inputs = SelfTestRenders.Inputs("/in/clip1.mp4", "/in/clip2.mp4", "/in/song.mp3", "/in/lyrics.ass", "/in/fonts", "/out")
    private val renders = SelfTestRenders.all(inputs)

    private fun SelfTestRenders.Render.graph() = args[args.indexOf("-filter_complex") + 1]
    private fun SelfTestRenders.Render.inputs() = args.withIndex().filter { it.value == "-i" }.map { args[it.index + 1] }

    @Test
    fun threeRendersWithStableIdsAndOutputs() {
        assertEquals(listOf("r1-concat-540p", "r2-ass-540p", "r3-export-1080p"), renders.map { it.id })
        assertEquals(SelfTestRenders.COUNT, renders.size)
        renders.forEach { assertEquals("/out/${it.id}.mp4", it.output); assertEquals(it.output, it.args.last()) }
        assertEquals(listOf(540 to 960, 540 to 960, 1080 to 1920), renders.map { it.width to it.height })
    }

    @Test
    fun everyRenderBenchmarksAndNeverReadsStdin() {
        renders.forEach { r ->
            assertTrue("-benchmark" in r.args, r.id)
            assertTrue("-nostdin" in r.args, r.id)
            assertTrue(r.args.containsAll(listOf("-map", "[v]", "[a]", "-shortest")), r.id)
        }
    }

    @Test
    fun onlyTheOverlayRendersUseLibassWithTheFontsDir() {
        val (r1, r2, r3) = renders
        assertFalse("ass=" in r1.graph())
        listOf(r2, r3).forEach { assertTrue("[vc]ass=filename='/in/lyrics.ass':fontsdir='/in/fonts'[v]" in it.graph(), it.id) }
    }

    @Test
    fun exportMixesTheSongAndUsesTheExportPreset() {
        val (r1, _, r3) = renders
        assertEquals(listOf("/in/clip1.mp4", "/in/clip2.mp4"), r1.inputs())
        assertEquals(listOf("/in/clip1.mp4", "/in/clip2.mp4", "/in/song.mp3"), r3.inputs())
        assertTrue("amix=inputs=2:duration=first:normalize=0[a]" in r3.graph())
        assertTrue(r3.args.containsAll(listOf("slow", "18", "192k")))
        assertTrue(r1.args.containsAll(listOf("ultrafast", "23", "128k")))
        assertTrue("scale=1080:1920:force_original_aspect_ratio=decrease" in r3.graph())
    }

    @Test
    fun frameAndProbeArgs() {
        assertEquals(listOf("-hide_banner", "-y", "-ss", "4", "-i", "/out/a.mp4", "-frames:v", "1", "/out/a.png"), SelfTestRenders.frameArgs("/out/a.mp4", "/out/a.png"))
        assertEquals("/out/a.mp4", SelfTestRenders.probeArgs("/out/a.mp4").last())
    }

    @Test
    fun summarizesFfprobeOutput() {
        val out = "codec_name=h264|codec_type=video|width=540|height=960\ncodec_name=aac|codec_type=audio\nduration=16.021333|size=401838\n"
        assertEquals("h264 540x960 + aac, 16.0 s", SelfTestRenders.summarizeProbe(out))
        assertEquals("weird", SelfTestRenders.summarizeProbe(" weird "))
    }
}
