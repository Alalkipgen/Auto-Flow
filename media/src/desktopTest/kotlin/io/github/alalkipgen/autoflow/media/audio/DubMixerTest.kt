package io.github.alalkipgen.autoflow.media.audio

import io.github.alalkipgen.autoflow.media.render.BurnLook
import io.github.alalkipgen.autoflow.media.render.BurnSize
import io.github.alalkipgen.autoflow.media.render.FontInfo
import io.github.alalkipgen.autoflow.media.render.MediaTaskJob
import io.github.alalkipgen.autoflow.media.render.RealFfmpeg
import io.github.alalkipgen.autoflow.media.render.SubtitleBurn
import io.github.alalkipgen.autoflow.core.recap.SrtCue
import java.io.File
import kotlin.math.abs
import kotlin.test.*

/** D-3a (specs/dubbing.md §6, §8 D-3): the dub mix — cues on time, never overlapping, Lower / Mute, the picture copied. */
class DubMixerTest {
    private val cues = listOf(
        DubCue("takes/a.wav", 500, 150, 1_400),
        DubCue("takes/b.wav", 2_000, 150, 1_400),
        DubCue("takes/c.wav", 3_500, 150, 2_500, speed = 1.2),
    )

    @Test fun argumentsAndNames() {
        assertEquals("[0:a]aformat=sample_rates=48000:channel_layouts=stereo,atrim=start=0.150,asetpts=PTS-STARTPTS,atrim=duration=1.400," +
            "adelay=500:all=1[c0]", DubMixer.chain(0, cues[0], "c0"))
        assertTrue(",atempo=1.2,atrim=duration=2.500,adelay=3500:all=1[c2]" in DubMixer.chain(2, cues[2], "c2"), "atempo only above 1.0")
        val plan = DubMixer.voicePlan(cues, 6_000, group = 2)
        assertEquals(listOf(2, 1), plan.stems.map { s -> s.args.count { it == "-i" } }, "stems of two cues")
        assertEquals(listOf(DubMixer.STEM, DubMixer.STEM, DubMixer.VOICE), plan.tasks.map { it.kind })
        assertEquals(plan.stems.map { it.output }, plan.voice.args.filterIndexed { i, _ -> i > 0 && plan.voice.args[i - 1] == "-i" })
        assertEquals(plan, DubMixer.voicePlan(cues, 6_000, group = 2), "the same lines, the same files (Resume reuses them)")
        val moved = DubMixer.voicePlan(cues.take(2) + cues[2].copy(atMs = 3_600), 6_000, group = 2)
        assertEquals(plan.stems[0].output, moved.stems[0].output, "an unchanged stem keeps its file")
        assertNotEquals(plan.stems[1].output, moved.stems[1].output)
        assertNotEquals(plan.voice.output, moved.voice.output)
        assertTrue(plan.tasks.all { it.output.startsWith(DubMixer.PARTS_DIR + "/") && it.args.last() == MediaTaskJob.PART && it.durationMs == 6_000L })
        assertEquals("out/voice.wav", DubMixer.voicePlan(cues, 6_000, 50, voiceOut = "out/voice.wav").voice.output)

        val copy = DubMixer.exportArgs("source/v.mp4", "v.wav", 6_000, 0.15, hasSound = true, outExt = "mp4", bitrate = "192k")
        assertEquals(listOf("-map", "0:v:0", "-c:v", "copy"), copy.subList(copy.indexOf("-filter_complex") + 2, copy.indexOf("-filter_complex") + 6))
        assertTrue("[0:a]aformat=sample_rates=48000:channel_layouts=stereo,volume=0.15[orig]" in copy[copy.indexOf("-filter_complex") + 1])
        val mute = DubMixer.exportArgs("source/v.mp4", "v.wav", 6_000, 0.0, hasSound = true, outExt = "mp4", bitrate = "192k")
        assertEquals("[1:a]aformat=sample_rates=48000:channel_layouts=stereo[aout]", mute[mute.indexOf("-filter_complex") + 1], "Mute = only the voice")
        assertEquals(listOf("-f", "matroska", MediaTaskJob.PART),
            DubMixer.exportArgs("v.mkv", "v.wav", 6_000, 0.15, true, "mkv", "192k").takeLast(3))
        val burn = DubMixer.exportArgs("v.mkv", "v.wav", 6_000, 0.15, true, "mkv", "192k", ass = "a.ass", fontsDir = "fonts")
        assertTrue(burn[burn.indexOf("-filter_complex") + 1].startsWith("[0:v]ass=filename=a.ass:fontsdir=fonts[vout];"))
        assertTrue("libx264" in burn && "copy" !in burn)
        assertEquals(listOf("-f", "mp4", MediaTaskJob.PART), burn.takeLast(3), "a burned export is always mp4")
    }

    @Test fun realFfmpegPutsEveryCueOnItsLineAndLowersOrMutesTheOriginal() {
        val (ffmpeg, ffprobe) = RealFfmpeg.orSkip("DubMixerTest") ?: return
        val dir = RealFfmpeg.tempDir("dub-mix")
        try {
            File(dir, "source").mkdirs(); File(dir, "takes").mkdirs()
            RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "testsrc2=s=320x180:r=25:d=6", "-f", "lavfi",
                "-i", "sine=f=300:d=6", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", "source/v.mp4"), dir)
            // each take: 150 ms of silence, 700 ms of an 800 Hz voice, 900 ms of silence (24 kHz mono, as TTS gives)
            for (n in listOf("a", "b", "c")) RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i",
                "sine=f=800:d=0.7:sample_rate=24000", "-af", "adelay=150:all=1,apad=pad_dur=0.9", "-ac", "1", "-c:a", "pcm_s16le", "takes/$n.wav"), dir)
            fun run(task: MediaTaskJob): File {
                val out = File(dir, task.output).also { it.parentFile.mkdirs() }
                if (!out.exists()) RealFfmpeg.ok(listOf(ffmpeg) + task.args.map { if (it == MediaTaskJob.PART) out.path else it }, dir)
                return out
            }
            val plan = DubMixer.voicePlan(cues.map { it.copy(speed = 1.0) }, 6_000, group = 2)
            val voice = plan.tasks.map(::run).last()
            assertTrue(abs(RealFfmpeg.durationMs(ffprobe, voice) - 6_000) <= 40, "the voice track is the video's length")
            val log = RealFfmpeg.run(listOf(ffmpeg, "-hide_banner", "-nostdin", "-i", voice.path, "-af", "silencedetect=n=-40dB:d=0.1", "-f", "null", "-")).second
            val silences = AudioFilters.parseSilences(log)
            val onsets = silences.mapNotNull { it.end }.map { (it * 1000).toLong() }.filter { it < 5_900 }
            assertEquals(cues.size, onsets.size, "one sound per cue, none merged: $silences")
            onsets.zip(cues).forEach { (got, c) -> assertTrue(abs(got - c.atMs) <= 40, "cue at ${c.atMs} ms started at $got ms") }
            val ends = silences.drop(1).map { (it.start * 1000).toLong() }
            ends.zip(cues).forEach { (end, c) -> assertTrue(end <= c.atMs + c.maxMs, "the cue at ${c.atMs} ends by its slot (${c.atMs + c.maxMs}), ended $end") }

            fun picture(f: File) = RealFfmpeg.run(listOf(ffmpeg, "-hide_banner", "-nostdin", "-i", f.path, "-map", "0:v:0", "-c", "copy", "-f", "md5", "-"))
                .second.lines().first { it.startsWith("MD5=") }
            val source = RealFfmpeg.samples(ffmpeg, File(dir, "source/v.mp4"), dir)
            val quiet = RealFfmpeg.rms(source, 1_300, 1_900) // between two lines: only the original plays
            for ((name, volume) in listOf("lower" to 0.15, "mute" to 0.0)) {
                val out = run(MediaTaskJob(DubMixer.EXPORT, DubMixer.exportArgs("source/v.mp4", plan.voice.output, 6_000, volume, true, "mp4", "192k"),
                    "exports/$name.mp4", 6_000))
                assertEquals(picture(File(dir, "source/v.mp4")), picture(out), "$name: the picture is copied")
                assertTrue(abs(RealFfmpeg.durationMs(ffprobe, out) - 6_000) <= 100, name)
                val s = RealFfmpeg.samples(ffmpeg, out, dir)
                val gap = RealFfmpeg.rms(s, 1_300, 1_900)
                if (volume > 0) assertTrue(abs(gap / quiet - 0.15) <= 0.03, "Lower keeps the original at 15 %: ${gap / quiet}")
                else assertTrue(gap < 0.002, "Mute removes the original: $gap")
                assertTrue(RealFfmpeg.rms(s, 600, 1_100) > 0.05, "$name: the dub voice plays on its line")
                if (volume == 0.0) assertTrue(abs(RealFfmpeg.hz(s, 600, 1_100) - 800) < 60, "mute: only the take is heard")
            }

            val font = RealFfmpeg.anyFont() ?: error("no TrueType font for the burned text")
            val info = FontInfo.parse(font.readBytes())!!
            File(dir, "fonts").mkdirs(); font.copyTo(File(dir, "fonts/${font.name}"))
            File(dir, "a.ass").writeText(SubtitleBurn.ass(listOf(SrtCue(500, 1_500, "One")), 320, 180, info.family, BurnLook.BOXED, BurnSize.LARGE, 42, info.assScale))
            val burned = run(MediaTaskJob(DubMixer.EXPORT, DubMixer.exportArgs("source/v.mp4", plan.voice.output, 6_000, 0.15, true, "mp4", "192k",
                ass = "a.ass", fontsDir = "fonts", x264Preset = "ultrafast"), "exports/burned.mp4", 6_000))
            val streams = RealFfmpeg.run(listOf(ffprobe, "-v", "error", "-show_entries", "stream=codec_name,codec_type,width,height", "-of", "csv=p=0", burned.path)).second
            assertTrue(streams.lines().any { it.startsWith("h264,video,320,180") } && streams.lines().any { it.startsWith("aac,audio") }, streams)
            assertNotEquals(picture(File(dir, "source/v.mp4")), picture(burned), "burning re-encodes the picture")
            assertTrue(abs(RealFfmpeg.durationMs(ffprobe, burned) - 6_000) <= 100)
        } finally {
            dir.deleteRecursively()
        }
    }
}
