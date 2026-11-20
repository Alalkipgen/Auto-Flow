package io.github.alalkipgen.autoflow.media.audio

import io.github.alalkipgen.autoflow.media.render.MediaTaskJob
import io.github.alalkipgen.autoflow.media.render.RealFfmpeg
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.*

/** V-3: golden Video sound commands, then real ffmpeg — the picture's packets unchanged, the length kept, pad / cut. */
class VideoSoundTest {
    @Test fun goldenCommands() {
        assertEquals(listOf("-hide_banner", "-y", "-nostdin", "-i", "source/movie.mp4", "-vn", "-map", "0:a:0", "-c:a", "copy",
            "-movflags", "+faststart", "-f", "mp4", "{part}"), VideoSound.soundOnlyArgs("source/movie.mp4", "aac", "m4a", "160k"))
        assertEquals(listOf("-c:a", "aac", "-b:a", "160k"), VideoSound.soundOnlyArgs("v.mkv", "opus", "m4a", "160k").subList(8, 12))
        assertEquals(listOf("-c:a", "pcm_s16le", "-f", "wav", "{part}"), VideoSound.soundOnlyArgs("v.mkv", "aac", "wav", "160k").takeLast(5))
        assertEquals(listOf("-hide_banner", "-y", "-nostdin", "-i", "v.mp4", "-i", "a.m4a", "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy",
            "-af", "apad", "-c:a", "aac", "-b:a", "160k", "-t", "65.250", "-movflags", "+faststart", "-f", "mp4", "{part}"),
            VideoSound.replaceArgs("v.mp4", "a.m4a", 65_250, "mp4", "160k"))
        assertEquals(listOf("-f", "matroska", "{part}"), VideoSound.replaceArgs("v.webm", "a.wav", 1_000, VideoSound.videoExt("webm"), "160k").takeLast(3))
        val add = VideoSound.addArgs("v.mp4", "a.wav", 4_000, 1_500, 0.8, lower = true, hasSound = true, outExt = "mp4", bitrate = "160k")
        assertEquals("[1:a]adelay=1500:all=1,volume=0.8,apad[add];[0:a]anull[orig];" + AudioFilters.mixWithMusic("add", "orig", "out", true).joinToString(";"),
            add[add.indexOf("-filter_complex") + 1])
        assertEquals(listOf("-map", "0:v:0", "-map", "[out]", "-c:v", "copy"), add.subList(add.indexOf("-filter_complex") + 2, add.indexOf("-filter_complex") + 8))
        assertEquals("[1:a]adelay=0:all=1,volume=1,apad[add];[add]anull[out]",
            VideoSound.addArgs("v.mp4", "a.wav", 4_000, 0, 1.0, lower = false, hasSound = false, outExt = "mp4", bitrate = "160k").let { it[it.indexOf("-filter_complex") + 1] })
    }

    @Test fun realFfmpegKeepsThePictureAndTheLength() {
        val (ffmpeg, ffprobe) = RealFfmpeg.orSkip("VideoSoundTest") ?: return
        val dir = Files.createTempDirectory("video-sound").toFile()
        try {
            val video = File(dir, "movie.mp4")
            RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-f", "lavfi", "-i", "testsrc2=s=320x180:r=25:d=4", "-f", "lavfi", "-i",
                "sine=f=300:d=4", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", video.path))
            fun tone(name: String, sec: Int) = File(dir, name).also {
                RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-f", "lavfi", "-i", "sine=f=800:d=$sec", "-c:a", "pcm_s16le", it.path))
            }
            val short = tone("short.wav", 2); val long = tone("long.wav", 6)
            fun picture(f: File) = RealFfmpeg.run(listOf(ffmpeg, "-hide_banner", "-nostdin", "-i", f.path, "-map", "0:v:0", "-c", "copy", "-f", "md5", "-")).second
                .lines().first { it.startsWith("MD5=") }
            fun audioMs(f: File) = RealFfmpeg.run(listOf(ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=duration",
                "-of", "default=nw=1:nk=1", f.path)).second.trim().lines().first().toDouble().times(1000).toLong()
            fun make(args: List<String>, out: File) = out.also { RealFfmpeg.ok(listOf(ffmpeg) + args.map { a -> if (a == MediaTaskJob.PART) it.path else a }) }

            val sound = make(VideoSound.soundOnlyArgs(video.path, "aac", "m4a", "160k"), File(dir, "sound.m4a"))
            assertEquals(listOf("audio"), RealFfmpeg.run(listOf(ffprobe, "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", sound.path)).second.trim().lines())
            assertTrue(abs(audioMs(sound) - 4_000) <= 100, "sound only keeps the length")

            for ((name, audio) in listOf("padded" to short, "cut" to long)) {
                val out = make(VideoSound.replaceArgs(video.path, audio.path, 4_000, "mp4", "160k"), File(dir, "$name.mp4"))
                assertEquals(picture(video), picture(out), "$name: the picture is not re-encoded")
                assertTrue(abs(audioMs(out) - 4_000) <= 100, "$name: the sound is the video's length, was ${audioMs(out)}")
                assertTrue(abs(RealFfmpeg.durationMs(ffprobe, out) - 4_000) <= 100, name)
            }
            val added = make(VideoSound.addArgs(video.path, short.path, 4_000, 1_000, 0.8, lower = true, hasSound = true, outExt = "mp4", bitrate = "160k"),
                File(dir, "added.mp4"))
            assertEquals(picture(video), picture(added))
            assertTrue(abs(audioMs(added) - 4_000) <= 100, "add on top keeps the video's length, was ${audioMs(added)}")
        } finally {
            dir.deleteRecursively()
        }
    }
}
