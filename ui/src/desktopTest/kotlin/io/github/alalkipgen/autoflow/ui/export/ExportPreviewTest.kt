package io.github.alalkipgen.autoflow.ui.export

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import io.github.alalkipgen.autoflow.media.probe.MediaInfo
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.render.*
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class ExportPreviewTest {
    @Test fun geometryUsesRealAspectAtPhoneAndWideWidths() {
        for (w in listOf(360f, 412f, 840f)) {
            val h = 780f
            assertEquals(w, previewWidth(w, h, 16f / 9))
            assertEquals(468f, previewWidth(w, h, 9f / 16) / (9f / 16), .01f)
            assertEquals(minOf(w, 585f), previewWidth(w, h, 1f))
        }
    }

    @Test fun aFinishedPlayerKeepsThePosterAndReplaysFromZero() = runDesktopComposeUiTest(width = 412, height = 780) {
        val poster = ByteArrayOutputStream().use { out ->
            ImageIO.write(BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB).apply { setRGB(4, 4, 0xff224466.toInt()) }, "png", out)
            out.toByteArray()
        }
        val media = RecapFrames.PreviewMedia(MediaInfo(1200, 160, 90, 30.0, "h264", "aac"), poster)
        val starts = mutableListOf<Long>()
        setContent { Column {
            PreviewPlayback("fixture.mp4", media, null) { request, _, report ->
                LaunchedEffect(request.seekRevision, request.playing) {
                    if (request.playing) {
                        starts += request.sourceMs
                        report(MovieSnapshot(1200, 1200, MoviePhase.ENDED))
                    }
                }
            }
        } }
        onNodeWithTag("export-preview-poster").assertIsDisplayed()
        onNodeWithTag("export-preview-playback").performMouseInput { click() }
        onNodeWithTag("export-preview-player").assertIsDisplayed()
        onNodeWithTag("export-preview-poster").assertIsDisplayed()
        onNodeWithTag("export-preview-playback").assertTextEquals(UiText.PREVIEW_REPLAY.en).performMouseInput { click() }
        runOnIdle { assertEquals(listOf(0L, 0L), starts) }
        onNodeWithTag("export-preview-poster").assertIsDisplayed()
    }

    @Test fun productionPreviewsMatchFfprobeAndLoadTheirPostersOutsideTheProject() = runBlocking {
        ExportHarness().use { h ->
            val tools = requireNotNull(h.media.runner)
            val probe = MediaProbe(tools, requireNotNull(h.media.ffprobe()))
            val runner = RenderJobRunner(h.fs, FfmpegRunner(tools, requireNotNull(h.media.ffmpeg())), probe)
            val original = requireNotNull(h.session.open)
            val originalRecap = requireNotNull(original.project.recap)
            val rel = "exports/preview"
            val frames = RecapFrames(h.session, h.settings, h.fs, h.media, previewCacheDir = File(h.root, "cache").path)
            for (ratio in listOf(Ratio.R16_9, Ratio.R9_16, Ratio.R1_1)) {
                val open = original.copy(project = original.project.copy(recap = originalRecap.copy(visual = originalRecap.visual.copy(outputRatio = ratio))))
                val job = assertNotNull(frames.job(open, Quality.PREVIEW, null, rel, "synthetic preview"))
                assertEquals(ratio, job.graph.output.ratio)
                val done = assertIs<RenderState.Done>(runner.run(job))
                val file = File(done.output)
                val probeInfo = assertIs<MediaProbe.Result.Ok>(probe.probe(file.path, file.parent)).info
                val output = job.graph.output
                assertEquals(output.width, probeInfo.width)
                assertEquals(output.height, probeInfo.height)
                val before = file.readBytes()
                val inspected = assertNotNull(frames.inspectPreview(file.path))
                assertEquals(probeInfo, inspected.info)
                assertTrue(assertNotNull(inspected.poster).isNotEmpty())
                assertContentEquals(before, file.readBytes())
                assertFalse(File(original.dir, "poster.jpg").exists())
                println("Synthetic preview: ${ratio.label}, ffprobe ${probeInfo.width}x${probeInfo.height}")
            }
        }
    }
}
