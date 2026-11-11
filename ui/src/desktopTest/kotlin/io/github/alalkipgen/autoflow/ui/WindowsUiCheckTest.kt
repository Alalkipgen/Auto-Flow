package io.github.alalkipgen.autoflow.ui

import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardGenerator
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardService
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.ui.projects.projectFolderName
import org.koin.dsl.module
import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.di.uiModule
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.shell.App
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Checks that need a real desktop OS. The Windows CI job sets `AUTOFLOW_EXPECT_MYANMAR_FONT=1` (the font check must
 * pass) and `AUTOFLOW_SCREENSHOTS_DIR` (PNGs uploaded as a workflow artifact), so the UI can be reviewed without a PC.
 */
@OptIn(ExperimentalTestApi::class)
class WindowsUiCheckTest {
    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun systemFontFallbackCoversMyanmar() {
        val face = FontMgr.default.matchFamilyStyleCharacter(null, FontStyle.NORMAL, arrayOf("my"), 'က'.code)
        println("Myanmar fallback font: ${face?.familyName ?: "none"}")
        if (System.getenv("AUTOFLOW_EXPECT_MYANMAR_FONT") == "1") assertNotNull(face, "No system font covers Myanmar (U+1000)")
    }

    @Test
    fun screenshotsOfTheMainScreens() {
        val dir = System.getenv("AUTOFLOW_SCREENSHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        runComposeUiTest {
            val fs = InMemoryFileSystem()
            val koin = startKoin { modules(fakePlatformModule(fs), uiModule, aiModule) }.koin
            val store = koin.get<ProjectStore>()
            store.create("D:/Videos/မိုးရေစက်.autoflow", "မိုးရေစက် (Rain drops)", Mode.MUSIC_VIDEO)
            store.create("D:/Videos/ကျွန်တော့်ညများ.autoflow", "ကျွန်တော် မိုးရွာနေတဲ့ ညမှာ", Mode.MUSIC_VIDEO)
            store.create("E:/USB/Gone.autoflow", "Gone", Mode.MUSIC_VIDEO)
            val dashboard = koin.get<DashboardModel>()
            listOf("E:/USB/Gone.autoflow", "D:/Videos/ကျွန်တော့်ညများ.autoflow", "D:/Videos/မိုးရေစက်.autoflow").forEach(dashboard::open)
            fs.atomicMove("E:/USB/Gone.autoflow/project.json", "E:/moved.json")
            koin.get<CurrentProject>().set(null)
            setContent { App() }
            save(dir, "dashboard")
            onNode(hasText("Settings") and isSelectable()).performClick()
            save(dir, "settings")
            // A project with a storyboard: sidebar locks, stepper and Burmese cards.
            dashboard.open("D:/Videos/မိုးရေစက်.autoflow")
            val session = koin.get<ProjectSession>()
            val open = session.open!!.project
            session.save(open.copy(settings = open.settings.copy(durationMs = 30_000)))
            session.writeLyrics("မိုးတွေ ရွာနေတယ်\nမင်းကို သတိရနေတယ်")
            runBlocking { StoryboardService(session, StoryboardGenerator(FakeTextProvider())).generateAll() }
            onNode(hasText("Storyboard") and isSelectable()).performClick()
            save(dir, "storyboard")
        }
    }

    /**
     * The same screens at a Redmi Note 13 Pro's 412 dp width (DESIGN.md §4), drawn at 2× so the PNGs read like the
     * phone; projects live in a projects home as on Android (D-27).
     */
    @Test
    fun phoneScreenshots() {
        val dir = System.getenv("AUTOFLOW_SCREENSHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        runDesktopComposeUiTest(width = 824, height = 1830) {
            val koin = startKoin {
                allowOverride(true)
                modules(
                    fakePlatformModule(InMemoryFileSystem()),
                    module { single { AppDirs("/data/app", projectsHome = "/data/app/projects") } },
                    uiModule,
                    aiModule,
                )
            }.koin
            val dashboard = koin.get<DashboardModel>()
            val home = koin.get<AppDirs>().projectsHome!!
            dashboard.createMusicVideo(home, "ကျွန်တော် မိုးရွာနေတဲ့ ညမှာ")
            dashboard.createMusicVideo(home, "မိုးရေစက် (Rain drops)")
            koin.get<CurrentProject>().set(null)
            setContent { CompositionLocalProvider(LocalDensity provides Density(2f)) { App() } }
            save(dir, "phone-dashboard")
            onNodeWithText("New Music Video").performClick()
            save(dir, "phone-new-project")
            onNode(hasText("More") and isSelectable()).performClick()
            save(dir, "phone-more")
            onNodeWithText("Settings").performClick()
            save(dir, "phone-settings")
            dashboard.open(home + "/" + projectFolderName("မိုးရေစက် (Rain drops)"))
            val session = koin.get<ProjectSession>()
            val open = session.open!!.project
            session.save(open.copy(settings = open.settings.copy(durationMs = 30_000)))
            session.writeLyrics("မိုးတွေ ရွာနေတယ်\nမင်းကို သတိရနေတယ်")
            onNode(hasText("More") and isSelectable()).performClick()
            onNodeWithText("Input").performClick()
            save(dir, "phone-input")
            runBlocking { StoryboardService(session, StoryboardGenerator(FakeTextProvider())).generateAll() }
            onNode(hasText("Storyboard") and isSelectable()).performClick()
            save(dir, "phone-storyboard")
        }
    }

    private fun ComposeUiTest.save(dir: File, name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }
}
