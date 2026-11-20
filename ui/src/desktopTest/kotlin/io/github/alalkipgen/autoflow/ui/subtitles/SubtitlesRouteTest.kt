package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.core.flags.FeatureFlags
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.di.uiModule
import io.github.alalkipgen.autoflow.ui.fakePlatformModule
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.shell.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily

/** S-1b in the real app graph: flag → Home tile → picker → project → Subtitles screen → Home Continue. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SubtitlesRouteTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun tileCreatesAProjectThatOpensAndContinuesFromHome() = runDesktopComposeUiTest(width = 412, height = 760) {
        val root = Files.createTempDirectory("p7-subtitles-route").toFile()
        try {
            val fs = JvmFs()
            val home = File(root, "projects").apply { mkdirs() }.invariantSeparatorsPath
            val appDirs = AppDirs(File(root, "app").invariantSeparatorsPath, home)
            val picked = File(root, "Download/Rain clip.mp4").apply { parentFile.mkdirs(); writeText("not decodable here") }
            val app = koinApplication {
                modules(fakePlatformModule(), module {
                    single<FileSystem> { fs }; single { appDirs }
                    single<FilePicker> { FakePicker(files = listOf(picked.invariantSeparatorsPath)) }
                }, uiModule, aiModule)
            }
            try {
                app.koin.get<SettingsStore>().update {
                    it.copy(featureOverrides = mapOf(FeatureFlags.STUDIO_V2 to true, FeatureFlags.TOOLS_SUBTITLES to true))
                }
                setContent { KoinIsolatedContext(app) { App() } }
                onNodeWithTag("home-subtitles-tool").performScrollTo().assertIsEnabled().performMouseInput { click() }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-notice").fetchSemanticsNodes().isNotEmpty() }
                val open = assertNotNull(app.koin.get<CurrentProject>().state.value)
                assertEquals(Mode.SUBTITLES, open.project.mode)
                assertTrue(open.project.name.startsWith("Rain clip · "), open.project.name)
                assertEquals("not decodable here", File(open.dir, "source/movie.mp4").readText())
                // No ffprobe in the test graph: the project stays, says why, and invents no facts.
                onNodeWithTag("subtitles-notice").assertTextEquals(UiText.MOVIE_UNREADABLE.en)
                onNodeWithTag("subtitles-no-video").assertIsDisplayed()
                onNodeWithTag("subtitles-source-planned").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
                val shots = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "subtitles").apply { mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots, "01-subtitles-412-unreadable.png"))
                assertEquals(listOf(Mode.SUBTITLES), app.koin.get<ProjectIndex>().entries.value.map { it.mode })

                onNodeWithContentDescription(UiText.HOME.en).assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("home-mode-SUBTITLES").performScrollTo().assertIsDisplayed()
                onNodeWithTag("home-continue-step").performScrollTo().assertTextEquals(UiText.V3_SUBTITLES.en)
                onNodeWithTag("recent-${open.project.name}").performScrollTo().performMouseInput { click() }
                onNodeWithTag("subtitles-no-video").assertIsDisplayed()
            } finally { app.close() }
        } finally { root.deleteRecursively() }
    }

    /** The facts card at the size extremes (D-22 one layout), English and Burmese, with the bundled Burmese font. */
    @Test fun videoFactsFitAtSmallAndWideSizesInBothLanguages() {
        for ((width, font) in listOf(360 to 2f, 1440 to 1f)) for (language in UiLanguage.entries) {
            runDesktopComposeUiTest(width = width, height = 760) {
                val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
                val index = ProjectIndex(fs, "/app/projects.json")
                val dir = "/p/Clip.autoflow"
                val created = (store.create(dir, "Clip", Mode.SUBTITLES) as CoreResult.Ok).value
                val project = (store.save(dir, created.copy(subtitles = SubtitlesTool(video =
                    SourceVideo("source/movie.mp4", 9, 3_725_000, 1920, 1080, 29.97, hasAudio = false)))) as CoreResult.Ok).value
                current.set(OpenProject(dir, project))
                val session = ProjectSession(fs, store, index, current)
                val model = SubtitlesModel(session, fs, DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), FakePicker(), current),
                    null, kotlinx.coroutines.MainScope(), probe = { null })
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
                        LocalUiFontFamily provides BurmeseTestFont.family) { SubtitlesScreen(model, session) }
                }
                val facts = UiText.SUBTITLES_VIDEO_FACTS.text(language, "duration" to "1:02:05", "width" to 1920, "height" to 1080,
                    "fps" to "29.97", "sound" to UiText.SUBTITLES_SOUND_NO.text(language))
                onNodeWithTag("subtitles-video-facts").assertIsDisplayed().assertTextEquals(My.breakSyllables(facts))
                onNodeWithTag("subtitles-no-video").assertDoesNotExist()
                assertEquals(emptyList(), textFitProblems(width, font))
            }
        }
    }
}
