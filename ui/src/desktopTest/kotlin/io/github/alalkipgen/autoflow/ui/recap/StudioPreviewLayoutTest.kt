package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class StudioPreviewLayoutTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object {
        private val fixture by lazy { QaRecapFixture.create(Files.createTempDirectory("uiv3-preview-layout").toFile()) }
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}/bundled={3}")
        fun cases() = StudioVoiceLayoutTest.cases()
    }
    @Test fun selectedBlockOutputScrubberDiagnosticsAndExactFrameAreReachableWithoutWrites() = runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
        val before = File(fixture.dir, "project.json").readBytes()
        val fs = JvmFs(); val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, fixture.project)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = RecapModel(ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, File(fixture.dir.parentFile, "index.json").path), current), fs, null, null, scope)
        try {
            setContent { CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                LocalUiLanguage provides language, LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) {
                    StudioRecapScreen(model, RecapStep.VOICE, width.toFloat(), {}, RecapNavigation({}, {}, {}))
                }
            } }
            onNodeWithTag("voice-open-1").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("timeline-time").performScrollTo().assertIsDisplayed()
                .assertTextEquals(UiText.V3_OUTPUT_POSITION.text(language, "position" to "0:04", "duration" to "0:05"))
            assertEquals(4_000L, model.previewSource.currentPosition(), "stable block B starts at its output interval, not source zero")
            onNodeWithTag("timeline-position").assertDoesNotExist()
            fun scrub(ms: Long) {
                val node = onNodeWithTag("timeline-scrubber").performScrollTo().assertIsDisplayed()
                val w = node.fetchSemanticsNode().size.width
                node.performMouseInput { click(Offset(12f + (w - 24) * ms / 5499f, 24f)) }
                waitForIdle(); assertTrue(kotlin.math.abs(model.previewSource.currentPosition()!! - ms) <= 2,
                    "pointer scrub requested=$ms actual=${model.previewSource.currentPosition()} width=$w bounds=${node.fetchSemanticsNode().boundsInRoot}")
            }
            scrub(500)
            if (width >= 840) {
                onNodeWithContentDescription(UiText.V3_DIAGNOSTICS.text(language)).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("timeline-caption").performScrollTo().assertIsDisplayed().assertTextEquals("FIRST")
                scrub(4500)
                onNodeWithTag("timeline-caption").performScrollTo().assertIsDisplayed().assertTextEquals("SECOND")
                onNodeWithContentDescription(UiText.V3_DIAGNOSTICS.text(language)).performScrollTo().performMouseInput { click() }
            } else {
                // Group 2 D §2.2: on a phone the readouts open from ⋮ Diagnostics in a glass dialog, closed by its Close button.
                fun diagnostics(caption: String) {
                    onNodeWithTag("project-overflow").assertIsDisplayed().performMouseInput { click() }
                    onNodeWithTag("recap-diagnostics").assertIsDisplayed().assertIsEnabled().performMouseInput { click() }
                    onNodeWithTag("timeline-diagnostics").assertIsDisplayed()
                    onNodeWithTag("timeline-caption").performScrollTo().assertIsDisplayed().assertTextEquals(caption)
                    onNodeWithTag("recap-diagnostics-close").performScrollTo().assertIsDisplayed().performMouseInput { click() }
                    waitForIdle(); onNodeWithTag("timeline-diagnostics").assertDoesNotExist()
                }
                diagnostics("FIRST")
                scrub(4500)
                diagnostics("SECOND")
                onNodeWithTag("timeline-diagnostics").assertDoesNotExist()
            }
            onNodeWithTag("timeline-position").assertDoesNotExist()
            onNodeWithTag("timeline-play").performScrollTo().assertIsDisplayed()
            assertEquals(emptyList(), textFitProblems(width, font))
            onNodeWithTag("timeline-time").performScrollTo().assertIsDisplayed()
            onNodeWithTag("timeline-scrubber").assertIsDisplayed()
            val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "preview-controls").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "04-preview-$width-${language.code}-$font-$bundled.png"))
            // Group 2 A §1: on a phone the Exact frame is the top card's frame button.
            (if (width >= 840) onNodeWithText(UiText.EXACT_FRAME.text(language))
                else onNodeWithTag("timeline-exact").assert(hasContentDescription(UiText.EXACT_FRAME.text(language))))
                .performScrollTo().assertIsDisplayed().performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("ffmpeg was not found", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("ffmpeg was not found", substring = true).performScrollTo().assertIsDisplayed()
            assertContentEquals(before, File(fixture.dir, "project.json").readBytes())
        } finally { scope.cancel() }
    }
}