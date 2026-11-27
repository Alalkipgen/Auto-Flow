package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.recap.RecapScene
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
class StudioStoryLayoutTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object {
        private val fixture by lazy {
            val f = QaRecapFixture.create(Files.createTempDirectory("uiv3-story").toFile())
            f.copy(project = (ProjectStore(JvmFs()).save(f.dir.path, f.project.copy(recap = f.project.recap!!.copy(
                scenes = listOf(RecapScene(2_000, 4_000, true), RecapScene(6_000, 8_000, true))))) as CoreResult.Ok).value)
        }
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}/bundled={3}")
        fun cases() = StudioVoiceLayoutTest.cases()
    }
    @Test fun nativeStoryOptionsEditorsVoiceConfirmationAndBackUseVisibleControlsWithoutWriting() =
        runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
            fun bytes() = fixture.dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(fixture.dir).path to it.readBytes().toList() }
            val before = bytes()
            val fs = JvmFs(); val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, fixture.project)) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val model = RecapModel(ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs,
                File(fixture.dir.parentFile, "index.json").path), current), fs, null, null, scope)
            var back = 0; var next = 0; var classic = 0
            try {
                setContent { CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) {
                        StudioRecapScreen(model, RecapStep.STORY, width.toFloat(), {}, RecapNavigation({ back++ }, { next++ }, { classic++ }))
                    }
                } }
                fun click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "story").apply { mkdirs() }
                fun capture(state: String) {
                    assertEquals(emptyList(), textFitProblems(width, font))
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "04-story-$state-$width-${language.code}-$font-$bundled.png"))
                }
                assertProjectPanesAndStickyActions(width, "recap-next", "recap-back")
                onNodeWithTag("recap-next").assertIsDisplayed().assertIsEnabled()
                repeat(5) { onNodeWithTag("progress-segment-$it").assertExists() }
                onNodeWithTag(if (width >= 840) "project-three-panes" else "project-single-pane").assertExists()
                val title = onNodeWithText(UiText.V3_BLOCK.text(language, "n" to 1), useUnmergedTree = true)
                title.assertIsDisplayed()
                val titleBounds = title.getUnclippedBoundsInRoot()
                val listBounds = onNodeWithTag(if (width >= 840) "story-list" else "story-content").getUnclippedBoundsInRoot()
                assertTrue(titleBounds.top >= listBounds.top && titleBounds.bottom <= listBounds.bottom,
                    "first block title must fit before any scroll, including OS fallback/font2")
                capture("list")
                onNodeWithContentDescription(My.breakSyllables(UiText.V3_STORY_OPTIONS.text(language))).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                onNodeWithText(My.breakSyllables(UiText.V3_STORY_WRITING_HINT.text(language))).performScrollTo().assertIsDisplayed()
                onNodeWithText(UiText.REGISTER_WRITTEN.text(language)).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                onNodeWithText(UiText.REGISTER_WRITTEN.text(language))
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "selected"))
                capture("options")
                onNodeWithText(UiText.WRITE_MYSELF.text(language)).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                onNodeWithText(UiText.SCRIPT_MANUAL_CONFIRM.text(language)).performScrollTo().assertIsDisplayed()
                onNodeWithText(UiText.BACK.text(language), useUnmergedTree = true).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                onNodeWithContentDescription(My.breakSyllables(UiText.V3_STORY_OPTIONS.text(language))).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                click("block-select-0")
                onNodeWithTag("block-script-edit").performScrollTo().assertIsDisplayed()
                onNodeWithTag("story-content").performMouseInput { scroll(-20_000f) }
                waitForIdle()
                capture("detail")
                click("block-script-edit")
                onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("block-script-input"))).performTextReplacement("unsaved Story draft")
                onNodeWithTag("block-script-cancel").assertIsDisplayed().performMouseInput { click() }
                click("block-clips-edit")
                onNodeWithTag("block-clips-save").assertIsDisplayed()
                onNodeWithTag("block-clips-discard").assertIsDisplayed().performMouseInput { click() }
                click("voice-block-request")
                onNodeWithTag("voice-block-confirm-text").performScrollTo().assertIsDisplayed().assertTextEquals("FIRST")
                onNodeWithTag("voice-block-cancel").assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("project-back").assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("voice-detail-number").assertDoesNotExist()
                assertEquals(0, back, "detail Back must return to the list, not Source")
                onNodeWithTag("recap-next").assertIsDisplayed().performMouseInput { click() }; assertEquals(1, next)
                onNodeWithTag("project-back").assertIsDisplayed().performMouseInput { click() }; assertEquals(1, back)
                onNodeWithTag("project-overflow").assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("classic-recap").assertIsDisplayed().performMouseInput { click() }; assertEquals(1, classic)
                assertEquals(before, bytes(), "browsing, generation cancel and editor cancel must preserve every project file")
            } finally { scope.cancel() }
        }
}