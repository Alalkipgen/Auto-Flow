package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.tool.FakeToolRunner
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class RenderJobRunnerTest {
    private val a = ArtifactId("vd_aaaaaaaaaaaa")
    private val b = ArtifactId("vd_bbbbbbbbbbbb")
    private val fs = InMemoryFileSystem().apply {
        createDirectories("/p/fonts")
        writeDurably("/p/fonts/NotoSansMyanmar-Regular.ttf", Sfnt.font(1000, 1520, 664, listOf(Sfnt.Name(3, 1, "Noto Sans Myanmar"))))
        writeDurably("/p/fonts/readme.txt", "not a font".encodeToByteArray())
        writeDurably("/p/fonts/broken.ttf", ByteArray(10))
    }
    private val probed = mutableListOf<String>()

    /** ffprobe: clip a has sound, b not; ffmpeg: writes the output file it was given (the last argument). */
    private fun tools(ffmpegExit: Int = 0) = FakeToolRunner(
        listOf("out_time_us=4000000", "progress=continue") + if (ffmpegExit == 0) listOf("bench: maxrss=1000KiB") else listOf("Error while filtering"),
    ) { cmd ->
        if (cmd[0] == "ffprobe") {
            probed += cmd.last()
            val audio = if ("aaaa" in cmd.last()) """,{"codec_type":"audio","codec_name":"aac"}""" else ""
            ToolExec.Done(0, """{"streams":[{"codec_type":"video","codec_name":"h264","width":1080,"height":1920}$audio],"format":{"duration":"8"}}""", emptyList(), 1, false)
        } else {
            if (ffmpegExit == 0) fs.writeDurably(cmd.last(), "mp4".encodeToByteArray())
            ToolExec.Done(ffmpegExit, "", emptyList(), 900, false)
        }
    }

    private fun runner(t: FakeToolRunner) = RenderJobRunner(fs, FfmpegRunner(t, "ffmpeg"), MediaProbe(t, "ffprobe"))

    private fun job(audio: AudioTrack?, overlays: List<Overlay> = emptyList()) = RenderJob(
        title = "MV",
        graph = RenderGraph(
            clips = listOf(Clip(a, trimOutMs = 8_000), Clip(b, trimOutMs = 8_000)),
            audio = audio,
            overlays = overlays,
            output = RenderPresets.export(Ratio.R9_16),
        ),
        workDir = "/p",
        files = mapOf(a.value to "artifacts/vd_aaaaaaaaaaaa.mp4", b.value to "/p/artifacts/vd_bbbbbbbbbbbb.mp4"),
        songPath = "audio/song.mp3",
        fontsDir = "fonts",
        outputName = "exports/mv-tiktok1080",
    )

    @Test
    fun rendersIntoAPartFileThenRenames() = runTest {
        val t = tools()
        val progress = mutableListOf<Double>()
        val lyric = Overlay("ည", 0, 1_000, "Noto Sans Myanmar", 64)
        val r = runner(t).run(job(AudioTrack("audio/song.mp3"), listOf(lyric))) { progress += it.fraction }
        assertEquals(RenderState.Done("/p/exports/mv-tiktok1080.mp4", 900, 16_000, 1000), r)
        assertEquals(listOf(0.25), progress)
        assertEquals(emptyList(), probed) // muted clips: no probe needed
        val ffmpeg = t.commands.single()
        assertEquals("/p/exports/mv-tiktok1080.part.mp4", ffmpeg.last())
        check("/p/artifacts/vd_aaaaaaaaaaaa.mp4" in ffmpeg && "/p/audio/song.mp3" in ffmpeg) { ffmpeg }
        check("ass=filename=/p/exports/mv-tiktok1080.ass:fontsdir=/p/fonts[vout]" in ffmpeg[ffmpeg.indexOf("-filter_complex") + 1])
        val ass = fs.read("/p/exports/mv-tiktok1080.ass").decodeToString()
        check("Style: s1,Noto Sans Myanmar,140," in ass) { ass } // size factor read from the font file
        val saved = CoreJson.decodeFromString(RenderGraph.serializer(), fs.read("/p/exports/mv-tiktok1080.rendergraph.json").decodeToString())
        assertEquals(job(null).graph.clips, saved.clips)
        assertEquals(false, fs.exists("/p/exports/mv-tiktok1080.part.mp4"))
        assertEquals("mp4", fs.read("/p/exports/mv-tiktok1080.mp4").decodeToString())
    }

    @Test
    fun clipSoundIsProbedOncePerVideo() = runTest {
        val t = tools()
        val r = runner(t).run(job(null).let { it.copy(graph = it.graph.copy(clips = it.graph.clips + Clip(a, trimOutMs = 2_000))) })
        assertIs<RenderState.Done>(r)
        assertEquals(listOf("/p/artifacts/vd_aaaaaaaaaaaa.mp4", "/p/artifacts/vd_bbbbbbbbbbbb.mp4"), probed)
        val graph = t.commands.last().let { it[it.indexOf("-filter_complex") + 1] }
        check("[0:a]aformat" in graph && "anullsrc" in t.commands.last().joinToString(" ")) { graph }
    }

    @Test
    fun failedRenderKeepsThePreviousExport() = runTest {
        fs.createDirectories("/p/exports")
        fs.writeDurably("/p/exports/mv-tiktok1080.mp4", "good".encodeToByteArray())
        val r = assertIs<RenderState.Failed>(runner(tools(ffmpegExit = 1)).run(job(AudioTrack("audio/song.mp3"))))
        assertEquals("ffmpeg failed (exit 1)", r.reason)
        assertEquals(listOf("Error while filtering"), r.tail)
        assertEquals("good", fs.read("/p/exports/mv-tiktok1080.mp4").decodeToString())
    }

    @Test
    fun invalidGraphsAndUnreadableClipsFailBeforeFfmpeg() = runTest {
        val t = tools()
        val missing = job(AudioTrack("audio/song.mp3")).copy(files = emptyMap())
        assertEquals(RenderState.Failed("clip 1: the video vd_aaaaaaaaaaaa is missing"), runner(t).run(missing))
        val noProbe = FakeToolRunner { ToolExec.CannotStart("EACCES") }
        val r = assertIs<RenderState.Failed>(runner(noProbe).run(job(null)))
        assertEquals("cannot read the video vd_aaaaaaaaaaaa: ffprobe cannot start: EACCES", r.reason)
        assertEquals(emptyList(), t.commands)
        assertEquals(false, fs.exists("/p/exports"))
    }

    @Test
    fun batteryGuardWarnsOnlyWhenLowAndUnplugged() {
        assertEquals(true, BatteryGuard.shouldWarn(19, charging = false))
        assertEquals(false, BatteryGuard.shouldWarn(19, charging = true))
        assertEquals(false, BatteryGuard.shouldWarn(20, charging = false))
        assertEquals(false, BatteryGuard.shouldWarn(null, charging = false))
        assertEquals(true, RenderState.Cancelled.finished && !RenderState.Queued.finished)
    }
}
