package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
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
class StudioVoiceDetailLayoutTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object {
        private val fixture by lazy {
            val f = QaRecapFixture.create(Files.createTempDirectory("uiv3-detail-layout").toFile())
            val r = f.project.recap!!
            val history = r.takes.last().copy(block = f.story.blocks.first().id, spokenHash = "old-spoken-text")
            f.copy(project = (ProjectStore(JvmFs()).save(f.dir.path, f.project.copy(recap = r.copy(takes = r.takes + history))) as CoreResult.Ok).value)
        }
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}/bundled={3}") fun cases() = StudioVoiceLayoutTest.cases()
    }
    @Test fun navigatorHistoryAndScopedConfirmationUseRealContainerScrollClicksAndNeverSaveOnBrowseCancel() = runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
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
            fun click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            fun number(n: Int) = onNodeWithTag("voice-detail-number").performScrollTo().assertIsDisplayed()
                .assertTextEquals(UiText.V3_BLOCK_OF.text(language, "n" to n, "total" to 2))
            click("voice-open-0"); number(1)
            assertProjectPanesAndStickyActions(width, "voice-block-request")
            onNodeWithTag("voice-detail-previous").assertIsNotEnabled()
            onNodeWithTag("voice-detail-source").assertTextEquals(UiText.V3_SOURCE_RANGES.text(language, "ranges" to "0:02–0:04 · 0:06–0:08"))
            onNodeWithTag("voice-detail-output").assertTextEquals(UiText.V3_OUTPUT_RANGE.text(language, "range" to "0:00–0:04"))
            onNodeWithTag("voice-block-request").assertIsDisplayed().assertIsEnabled()
            assertEquals(emptyList(), textFitProblems(width, font))
            val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "voice-detail").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "04-detail-$width-${language.code}-$font-$bundled.png"))
            click("voice-detail-next"); number(2)
            onNodeWithTag("voice-detail-next").assertIsNotEnabled()
            onNodeWithTag("timeline-time").performScrollTo().assertIsDisplayed().assertTextEquals(UiText.V3_OUTPUT_POSITION.text(language, "position" to "0:04", "duration" to "0:05"))
            assertEquals(4_000L, model.previewSource.currentPosition())
            click("voice-detail-previous"); number(1)
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("voice-take-1").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("voice-take-1").performScrollTo().assertIsDisplayed()
            onNodeWithTag("voice-take-2").assertDoesNotExist()
            onNodeWithContentDescription(UiText.V3_TAKE_HISTORY.text(language, "n" to 1)).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("voice-take-2").performScrollTo().assertIsDisplayed()
            onNodeWithTag("status-SCRIPT_CHANGED").performScrollTo().assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, UiText.V3_SCRIPT_CHANGED.text(language)))
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "04-takes-$width-${language.code}-$font-$bundled.png"))
            onNodeWithTag("voice-block-request").assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("voice-block-confirm-text").performScrollTo().assertIsDisplayed().assertTextEquals("FIRST")
            onNodeWithTag("voice-block-confirm").assertIsDisplayed().assertIsEnabled()
            onNodeWithTag("voice-block-cancel").assertIsDisplayed()
            assertEquals(emptyList(), textFitProblems(width, font))
            ImageIO.write(onNode(isRoot() and hasAnyDescendant(hasTestTag("voice-block-confirm"))).captureToImage().toAwtImage(),
                "png", File(dir, "04-confirm-$width-${language.code}-$font-$bundled.png"))
            onNodeWithTag("voice-block-cancel").performMouseInput { click() }
            onNodeWithTag("voice-block-confirm").assertDoesNotExist()
            onNodeWithTag("project-back").assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("voice-remaining").assertIsDisplayed()
            assertContentEquals(before, File(fixture.dir, "project.json").readBytes())
        } finally { scope.cancel() }
    }
}
