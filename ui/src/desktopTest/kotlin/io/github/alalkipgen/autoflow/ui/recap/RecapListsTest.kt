package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.*
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * P9-11a: Source scene picks and Story / Voice block rows — the 128 × 72 scene frame beside the number and real time
 * (above them for large fonts / narrow widths), a drawn check mark that follows the row's checkbox state, the play icon
 * button, strict text fit (DESIGN.md §9.6) and no project file written.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class RecapListsTest(val width: Int, val font: Float, val language: UiLanguage) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}")
        fun cases() = listOf(1f, 1.3f, 2f).flatMap { f -> UiLanguage.entries.map { l -> arrayOf<Any>(412, f, l) } } + listOf(
            arrayOf<Any>(360, 1f, UiLanguage.BURMESE), arrayOf<Any>(360, 2f, UiLanguage.BURMESE),
            arrayOf<Any>(840, 1.3f, UiLanguage.BURMESE), arrayOf<Any>(1440, 1f, UiLanguage.ENGLISH), arrayOf<Any>(1440, 1f, UiLanguage.BURMESE))
    }

    private fun ComposeUiTest.bounds(matcher: SemanticsMatcher): Rect = onNode(matcher, useUnmergedTree = true).getUnclippedBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
    }

    @Test fun sceneAndBlockRowsShowTheirFrameBesideTheFactsAndFitStrictly() = runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
        val f = QaRecapFixture.create(Files.createTempDirectory("p911-lists").toFile()).let { f ->
            f.copy(project = (ProjectStore(JvmFs()).save(f.dir.path, f.project.copy(recap = f.project.recap!!.copy(
                scenes = listOf(RecapScene(2_000, 4_000, true), RecapScene(6_000, 8_000, false))))) as CoreResult.Ok).value)
        }
        val before = StudioThumbnailsLayoutTest.bytes(f.dir); val fs = JvmFs()
        val current = CurrentProject().apply { set(OpenProject(f.dir.path, f.project)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val commands = Collections.synchronizedList(mutableListOf<List<String>>())
        val media = MediaTools(SettingsStore(fs, File(f.dir.parentFile, "settings.json").path), DesktopToolLocator(fs),
            StudioThumbnailsLayoutTest.tools(commands))
        val model = RecapModel(ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, File(f.dir.parentFile, "index.json").path), current),
            fs, media, null, scope, thumbnailCacheDir = File(f.dir.parentFile, "app-cache").path)
        var step by mutableStateOf(RecapStep.SOURCE)
        try {
            setContent {
                CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) {
                        StudioRecapScreen(model, step, width.toFloat(), {}, RecapNavigation({},
                            { step = if (step == RecapStep.SOURCE) RecapStep.STORY else RecapStep.VOICE }, {}))
                    }
                }
            }
            val captures = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "recap-lists").apply { mkdirs() }
            // Scroll the row and its frame slot into view (a frame loads only on screen), then wait for the real frame.
            fun frame(row: String, thumb: String): Rect {
                onNodeWithTag(row).performScrollTo()
                onNodeWithTag("$thumb-slot", useUnmergedTree = true).performScrollTo()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag(thumb, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag(row).performScrollTo()
                val slot = bounds(hasTestTag("$thumb-slot"))
                // 128 × 72; narrower only where the row itself is (the 840 dp three-pane list).
                assertTrue(slot.width > 0f && slot.width <= 128.5f, "frame width $slot"); assertEquals(72f, slot.height, .5f)
                assertEquals(slot, bounds(hasTestTag(thumb)), "the real frame fills its fixed slot")
                val card = bounds(hasTestTag(row))
                assertTrue(slot.left >= card.left && slot.right <= card.right, "frame inside its row")
                return slot
            }
            // Beside: the frame is left of the number (vertically overlapping). Above: the number is above the frame.
            fun layout(slot: Rect, number: Rect): Boolean {
                val beside = slot.right <= number.left && slot.top < number.bottom && number.top < slot.bottom
                assertTrue(beside || number.bottom <= slot.top, "frame beside or below-left of the facts: $slot / $number")
                if (width == 412 && font == 1f) assertTrue(beside, "412 dp × 1.0: frame beside the facts")
                if (width == 360 && font == 2f) assertFalse(beside, "360 dp × 2.0: facts above the frame, never a squeezed column")
                return beside
            }
            fun capture(name: String) {
                assertEquals(emptyList(), textFitProblems(width, font, strict = true))
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(captures, "$name-$width-${language.code}-$font.png"))
            }

            // Source: check-box rows — check mark · frame · "#n" + time; the drawn mark follows the real toggle state.
            val scene = frame("scene-pick-0", "scene-thumbnail-0")
            val number = bounds(hasText("#1") and hasAnyAncestor(hasTestTag("scene-pick-0")))
            val mark = bounds(hasTestTag("check-mark-on") and hasAnyAncestor(hasTestTag("scene-pick-0")))
            assertEquals(24f, mark.width, .5f)
            assertTrue(mark.right <= number.left, "check mark before the number")
            if (layout(scene, number)) assertTrue(mark.right <= scene.left, "check mark · frame · facts")
            onNodeWithTag("scene-pick-0").assertIsOn()
            onNodeWithTag("scene-pick-1").performScrollTo().assertIsOff()
            onNode(hasTestTag("check-mark-off") and hasAnyAncestor(hasTestTag("scene-pick-1")), useUnmergedTree = true).assertExists()
            onAllNodes(hasText("☑", substring = true) or hasText("☐", substring = true), useUnmergedTree = true).assertCountEquals(0)
            onNodeWithTag("scene-pick-0").performScrollTo()
            capture("02-source")

            // Story: block rows — frame beside "Block n" and its real clip times, status chip, script line, no ▶ glyph.
            onNodeWithTag("recap-next").assertIsDisplayed().performMouseInput { click() }
            val block = frame("block-select-0", "block-thumbnail-stable-a")
            val title = UiText.V3_BLOCK.text(language, "n" to 1)
            layout(block, bounds(hasText(title) and hasAnyAncestor(hasTestTag("block-select-0"))))
            onNode(SemanticsMatcher("a status chip") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("status-") == true } and hasAnyAncestor(hasTestTag("block-select-0")), useUnmergedTree = true).assertExists()
            capture("03-story")

            // Voice: the voiced block's play is a 48 dp icon button with its label (no ▶ / ■ text glyphs).
            onNodeWithTag("recap-next").assertIsDisplayed().performMouseInput { click() }
            val voiced = frame("block-select-0", "block-thumbnail-stable-a")
            layout(voiced, bounds(hasText(title) and hasAnyAncestor(hasTestTag("block-select-0"))))
            // (Not the thumbnail's own "Play" badge `block-preview-0`, PR 2: that one plays the picture in the top card.)
            val play = hasContentDescription(UiText.AUDIO_PLAY.text(language)) and hasAnyAncestor(hasTestTag("block-select-0")) and
                !hasTestTag("block-preview-0")
            onNode(play, useUnmergedTree = true).performScrollTo()
            val button = bounds(play)
            assertTrue(button.width >= 48f && button.height >= 48f, "play target $button")
            onAllNodes(hasText("▶") or hasText("■") or hasText("▶️"), useUnmergedTree = true).assertCountEquals(0)
            capture("04-voice")
            assertEquals(before, StudioThumbnailsLayoutTest.bytes(f.dir), "browsing the lists must not change any project file")
        } finally { scope.cancel() }
    }
}
