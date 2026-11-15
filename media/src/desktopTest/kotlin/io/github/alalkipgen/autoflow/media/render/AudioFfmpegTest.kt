package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.media.audio.AudioFilters
import io.github.alalkipgen.autoflow.media.audio.SilenceTrim
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Audit F09 with a real ffmpeg, measured on the output (not on filter strings): two-pass loudness through the
 * production [RenderJobRunner], the background music (loop, fades, ducking under the voice) and the take silence
 * trim ([SilenceTrim]). The numbers are printed (`AUDIO …`) so a run records them.
 */
class AudioFfmpegTest {
    /** Real processes, every command and its output kept. */
    private class Recorder : ToolRunner {
        val calls = mutableListOf<Pair<List<String>, String>>()
        override suspend fun run(command: List<String>, workDir: String, timeoutMs: Long, keepOutput: Boolean, onLine: (String) -> Unit): ToolExec {
            val log = StringBuilder()
            val r = ProcessTools().run(command, workDir, timeoutMs, keepOutput) { log.appendLine(it); onLine(it) }
            calls += command to log.toString()
            return r
        }
    }

    private fun graph(voice: VoiceTrack, ms: Long = 10_000) = RenderGraph(
        clips = listOf(Clip(null, trimOutMs = ms, source = "source/movie.mp4")),
        output = Output(Ratio.R16_9, 320, 180, 12, Quality.PREVIEW, "audio"),
        voice = voice,
    )

    private fun movie(ffmpeg: String, dir: File) {
        File(dir, "source").mkdirs()
        RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "testsrc2=s=320x180:r=12:d=10",
            "-f", "lavfi", "-i", "sine=f=200:sample_rate=48000:d=10", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", "source/movie.mp4"), dir)
    }

    private fun wav(ffmpeg: String, dir: File, name: String, expr: String, seconds: Int) =
        RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "aevalsrc='$expr':s=48000:d=$seconds", "-ac", "1", name), dir)

    /** Integrated loudness (LUFS) and true peak (dBTP) of [file] by ffmpeg's EBU R128 meter. */
    private fun ebur128(ffmpeg: String, file: File): Pair<Double, Double> {
        val (code, log) = RealFfmpeg.run(listOf(ffmpeg, "-hide_banner", "-nostdin", "-nostats", "-i", file.path, "-vn", "-af", "ebur128=peak=true", "-f", "null", "-"))
        assertEquals(0, code, log)
        val summary = log.substringAfterLast("Summary:")
        val i = Regex("""I:\s+(-?[0-9.]+) LUFS""").find(summary)!!.groupValues[1].toDouble()
        val tp = Regex("""Peak:\s+(-?[0-9.]+) dBFS""").find(summary)!!.groupValues[1].toDouble()
        return i to tp
    }

    /** Amplitude of the [hz] tone in [fromMs, toMs) (one DFT bin). */
    private fun tone(s: FloatArray, fromMs: Long, toMs: Long, hz: Double): Double {
        val a = (fromMs * 48).toInt(); val b = (toMs * 48).toInt().coerceAtMost(s.size)
        var re = 0.0; var im = 0.0
        for (i in a until b) { val w = 2 * PI * hz * i / 48_000.0; re += s[i] * cos(w); im += s[i] * sin(w) }
        return 2 * sqrt(re * re + im * im) / (b - a)
    }

    @Test fun realRenderJobMustActuallyPerformTwoPassLoudness() {
        val (ffmpeg, ffprobe) = RealFfmpeg.orSkip("AudioFfmpegTest") ?: return
        val dir = RealFfmpeg.tempDir("loudness")
        try {
            movie(ffmpeg, dir)
            // A quiet narration (≈ −29 LUFS): 1 kHz at 0.05, 1.5 s on / 0.5 s off for 8 s, from 1 s of a 10 s video.
            wav(ffmpeg, dir, "voice.wav", "if(lt(mod(t,2),1.5),0.05*sin(2*PI*1000*t),0)", 8)
            val (inI, _) = ebur128(ffmpeg, File(dir, "voice.wav"))
            val rec = Recorder()
            val runner = RenderJobRunner(LocalFs(), FfmpegRunner(rec, ffmpeg), MediaProbe(rec, ffprobe))
            val graph = graph(VoiceTrack(listOf(VoiceCue("voice.wav", 1_000)), clipsVolume = 0.0, loudnessLufs = -14.0))
            val state = runBlocking { runner.run(RenderJob(title = "l", graph = graph, workDir = dir.path, files = emptyMap(), fontsDir = "fonts", outputName = "exports/loud")) }
            val done = assertIs<RenderState.Done>(state, state.toString())

            // Pass 1: audio only (no picture map, no encoder, nothing written), loudnorm measuring with JSON.
            val ffmpegCalls = rec.calls.filter { it.first.first() == ffmpeg }
            assertEquals(2, ffmpegCalls.size, ffmpegCalls.map { it.first.takeLast(3) }.toString())
            val (measureCmd, measureLog) = ffmpegCalls[0]
            assertTrue("[vout]" !in measureCmd && "libx264" !in measureCmd && measureCmd.takeLast(3) == listOf("-f", "null", "-"), measureCmd.toString())
            assertTrue(measureCmd[measureCmd.indexOf("-filter_complex") + 1].endsWith("print_format=json[aout]"))
            val m = assertNotNull(AudioFilters.parseMeasurement(measureLog), "pass 1 printed no loudnorm JSON")
            // Pass 2: the real render, its loudnorm fed with exactly those numbers.
            val render = ffmpegCalls[1].first
            val fc = render[render.indexOf("-filter_complex") + 1]
            assertTrue(AudioFilters.loudnormApply(m, -14.0) in fc, "pass 2 does not use the measured numbers: $fc")
            assertEquals(m, done.loudness?.measured)
            assertTrue(done.loudness!!.twoPass && done.loudness.note == null)

            // The output: −14 LUFS within 1 LU (EBU R128's ±1 LU; AAC and the 4:2 gating move it a little), true
            // peak under the −1.5 dBTP ceiling with 0.5 dB for the AAC encode.
            val mp4 = File(dir, "exports/loud.mp4")
            val (outI, outTp) = ebur128(ffmpeg, mp4)
            println("AUDIO loudness: voice in $inI LUFS; pass-1 input_i=${m.inputI} input_tp=${m.inputTp} input_lra=${m.inputLra} thresh=${m.inputThresh} offset=${m.targetOffset}; output $outI LUFS, true peak $outTp dBTP")
            assertTrue(abs(outI - -14.0) <= 1.0, "output $outI LUFS, target −14")
            assertTrue(outTp <= -1.0, "true peak $outTp dBTP")
            assertTrue(abs(RealFfmpeg.durationMs(ffprobe, mp4) - 10_000) <= 100)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun silentNarrationFallsBackToOnePassAndSaysSo() {
        val (ffmpeg, ffprobe) = RealFfmpeg.orSkip("AudioFfmpegTest") ?: return
        val dir = RealFfmpeg.tempDir("loudness-silent")
        try {
            movie(ffmpeg, dir)
            wav(ffmpeg, dir, "voice.wav", "0", 3)
            val rec = Recorder()
            val runner = RenderJobRunner(LocalFs(), FfmpegRunner(rec, ffmpeg), MediaProbe(rec, ffprobe))
            val graph = graph(VoiceTrack(listOf(VoiceCue("voice.wav", 0)), clipsVolume = 0.0, loudnessLufs = -14.0), ms = 3_000)
            val state = runBlocking { runner.run(RenderJob(title = "l", graph = graph, workDir = dir.path, files = emptyMap(), fontsDir = "fonts", outputName = "exports/silent")) }
            val done = assertIs<RenderState.Done>(state, state.toString())
            // Silence measures −inf: no two-pass numbers, the one-pass filter, and the result says so.
            val loud = assertNotNull(done.loudness)
            assertNull(loud.measured)
            assertTrue(!loud.twoPass && loud.note!!.contains("one-pass"), loud.toString())
            val render = rec.calls.filter { it.first.first() == ffmpeg }.last().first
            val fc = render[render.indexOf("-filter_complex") + 1]
            assertTrue(AudioFilters.loudnormSinglePass(-14.0) + "," in fc && "measured_I" !in fc, fc)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun backgroundMusicLoopsFadesAndDucksUnderTheVoice() {
        val (ffmpeg, ffprobe) = RealFfmpeg.orSkip("AudioFfmpegTest") ?: return
        val dir = RealFfmpeg.tempDir("bgm")
        try {
            movie(ffmpeg, dir)
            // Voice: 1 kHz at 0.25 for 3 s from 3 s. Music: a 2 s 300 Hz loop at 0.2, volume 0.5 → 0.1 when not ducked.
            wav(ffmpeg, dir, "voice.wav", "0.25*sin(2*PI*1000*t)", 3)
            wav(ffmpeg, dir, "music.wav", "0.2*sin(2*PI*300*t)", 2)
            val graph = graph(VoiceTrack(listOf(VoiceCue("voice.wav", 3_000)), clipsVolume = 0.0, music = MusicBed("music.wav", 0.5, duck = true)))
            val state = runBlocking { RealFfmpeg.runner(ffmpeg, ffprobe).run(RenderJob(title = "m", graph = graph, workDir = dir.path, files = emptyMap(), fontsDir = "fonts", outputName = "exports/bgm")) }
            assertIs<RenderState.Done>(state, state.toString())
            val mp4 = File(dir, "exports/bgm.mp4")
            assertTrue(abs(RealFfmpeg.durationMs(ffprobe, mp4) - 10_000) <= 100)
            val s = RealFfmpeg.samples(ffmpeg, mp4, dir)
            val fadeIn = tone(s, 0, 200, 300.0)
            val before = tone(s, 1_600, 2_800, 300.0)
            val ducked = tone(s, 3_500, 5_500, 300.0)
            val after = tone(s, 6_700, 8_200, 300.0)   // past the 2 s file: looped
            val fadeOut = tone(s, 9_800, 9_990, 300.0)
            val voice = tone(s, 3_500, 5_500, 1_000.0)
            val voiceOff = tone(s, 6_700, 8_200, 1_000.0)
            val db = { x: Double -> 20 * kotlin.math.log10(x / before) }
            println("AUDIO bgm: 300 Hz amplitude fade-in ${"%.4f".format(fadeIn)}, before voice ${"%.4f".format(before)}, " +
                "under voice ${"%.4f".format(ducked)} (${"%.1f".format(db(ducked))} dB), after ${"%.4f".format(after)} (${"%.1f".format(db(after))} dB), " +
                "fade-out ${"%.4f".format(fadeOut)}; voice 1 kHz ${"%.4f".format(voice)} / off ${"%.4f".format(voiceOff)}")
            assertTrue(abs(before - 0.1) < 0.02, "music level $before, expected 0.1")
            assertTrue(db(ducked) < -6, "ducking under the voice only ${db(ducked)} dB")
            assertTrue(abs(db(after)) < 1.5, "music after the voice is back (and looped): ${db(after)} dB")
            assertTrue(fadeIn < before * 0.3 && fadeOut < before * 0.3, "fades: in $fadeIn, out $fadeOut")
            assertTrue(voice > 0.15 && voiceOff < 0.01, "voice $voice / $voiceOff")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun silenceTrimCutsPausesOver350msTo200msAndKeepsTheRest() {
        val (ffmpeg, _) = RealFfmpeg.orSkip("AudioFfmpegTest") ?: return
        val dir = RealFfmpeg.tempDir("trim")
        try {
            // 0.6 s lead, 4 × 0.5 s of speech with pauses of 1.0 s, 0.3 s and 0.5 s, 0.6 s tail (5 s).
            wav(ffmpeg, dir, "take.wav", "if(between(t,0.6,1.1)+between(t,2.1,2.6)+between(t,2.9,3.4)+between(t,3.9,4.4),0.5*sin(2*PI*440*t),0)", 5)
            val r = runBlocking { SilenceTrim(ProcessTools(), ffmpeg).trim("take.wav", "trimmed.wav", dir.path, 60_000) }
            assertIs<SilenceTrim.Result.Trimmed>(r, r.toString())
            val (code, log) = RealFfmpeg.run(listOf(ffmpeg, "-hide_banner", "-nostdin", "-nostats", "-i", File(dir, "trimmed.wav").path, "-af", "silencedetect=n=-40dB:d=0.05", "-f", "null", "-"))
            assertEquals(0, code, log)
            val gaps = AudioFilters.parseSilences(log)
            val total = AudioFilters.parseInputDuration(log)!!
            val lengths = gaps.map { (it.end ?: total) - it.start }
            val speech = gaps.zipWithNext { a, b -> b.start - a.end!! }
            println("AUDIO trim: ${"%.3f".format(total)} s (from 5.000); pauses ${lengths.map { "%.3f".format(it) }}; speech ${speech.map { "%.3f".format(it) }}")
            // Lead 0.6 → 0.2, 1.0 → 0.2, 0.3 stays, 0.5 → 0.2, tail 0.6 → 0.2; every 0.5 s of speech intact; 5 − 1.9 = 3.1 s.
            val expected = listOf(0.2, 0.2, 0.3, 0.2, 0.2)
            assertEquals(expected.size, lengths.size, lengths.toString())
            lengths.zip(expected).forEach { (got, want) -> assertTrue(abs(got - want) < 0.025, "pause $got s, expected $want") }
            speech.forEach { assertTrue(abs(it - 0.5) < 0.025, "speech $it s, expected 0.5") }
            assertTrue(abs(total - 3.1) < 0.03, "trimmed length $total s, expected 3.1")
            // No pause long enough: nothing is cut or written.
            wav(ffmpeg, dir, "tight.wav", "if(lt(mod(t,1),0.8),0.5*sin(2*PI*440*t),0)", 3)
            assertEquals(SilenceTrim.Result.Unchanged, runBlocking { SilenceTrim(ProcessTools(), ffmpeg).trim("tight.wav", "tight-out.wav", dir.path, 60_000) })
            assertTrue(!File(dir, "tight-out.wav").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
