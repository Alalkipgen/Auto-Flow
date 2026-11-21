package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.tool.*
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
import java.security.MessageDigest
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.test.*

@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class StudioThumbnailsLayoutTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object {
        private val fixture by lazy {
            val f = QaRecapFixture.create(Files.createTempDirectory("uiv3-thumbnails").toFile())
            f.copy(project = (ProjectStore(JvmFs()).save(f.dir.path, f.project.copy(recap = f.project.recap!!.copy(
                scenes = listOf(RecapScene(2_000, 4_000, true), RecapScene(6_000, 8_000, true))))) as CoreResult.Ok).value)
        }
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}/bundled={3}")
        fun cases() = StudioVoiceLayoutTest.cases()
        internal fun bytes(dir: File) = dir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(dir).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
        }
        internal fun tools(commands: MutableList<List<String>>) = ToolRunner { cmd, wd, timeout, keep, onLine ->
            assertFalse(javax.swing.SwingUtilities.isEventDispatchThread(), "media work must stay off the UI thread")
            commands += cmd
            when (val r = ProcessRunner.run(cmd, File(wd), timeout, keep, onLine)) {
                is ProcessRunner.Exec.Done -> ToolExec.Done(r.exitCode, r.output, r.tail, r.wallMs, r.timedOut)
                is ProcessRunner.Exec.CannotStart -> ToolExec.CannotStart(r.message)
            }
        }
    }

    @Test fun realSourceStoryVoiceImagesScrollAndNavigateWithoutChangingAnyProjectFile() =
        runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
            val f = fixture; val before = bytes(f.dir); val fs = JvmFs()
            val current = CurrentProject().apply { set(OpenProject(f.dir.path, f.project)) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val commands = Collections.synchronizedList(mutableListOf<List<String>>())
            val media = MediaTools(SettingsStore(fs, File(f.dir.parentFile, "settings.json").path), DesktopToolLocator(fs), tools(commands))
            val model = RecapModel(ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs,
                File(f.dir.parentFile, "index.json").path), current), fs, media, null, scope,
                thumbnailCacheDir = File(f.dir.parentFile, "app-cache").path)
            var step by mutableStateOf(RecapStep.SOURCE)
            try {
                setContent {
                    CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                        LocalUiLanguage provides language, LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                        StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) {
                            StudioRecapScreen(model, step, width.toFloat(), {}, RecapNavigation({},
                                { step = if (step == RecapStep.SOURCE) RecapStep.STORY else RecapStep.VOICE }, {}))
                        }
                    }
                }
                val captures = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "thumbnails").apply { mkdirs() }
                fun image(tag: String, parent: String) {
                    onNodeWithTag(parent).performScrollTo().assertIsDisplayed()
                    // At 360 dp / font 2.0 the card is taller than the space between the bars: bring its picture slot
                    // into view, since a thumbnail loads only once its slot is on screen.
                    onNodeWithTag("$tag-slot", useUnmergedTree = true).performScrollTo()
                    waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                    val bounds = onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
                    val imageWidth = (bounds.right - bounds.left).value
                    assertTrue(imageWidth > 0f && imageWidth <= 128f, "thumbnail fits even the narrow wide-layout list pane")
                    assertEquals(72f, (bounds.bottom - bounds.top).value)
                    assertTrue(bounds.bottom <= onNodeWithTag("sticky-action-bar").getUnclippedBoundsInRoot().top)
                    val target = onNodeWithTag(parent).getUnclippedBoundsInRoot()
                    assertTrue((target.right - target.left).value >= 48f && (target.bottom - target.top).value >= 48f)
                    assertTrue(bounds.left >= target.left && bounds.right <= target.right)
                }
                fun capture(name: String) {
                    assertEquals(emptyList(), textFitProblems(width, font))
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png",
                        File(captures, "$name-$width-${language.code}-$font-$bundled.png"))
                }
                image("scene-thumbnail-0", "scene-pick-0")
                assertProjectPanesAndStickyActions(width, "recap-next")
                capture("02-source")
                onNodeWithTag("recap-next").assertIsDisplayed().performMouseInput { click() }
                image("block-thumbnail-stable-a", "block-select-0")
                assertProjectPanesAndStickyActions(width, "recap-next")
                capture("03-story")
                onNodeWithTag("block-select-0").assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("project-back").assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("recap-next").assertIsDisplayed().performMouseInput { click() }
                image("block-thumbnail-stable-a", "block-select-0")
                assertProjectPanesAndStickyActions(width, "voice-remaining", "recap-next")
                capture("03-voice")
                onNodeWithTag("voice-open-0").performScrollTo().assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("project-back").assertIsDisplayed().performMouseInput { click() }
                assertEquals(before, bytes(f.dir), "opening, caching, scrolling and navigation must preserve the entire project tree")
                assertTrue(commands.all { "-frames:v" in it && "scale=240:-2" in it }, "only bounded source thumbnail extraction")
                assertTrue(commands.all { it[it.indexOf("-ss") + 1] in setOf("3.000", "7.000", "4.750") },
                    "scene midpoints or first source-range midpoints only, never a block's gap")
            } finally { scope.cancel() }
        }
}