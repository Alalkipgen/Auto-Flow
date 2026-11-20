package io.github.alalkipgen.autoflow.media.audio

import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.model.CleanChoice
import io.github.alalkipgen.autoflow.media.render.RealFfmpeg
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.*

/** V-2: golden Clean up sound commands, then a real ffmpeg run — the long pause shortened and −14 LUFS ± 1. */
class CleanAudioTest {
    private val all = CleanChoice()
    private val c = ToolsConfig().clean

    @Test fun analysisAsksOnlyWhatIsChosen() {
        assertEquals(listOf("-hide_banner", "-nostdin", "-nostats", "-i", "source/song.wav", "-vn", "-af",
            "silencedetect=n=-40dB:d=0.700000,${AudioFilters.voicePolish()},${AudioFilters.loudnormMeasure()}", "-f", "null", "-"),
            CleanAudio.analyzeArgs("source/song.wav", all, c.pauseMs))
        assertEquals("silencedetect=n=-40dB:d=0.700000", CleanAudio.analyzeArgs("a.wav", CleanChoice(true, true, false), 700)!![7])
        assertNull(CleanAudio.analyzeArgs("a.wav", CleanChoice(false, true, false), 700), "noise alone needs no analysis")
    }

    @Test fun goldenCleanCommands() {
        val m = LoudnessMeasurement(-30.0, -10.0, 5.0, -40.0, 0.5)
        val kept = listOf(0.0 to 3.175, 4.825 to null)
        val chain = "${AudioFilters.voicePolish()},${AudioFilters.loudnormApply(m)},aresample=48000"
        assertEquals(listOf("-hide_banner", "-y", "-nostdin", "-i", "in.mp4", "-vn", "-filter_complex",
            "[0:a]asplit=2[k0][k1];[k0]atrim=start=0.000000:end=3.175000,asetpts=PTS-STARTPTS[c0];" +
                "[k1]atrim=start=4.825000,asetpts=PTS-STARTPTS[c1];[c0][c1]concat=n=2:v=0:a=1,$chain[out]",
            "-map", "[out]", "-c:a", "pcm_s16le", "-f", "wav", "{part}"),
            CleanAudio.args("in.mp4", all, kept, m, "wav", "160k", "{part}"))
        val preview = CleanAudio.args("in.mp4", CleanChoice(true, false, true), kept, null, "m4a", "160k", "after.m4a", limitMs = 6_000)
        assertEquals(listOf("-t", "6.000000", "-i", "in.mp4"), preview.subList(3, 7))
        val graph = preview[preview.indexOf("-filter_complex") + 1]
        assertTrue("atrim=start=4.825000,asetpts" in graph && "concat=n=2" in graph && "end=6" !in graph, graph)
        assertTrue(graph.endsWith("${AudioFilters.loudnormSinglePass()},aresample=48000[out]"), graph)
        assertEquals(listOf("-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", "-f", "mp4", "after.m4a"), preview.takeLast(9))
        val none = CleanAudio.args("a.wav", CleanChoice(true, true, false), listOf(0.0 to null), null, "wav", "160k", "o.wav")
        assertEquals("[0:a]${AudioFilters.voicePolish()},aresample=48000[out]", none[none.indexOf("-filter_complex") + 1], "nothing to cut")
        assertFailsWith<IllegalArgumentException> { CleanAudio.args("a.wav", CleanChoice(false, false, false), null, null, "wav", "160k", "o.wav") }
        assertEquals("My-talk-clean-pnl${1234L.toString(36)}.m4a", CleanAudio.fileName("/x/My talk!.mp3", all, 1234, "m4a"))
    }

    @Test fun realFfmpegShortensThePauseAndEvensTheLoudness() {
        val (ffmpeg, ffprobe) = RealFfmpeg.orSkip("CleanAudioTest") ?: return
        val dir = Files.createTempDirectory("clean-audio").toFile()
        try {
            val input = File(dir, "talk.wav")
            RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-f", "lavfi", "-i",
                "aevalsrc='if(between(t,3,5),0,0.05*sin(2*PI*440*t))':s=48000:d=8", "-c:a", "pcm_s16le", input.path))
            val (code, log) = RealFfmpeg.run(listOf(ffmpeg) + CleanAudio.analyzeArgs(input.path, all, c.pauseMs)!!)
            assertEquals(0, code, log)
            val analysis = CleanAudio.parseAnalysis(log)
            assertEquals(1, analysis.silences.size, analysis.toString())
            val loud = assertNotNull(analysis.loudness, log.takeLast(800))
            val kept = CleanAudio.kept(analysis, c.pauseMs, c.keepMs)
            assertEquals(2, kept.size, kept.toString())

            val out = File(dir, "clean.wav")
            RealFfmpeg.ok(listOf(ffmpeg) + CleanAudio.args(input.path, all, kept, loud, "wav", "160k", out.path))
            val ms = RealFfmpeg.durationMs(ffprobe, out)
            assertTrue(abs(ms - 6_350) <= 60, "8 s with a 2 s pause → about 6.35 s, was $ms ms")
            val (_, measured) = RealFfmpeg.run(listOf(ffmpeg, "-hide_banner", "-nostdin", "-i", out.path, "-af", AudioFilters.loudnormMeasure(), "-f", "null", "-"))
            val lufs = assertNotNull(AudioFilters.parseMeasurement(measured)).inputI
            assertTrue(abs(lufs - AudioFilters.DEFAULT_LUFS) <= 1.0, "−14 LUFS ± 1, was $lufs")

            val after = File(dir, "after.m4a")
            RealFfmpeg.ok(listOf(ffmpeg) + CleanAudio.args(input.path, all, kept, loud, "m4a", "160k", after.path, limitMs = 6_000))
            val afterMs = RealFfmpeg.durationMs(ffprobe, after)
            assertTrue(abs(afterMs - 4_350) <= 120, "the first 6 s with the pause shortened → about 4.35 s, was $afterMs ms")
            val before = File(dir, "before.wav")
            RealFfmpeg.ok(listOf(ffmpeg) + CleanAudio.beforeArgs(input.path, 4_000, before.path))
            assertTrue(abs(RealFfmpeg.durationMs(ffprobe, before) - 4_000) <= 30)
        } finally {
            dir.deleteRecursively()
        }
    }
}
