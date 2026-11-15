package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.recap.Srt
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/**
 * Audit F14 with a real ffprobe / ffmpeg: a movie with a video, a sound and two subtitle streams (2 = English,
 * 3 = Burmese, made here — no binary fixture). The probe must report the real stream indexes and tags, and the
 * app's extraction ([MediaProbe.extractSubtitleArgs], used by the Script step) must get each track's own text.
 */
class SubtitleProbeFfmpegTest {
    @Test fun realFfprobeMustPreserveSubtitleIndexesAndMetadata() {
        val (ffmpeg, ffprobe) = RealFfmpeg.orSkip("SubtitleProbeFfmpegTest") ?: return
        val dir = RealFfmpeg.tempDir("subtitles")
        try {
            File(dir, "en.srt").writeText("1\n00:00:00,200 --> 00:00:01,000\nHello there\n\n2\n00:00:01,200 --> 00:00:01,800\nGood night\n")
            File(dir, "my.srt").writeText("1\n00:00:00,300 --> 00:00:01,100\nမင်္ဂလာပါ\n\n2\n00:00:01,300 --> 00:00:01,900\nညနေခင်း\n")
            RealFfmpeg.ok(listOf(ffmpeg, "-hide_banner", "-y", "-nostdin",
                "-f", "lavfi", "-i", "color=c=blue:s=160x90:r=10:d=2", "-f", "lavfi", "-i", "sine=f=440:sample_rate=48000:d=2",
                "-i", "en.srt", "-i", "my.srt",
                "-map", "0:v", "-map", "1:a", "-map", "2:s", "-map", "3:s", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-c:s", "srt",
                "-metadata:s:s:0", "language=eng", "-metadata:s:s:0", "title=English",
                "-metadata:s:s:1", "language=mya", "-metadata:s:s:1", "title=Burmese", "subtitles.mkv"), dir)

            val probed = runBlocking { MediaProbe(ProcessTools(), ffprobe).probe(File(dir, "subtitles.mkv").path, dir.path) }
            val tracks = assertIs<MediaProbe.Result.Ok>(probed, probed.toString()).info.subtitleTracks
            println("SUBTITLES probe: $tracks")
            assertEquals(listOf(2, 3), tracks.map { it.index })
            assertEquals(listOf("eng", "mya"), tracks.map { it.language })
            assertEquals(listOf("English", "Burmese"), tracks.map { it.title })

            val texts = tracks.associate { t ->
                val out = File(dir, "out_${t.index}.srt")
                RealFfmpeg.ok(listOf(ffmpeg) + MediaProbe.extractSubtitleArgs(File(dir, "subtitles.mkv").path, t.index, out.path), dir)
                t.language to Srt.parse(out.readText()).map { it.text }
            }
            println("SUBTITLES extracted: $texts")
            assertEquals(listOf("Hello there", "Good night"), texts["eng"])
            assertEquals(listOf("မင်္ဂလာပါ", "ညနေခင်း"), texts["mya"])
        } finally {
            dir.deleteRecursively()
        }
    }
}
