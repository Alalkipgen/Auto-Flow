package io.github.alalkipgen.autoflow.ui.home

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.jobs.*
import io.github.alalkipgen.autoflow.ui.media.thumbSource
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.recap.QaRecapFixture
import io.github.alalkipgen.autoflow.ui.settings.*
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class HomeScreenTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object {
        private val fixture by lazy { QaRecapFixture.create(Files.createTempDirectory("uiv3-home").toFile()) }
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}/bundled={3}")
        fun cases() = listOf(412, 840).flatMap { w -> listOf(1f, 1.3f, 2f).flatMap { f ->
            UiLanguage.entries.flatMap { l -> listOf(true, false).map { b -> arrayOf<Any>(w, f, l, b) } }
        } } + listOf(arrayOf<Any>(360, 2f, UiLanguage.BURMESE, true), arrayOf<Any>(1440, 1f, UiLanguage.ENGLISH, true))
    }
    @Test fun realHomeActionsRemainReachableWithoutMutatingTheFixture() = runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
        val before = File(fixture.dir, "project.json").readBytes()
        val index = ProjectIndex(JvmFs(), File(fixture.dir.parentFile, "index-$width-$font-${language.code}-$bundled.json").path)
        index.record(fixture.dir.path, fixture.project)
        val rows = jobRows(null, null, null, listOf(AiJob("qa-job", AiJobKind.TTS_ALL, fixture.dir.path,
            listOf(fixture.story.blocks.last().id), state = AiJobState.RUNNING)), language)
        var opened: String? = null; var recap = 0; var mv = 0; var settings = 0; var jobs = 0; var projects = 0
        setContent {
            CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                LocalUiLanguage provides language, LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                StudioV2Shell(StudioV2Dest.HOME, {}, jobsBadgeValue(rows), width.toFloat()) {
                    HomeScreen(onNewRecap = { recap++ }, onNewMusicVideo = { mv++ }, onOpenSettings = { settings++ },
                        recent = index.entries.value, onOpenProject = { opened = it }, jobs = rows, onViewJobs = { jobs++ },
                        onOpenProjects = { projects++ }, thumbSource = { dir -> ProjectStore(JvmFs()).thumbSource(dir) })
                }
            }
        }
        onNodeWithTag("home-settings").assertIsDisplayed().performMouseInput { click() }
        assertEquals(1, settings)
        onNodeWithTag("recent-${fixture.project.name}").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        assertEquals(fixture.dir.path, opened)
        onNodeWithTag("home-mode-MOVIE_RECAP").performScrollTo().assertIsDisplayed()
        // The thumbnail placeholder (no cache in tests) carries the recorded duration of the fixture movie (10 s).
        onNode(hasText("0:10") and hasAnyAncestor(hasTestTag("home-continue"))).performScrollTo().assertIsDisplayed()
        onNodeWithTag("home-all-projects").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        assertEquals(1, projects)
        onNodeWithTag("home-step-by-step").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        onNodeWithTag("home-new-recap").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        onNodeWithTag("home-new-mv").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        assertEquals(2, recap); assertEquals(1, mv)
        listOf("home-dubbing", "home-voice-tool", "home-subtitles-tool").forEach {
            onNodeWithTag(it).performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        }
        onNodeWithTag("home-view-jobs").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        assertEquals(1, jobs)
        onNodeWithTag("home-job-0").assertExists()
        onNodeWithTag("home-quick-start").performScrollTo().assertIsDisplayed()
        assertTrue(onNodeWithTag("home-quick-start").fetchSemanticsNode().boundsInRoot.height >= 48)
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))
        assertContentEquals(before, File(fixture.dir, "project.json").readBytes())
        onNodeWithTag("home-header").performScrollTo().assertIsDisplayed()
        val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "home").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "01-home-$width-${language.code}-$font-$bundled.png"))
    }
}

@OptIn(ExperimentalTestApi::class)
class HomeFlowTest {
    @Test fun wizardCreatesARealProjectOnlyAfterTheFinalVisibleConfirm() = runDesktopComposeUiTest(width = 412, height = 760) {
        val fs = InMemoryFileSystem(); val current = CurrentProject(); val store = ProjectStore(fs)
        val model = DashboardModel(fs, store, ProjectIndex(fs, "/app/index.json"), SettingsStore(fs, "/app/settings.json"),
            FakePicker(), current, projectsHome = "/projects")
        setContent { HomeScreen(jobs = emptyList(), onQuickRecapStart = { target, style -> assertTrue(model.createQuickRecap(target, style)) }) }
        onNodeWithTag("home-continue").assertDoesNotExist(); onNodeWithTag("home-running").assertDoesNotExist()
        onNodeWithTag("home-quick-start").assertIsDisplayed().performMouseInput { click() }
        onNodeWithText(UiText.MINUTES.text(UiLanguage.ENGLISH, "n" to 3)).performScrollTo().assertIsDisplayed().performMouseInput { click() }
        onNodeWithTag("quick-next-style").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        onNodeWithText(UiText.STYLE_COMMENTARY.en).performScrollTo().assertIsDisplayed().performMouseInput { click() }
        onNodeWithTag("quick-confirm").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        assertNull(current.state.value)
        onNodeWithTag("quick-create").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        val opened = current.state.value!!
        val saved = (store.open(opened.dir) as CoreResult.Ok).value
        assertEquals(Mode.MOVIE_RECAP, saved.mode)
        val recap = saved.recap!!
        val quick = io.github.alalkipgen.autoflow.core.model.CoreJson.decodeFromJsonElement(QuickRecap.serializer(), recap.quick!!)
        assertEquals(180_000L, recap.targetMs); assertEquals(RecapStyle.COMMENTARY, quick.style)
        assertFalse(recap.storyApproved)
    }
    @Test fun controlledCreateFormUsesTheExistingSaveAndCancelFlow() = runDesktopComposeUiTest(width = 360, height = 760) {
        val fs = InMemoryFileSystem(); val current = CurrentProject()
        val model = DashboardModel(fs, ProjectStore(fs), ProjectIndex(fs, "/app/index.json"), SettingsStore(fs, "/app/settings.json"),
            FakePicker(), current, projectsHome = "/projects")
        var mode by mutableStateOf<Mode?>(Mode.MOVIE_RECAP)
        setContent { CompositionLocalProvider(LocalCompact provides true) {
            DashboardScreen(model, {}, createMode = mode, onCreateModeChange = { mode = it })
        } }
        onNodeWithText(UiText.RIGHTS_TICK.en).performScrollTo().assertIsDisplayed()
        onNodeWithText(UiText.CANCEL.en).performScrollTo().assertIsDisplayed().performMouseInput { click() }
        assertNull(mode); assertNull(current.state.value); assertTrue(model.state.value.rows.isEmpty())
    }
}
