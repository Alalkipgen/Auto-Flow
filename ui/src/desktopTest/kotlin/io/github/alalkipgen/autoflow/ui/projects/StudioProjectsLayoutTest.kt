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
            fun section(key: UiText) = onNodeWithContentDescription(key.text(language))
                .performScrollTo().assertIsDisplayed().performMouseInput { moveTo(center); click(); exit() }
            section(UiText.V3_PROJECT_CREATE)
            onNodeWithText(UiText.NEW_MOVIE_RECAP.text(language)).assertDoesNotExist()
            section(UiText.V3_PROJECT_CREATE)
            clickText(UiText.NEW_MOVIE_RECAP.text(language))
            onNodeWithText(UiText.RIGHTS_TICK.text(language)).performScrollTo().assertIsDisplayed()
            onNodeWithText(UiText.CREATE.text(language)).performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            onNodeWithContentDescription(UiText.PROJECT_NAME.text(language)).performScrollTo()
                .assertIsDisplayed().performTextInput("QA draft")
            section(UiText.V3_PROJECT_CREATE)
            onNodeWithContentDescription(UiText.PROJECT_NAME.text(language)).performScrollTo().assertTextContains("QA draft")
            section(UiText.V3_PROJECT_CREATE)
            onNodeWithContentDescription(UiText.PROJECT_NAME.text(language)).performScrollTo().assertTextContains("QA draft")
            clickText(UiText.CANCEL.text(language))
            assertNull(current.state.value)
            section(UiText.HOME_RECENT)
            onNodeWithText(f.project.name).assertDoesNotExist()
            section(UiText.HOME_RECENT)
            val row = onNodeWithText(f.project.name).performScrollTo().assertIsDisplayed()
            assertEquals(emptyList(), textFitProblems(width, font))
            val images = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "projects").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png",
                File(images, "projects-$width-${language.code}-$font-$bundled.png"))
            row.performTouchInput { longClick() }
            onNodeWithText(UiText.REMOVE_PROJECT_QUESTION.text(language, "name" to f.project.name)).assertIsDisplayed()
            onNodeWithText(UiText.CANCEL.text(language)).assertIsDisplayed().performMouseInput { moveTo(center); click(); exit() }
            clickText(f.project.name)
            assertEquals(1, opened); assertEquals(f.dir.path, current.state.value!!.dir)
            onNodeWithText(f.project.name).performScrollTo().assertIsDisplayed().performTouchInput { longClick() }
            onNodeWithText(UiText.REMOVE.text(language)).assertIsDisplayed().performMouseInput { moveTo(center); click(); exit() }
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