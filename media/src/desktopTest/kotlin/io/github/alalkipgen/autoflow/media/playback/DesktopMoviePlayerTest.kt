package io.github.alalkipgen.autoflow.media.playback

import io.github.alalkipgen.autoflow.media.render.RealFfmpeg
import org.junit.AssumptionViolatedException
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.*

class DesktopMoviePlayerTest {
    @Test fun missingMovieAndBadConfiguredDirectoryAreUnavailableNotStartupFailures() {
        assertIs<MovieOpen.Unavailable>(DesktopMoviePlayer.open("/does-not-exist/movie.mp4"))
        val file = Files.createTempFile("af-not-a-movie", ".mp4").toFile()
        try {
            val result = assertIs<MovieOpen.Unavailable>(DesktopMoviePlayer.open(file.path, file.path))
            assertTrue(result.reason.contains("Settings"))
        } finally { file.delete() }
    }

    @Test fun realInstalledVlcDecodesPausesSeeksChangesMediaAndReleases() {
        val (ffmpeg, _) = RealFfmpeg.orSkip("DesktopMoviePlayerTest")
            ?: throw AssumptionViolatedException("ffmpeg / ffprobe unavailable")
        val dir = Files.createTempDirectory("af-vlc-runtime").toFile()
        val file = File(dir, "portrait.mp4")
        try {
            RealFfmpeg.ok(listOf(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=180x320:rate=25",
                "-t", "10", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25", file.path))
            val opened = DesktopMoviePlayer.open(file.path, System.getenv("AUTOFLOW_VLC_DIR"))
            if (opened is MovieOpen.Unavailable) {
                if (System.getenv("AUTOFLOW_EXPECT_VLC") == "1") fail(opened.reason)
                throw AssumptionViolatedException("No compatible separately installed VLC: ${opened.reason}")
            }
            val player = (opened as MovieOpen.Ready).player
            try {
                player.setPlaying(true)
                await("two changing decoded frames") { (player.frames.value?.serial ?: 0) > 2 }
                val first = player.frames.value!!
                await("source clock advances") { player.snapshot().positionMs > 600 }
                val second = player.frames.value!!
                assertFalse(first.bgra.contentEquals(second.bgra), "real changing video pixels, not a thumbnail")
                assertEquals(180 * 320, second.width * second.height, "decoder padding is cropped")
                player.setPlaying(false)
                await("paused") { player.snapshot().phase == MoviePhase.PAUSED }
                Thread.sleep(150)
                val paused = player.snapshot().positionMs
                Thread.sleep(250)
                assertTrue(abs(player.snapshot().positionMs - paused) <= 80, "pause freezes the actual decoder clock")
                player.seek(5_000)
                await("paused seek changes actual source clock") { abs(player.snapshot().positionMs - 5_000) < 250 }
                player.setRate(0.8f); player.setPlaying(true)
                await("resume after seek") { player.snapshot().positionMs > 5_400 }
                assertNull(player.snapshot().problem)
            } finally { player.close(); player.close() }
            assertEquals(MoviePhase.CLOSED, player.snapshot().phase)
            assertNull(player.frames.value)
            player.setPlaying(true); player.seek(0) // disposed player is safe and never revived
            assertEquals(MoviePhase.CLOSED, player.snapshot().phase)
            // A project/media replacement creates a NEW decoder, rather than retaining the first file or frame.
            val replacement = assertIs<MovieOpen.Ready>(DesktopMoviePlayer.open(file.path, System.getenv("AUTOFLOW_VLC_DIR"))).player
            try { replacement.setPlaying(true); await("replacement frame") { replacement.frames.value != null } }
            finally { replacement.close() }
            println("PLAYBACK_EVIDENCE: real VLC frames changed; pause <=80ms; paused seek ±250ms; rate/resume; close twice; replacement decode")
        } finally { dir.deleteRecursively() }
    }

    private fun await(what: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (!condition() && System.nanoTime() < end) Thread.sleep(30)
        assertTrue(condition(), "Timed out: $what")
    }
}
