package io.github.alalkipgen.autoflow.ui.projects

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.*
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.di.uiModule
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.recap.*
import io.github.alalkipgen.autoflow.ui.settings.*
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

/** The routed Dashboard, not the unused ProjectsScreen prototype. No project writes on browse/remove. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class StudioProjectsLayoutTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object {
        private val fixture by lazy { QaRecapFixture.create(Files.createTempDirectory("projects-layout").toFile()) }
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}/bundled={3}")
        fun cases() = StudioVoiceLayoutTest.cases()
    }

    @Test fun realProjectSectionsCreateCancelOpenAndConfirmedIndexRemovalUseVisiblePointerControls() =
        runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
            val f = fixture; val fs = JvmFs(); val before = StudioThumbnailsLayoutTest.bytes(f.dir)
            val index = ProjectIndex(fs, File(f.dir.parentFile, "index-$width-$font-${language.code}-$bundled.json").path)
            index.record(f.dir.path, f.project)
            val current = CurrentProject()
            val model = DashboardModel(fs, ProjectStore(fs), index,
                SettingsStore(fs, File(f.dir.parentFile, "settings.json").path), FakePicker(), current)
            var opened = 0
            setContent {
                CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language,
                    LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) {
                        DashboardScreen(model, { opened++ })
                    }
                }
            }
            fun clickText(text: String) = onNodeWithText(text).performScrollTo().assertIsDisplayed()
                .performMouseInput { moveTo(center); click(); exit() }
            // "+" in the header opens / closes the create card (closed while the list has projects).
            fun toggleCreate() = onNodeWithTag("projects-new").assertContentDescriptionEquals(UiText.V3_PROJECT_CREATE.text(language))
                .performScrollTo().assertIsDisplayed().performMouseInput { moveTo(center); click(); exit() }
            onNodeWithTag("projects-count").assertTextEquals(My.breakSyllables(UiText.MANY_PROJECTS.text(language, "projects" to 1)))
            onNodeWithText(UiText.NEW_MOVIE_RECAP.text(language)).assertDoesNotExist()
            toggleCreate()
            onNodeWithTag("projects-create").assertIsDisplayed()
            clickText(UiText.NEW_MOVIE_RECAP.text(language))
            onNodeWithText(UiText.RIGHTS_TICK.text(language)).performScrollTo().assertIsDisplayed()
            onNodeWithText(UiText.CREATE.text(language)).performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            onNodeWithContentDescription(UiText.PROJECT_NAME.text(language)).performScrollTo()
                .assertIsDisplayed().performTextInput("QA draft")
            toggleCreate()
            onNodeWithTag("projects-create").assertDoesNotExist()
            onNodeWithContentDescription(UiText.PROJECT_NAME.text(language)).performScrollTo().assertTextContains("QA draft")
            toggleCreate()
            onNodeWithContentDescription(UiText.PROJECT_NAME.text(language)).performScrollTo().assertTextContains("QA draft")
            clickText(UiText.CANCEL.text(language))
            assertNull(current.state.value)
            // Filter chips All + the one mode; the row: thumbnail, mode chip, ⋮; no status chip without a job.
            onNode(hasText(UiText.PROJECTS_FILTER_ALL.text(language)) and hasAnyAncestor(hasTestTag("projects-filters"))).assertIsDisplayed()
            onNode(hasText(UiText.MOVIE_RECAP.text(language)) and hasAnyAncestor(hasTestTag("projects-filters"))).assertIsDisplayed()
            // 1440: the D2 card grid (click selects, the detail pane opens); else the phone rows (tap opens).
            val wide = width >= 1440
            val rowTag = if (wide) "project-card-${f.project.name}" else "project-row-${f.project.name}"
            onNode(hasTestTag("media-thumb") and hasAnyAncestor(hasTestTag(rowTag))).assertIsDisplayed()
            onNodeWithTag("project-mode-MOVIE_RECAP").assertIsDisplayed()
            onNode(hasTestTag("status-RUNNING") and hasAnyAncestor(hasTestTag(rowTag))).assertDoesNotExist()
            val row = onNodeWithText(f.project.name).performScrollTo().assertIsDisplayed()
            assertEquals(emptyList(), textFitProblems(width, font))
            val images = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "projects").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png",
                File(images, "projects-$width-${language.code}-$font-$bundled.png"))
            row.performTouchInput { longClick() }
            // The glass dialog scrolls when it is taller than the window (font 2.0 with the system Burmese font).
            onNodeWithTag("project-remove-action").performScrollTo().assertIsDisplayed().performMouseInput { moveTo(center); click(); exit() }
            onNodeWithText(My.breakSyllables(UiText.REMOVE_PROJECT_QUESTION.text(language, "name" to f.project.name))).assertIsDisplayed()
            onNodeWithTag("remove-project-cancel").performScrollTo().assertIsDisplayed().performMouseInput { moveTo(center); click(); exit() }
            // Right-click opens the same options.
            row.performMouseInput { rightClick() }
            onNodeWithTag("project-remove-action").performScrollTo().assertIsDisplayed()
            onNodeWithTag("project-actions-cancel").performScrollTo().assertIsDisplayed().performMouseInput { moveTo(center); click(); exit() }
            clickText(f.project.name)
            if (wide) {
                mainClock.advanceTimeBy(1_000)
                assertEquals(0, opened)
                onNodeWithTag("project-detail-pane").assertIsDisplayed()
                onNodeWithTag("project-detail-open").performScrollTo().assertIsDisplayed().performMouseInput { moveTo(center); click(); exit() }
            }
            assertEquals(1, opened); assertEquals(f.dir.path, current.state.value!!.dir)
            onNodeWithTag("project-more-${f.project.name}").performScrollTo().assertIsDisplayed()
                .performMouseInput { moveTo(center); click(); exit() }
            onNodeWithTag("project-remove-action").performScrollTo().assertIsDisplayed().performMouseInput { moveTo(center); click(); exit() }
            onNodeWithTag("remove-project-confirm").performScrollTo().assertIsDisplayed().performMouseInput { moveTo(center); click(); exit() }
            onNodeWithText(f.project.name).assertDoesNotExist()
            assertTrue(index.entries.value.isEmpty(), "Remove changes only the rebuildable index")
            assertEquals(before, StudioThumbnailsLayoutTest.bytes(f.dir), "browse/open/cancel/remove never change project/artifacts")
        }
}

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class StudioProjectsRoutingTest {
    @BeforeTest fun dispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }
    @Test fun actualAppProjectsDestinationMountsDashboardSectionsAndRetainsNavigation() =
        runDesktopComposeUiTest(width = 412, height = 760) {
            val koin = startKoin { modules(fakePlatformModule(InMemoryFileSystem()), uiModule, aiModule) }.koin
            try {
                koin.get<SettingsStore>().update { it.copy(featureOverrides = mapOf("studioV2" to true)) }
                setContent { TestApp() }
                onNodeWithContentDescription(UiText.PROJECTS.en).assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("projects-heading").assertIsDisplayed()
                onNodeWithTag("projects-create").assertExists()
                onNodeWithTag("projects-recent").assertExists()
                onNodeWithText(UiText.NEW_MUSIC_VIDEO.en).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                onNodeWithContentDescription(UiText.PROJECT_NAME.en).performScrollTo().assertIsDisplayed().performTextInput("QA Music")
                onNodeWithText(UiText.CANCEL.en).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                assertNull(koin.get<CurrentProject>().state.value)
                onNodeWithContentDescription(UiText.HOME.en).assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("home-header").assertIsDisplayed()
            } finally { stopKoin() }
        }
}