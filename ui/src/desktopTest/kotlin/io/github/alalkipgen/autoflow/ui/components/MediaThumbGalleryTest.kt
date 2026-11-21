package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.media.MediaThumbnails
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.AfSize
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P9-7 `MediaThumb` (bundle §4.9): every size, placeholder and real frame, badge and play, phone + desktop × font 1.0 / 2.0. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class MediaThumbGalleryTest(val width: Int, val font: Float) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}dp/{1}")
        fun cases() = listOf(360, 1440).flatMap { w -> listOf(1f, 2f).map { arrayOf<Any>(w, it) } }
        private val frame by lazy { picture().toComposeImageBitmap() }
        private fun picture() = BufferedImage(240, 136, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(0x1d4ed8), 240f, 136f, java.awt.Color(0xf59e0b)); fillRect(0, 0, 240, 136)
                color = java.awt.Color(0x0b1020); fillOval(150, 30, 60, 60); dispose()
            }
        }
        private val sizes = listOf(AfSize.ThumbCompact, AfSize.ThumbContinue, AfSize.ThumbList, AfSize.ThumbExport)
    }

    @Test fun thumbnailsFitAtEverySizeAndPlaceholdersAreNeverFakePictures() = runDesktopComposeUiTest(width = width, height = 640) {
        setContent {
            CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                LocalUiLanguage provides UiLanguage.BURMESE, LocalUiFontFamily provides BurmeseTestFont.family) {
                Column(Modifier.fillMaxSize().background(AutoFlowColors.Background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(null, frame).forEach { image ->
                        // A Burmese caption per row, as on the screens that place thumbnails next to text.
                        GroupLabel(tr(if (image == null) UiText.HOME_RECENT else UiText.V3_CONTINUE))
                        WrapRow { sizes.forEachIndexed { i, size ->
                            MediaThumb(image, listOf(AfIcons.Duotone.Film, AfIcons.Duotone.Music, AfIcons.Duotone.Dub, AfIcons.Duotone.Wave)[i],
                                size = size, durationMs = listOf(42_000L, 768_000L, 3_725_000L, 95_000L)[i], play = size == AfSize.ThumbExport)
                        } }
                    }
                    // The full-width player form (16:9), kept inside the test window so it does not overflow onto the rows.
                    Box(Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(16 / 9f)) {
                        MediaThumb(frame, AfIcons.Duotone.Film, Modifier.fillMaxSize(), size = AfSize.ThumbExport, play = true)
                    }
                }
            }
        }
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))
        assertEquals(4, onAllNodesWithTag("media-thumb-placeholder", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(5, onAllNodesWithTag("media-thumb-image", useUnmergedTree = true).fetchSemanticsNodes().size)
        // Badges sit inside their thumbnail (never cut); at font 1.0 every size has room for one.
        val thumbs = onAllNodesWithTag("media-thumb", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        val badges = onAllNodes(hasText(":", substring = true), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        badges.forEach { b -> assertTrue(thumbs.any { t -> b.left >= t.left && b.right <= t.right && b.top >= t.top && b.bottom <= t.bottom }, "badge $b inside a thumbnail") }
        if (font == 1f) assertEquals(8, badges.size)
        // A badge never covers the play circle (it is left out instead, e.g. on the export size at font 2.0).
        val plays = onAllNodesWithTag("media-thumb-play", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(3, plays.size)
        badges.forEach { b -> assertTrue(plays.none { it.overlaps(b) }, "badge $b covers a play circle") }
        System.getenv("AUTOFLOW_PR_SHOTS_DIR")?.let {
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(File(it).apply { mkdirs() }, "thumbs-${if (width < 600) "phone-$font" else "desktop-$font"}.png"))
        }
    }
}

/** P9-7: the on-screen thumbnail asks the cache off the main thread; a failure keeps the placeholder. */
@OptIn(ExperimentalTestApi::class)
class MediaThumbSourceTest {
    @Test fun aVisibleThumbShowsTheRealFrameAndAFailureKeepsThePlaceholder() {
        for (works in listOf(true, false)) runDesktopComposeUiTest(width = 400, height = 300) {
            val fs = InMemoryFileSystem().apply { createDirectories("/p/source"); writeDurably("/p/source/movie.mp4", byteArrayOf(1)) }
            val ui = Thread.currentThread().id
            var offMain = true
            val runner = ToolRunner { cmd, _, _, _, _ ->
                if (Thread.currentThread().id == ui) offMain = false
                if (works) fs.writeDurably(cmd.last(), ByteArrayOutputStream().also { ImageIO.write(BufferedImage(240, 136, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray())
                ToolExec.Done(0, "", emptyList(), 1, false)
            }
            val locator = object : ToolLocator {
                override fun findFfmpeg() = "/fake/ffmpeg"
                override fun findVlc(): String? = null
                override fun isVlcDir(dir: String) = false
            }
            val thumbs = MediaThumbnails(fs, MediaTools(SettingsStore(fs, "/app/settings.json"), locator, runner), "/app/cache")
            setContent {
                CompositionLocalProvider(LocalMediaThumbnails provides thumbs) {
                    MediaThumb(MediaThumbnails.Source("/p", "source/movie.mp4", durationMs = 60_000), AfIcons.Duotone.Film)
                }
            }
            if (works) waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("media-thumb-image", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            else { Thread.sleep(500); waitForIdle(); onNodeWithTag("media-thumb-placeholder", useUnmergedTree = true).assertExists() }
            assertTrue(offMain, "ffmpeg ran on the UI thread")
            assertEquals(listOf("movie.mp4"), fs.list("/p/source"), "nothing written next to the project's video")
        }
    }
}
