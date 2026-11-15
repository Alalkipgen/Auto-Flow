package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.recap.SceneAudioChunks
import io.github.alalkipgen.autoflow.core.recap.TranscriptRange
import io.github.alalkipgen.autoflow.media.audio.SceneAudio
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 5B-1 with a real ffmpeg: the Transcribe request sound ([SceneAudio]) of two picked ranges of a movie whose sound is
 * 440 Hz for 10 s, then 880 Hz for 10 s. The chunk must be only those ranges, joined in movie order — its length is
 * their sum (±150 ms) and its pitch changes where the second range starts — mono 16 kHz AAC (`.m4a`), small.
 */
class SceneAudioFfmpegTest {
    @Test fun realChunkIsOnlyThePickedRangesJoinedInOrder() {
        val (ffmpeg, ffprobe) = RealFfmpeg.orSkip("SceneAudioFfmpegTest") ?: return
        val dir = RealFfmpeg.tempDir("scene-audio")
        try {
            RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin",
                "-f", "lavfi", "-i", "color=c=gray:s=160x90:r=10:d=20",
                "-f", "lavfi", "-i", "sine=f=440:sample_rate=48000:d=10", "-f", "lavfi", "-i", "sine=f=880:sample_rate=48000:d=10",
                "-filter_complex", "[1:a][2:a]concat=n=2:v=0:a=1,pan=stereo|c0=c0|c1=c0[a]", "-map", "0:v", "-map", "[a]",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k", "movie.mp4"), dir)
            // Picked out of order on purpose: the plan sorts them (12–15 s is the 880 Hz part, 3–7 s the 440 Hz part).
            val chunk = SceneAudioChunks.plan(listOf(TranscriptRange(12_000, 15_000), TranscriptRange(3_000, 7_000))).single()
            assertEquals(listOf(0L, 4_000L), chunk.scenes.map { it.offsetMs })
            val out = File(dir, "chunk.m4a")
            val err = runBlocking { SceneAudio(ProcessTools(), ffmpeg).cut(File(dir, "movie.mp4").path, chunk, out.path, dir.path) }
            assertNull(err, err)

            val (code, info) = RealFfmpeg.run(listOf(ffprobe, "-v", "error", "-show_entries", "stream=codec_type,codec_name,sample_rate,channels", "-of", "csv=p=0", out.path))
            assertEquals(0, code, info)
            // ffprobe prints the entries in its own order: codec_name, codec_type, sample_rate, channels.
            assertEquals(listOf("aac,audio,16000,1"), info.trim().lines().map { it.trim() }.filter { it.isNotEmpty() },
                "only one mono 16 kHz AAC stream, no picture")
            val length = RealFfmpeg.durationMs(ffprobe, out)
            println("SCENE AUDIO: ${out.length()} bytes, $length ms (expected ${chunk.durationMs} ms)")
            assertTrue(abs(length - chunk.durationMs) <= 150, "length $length ms vs ${chunk.durationMs} ms")
            assertTrue(out.length() < 32_000 / 8 * 7 * 2, "about 32 kbit/s: ${out.length()} bytes for 7 s")

            val s = RealFfmpeg.samples(ffmpeg, out, dir)
            val first = RealFfmpeg.hz(s, 500, 3_500)
            val second = RealFfmpeg.hz(s, 4_500, 6_500)
            println("SCENE AUDIO pitch: first range $first Hz, second range $second Hz")
            assertTrue(abs(first - 440) < 30, "first range is the 3–7 s part (440 Hz): $first")
            assertTrue(abs(second - 880) < 40, "second range is the 12–15 s part (880 Hz): $second")
        } finally {
            dir.deleteRecursively()
        }
    }
}
