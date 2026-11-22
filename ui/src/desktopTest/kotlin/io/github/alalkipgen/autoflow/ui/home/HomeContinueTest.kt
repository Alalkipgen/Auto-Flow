package io.github.alalkipgen.autoflow.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.di.uiModule
import io.github.alalkipgen.autoflow.ui.fakePlatformModule
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.shell.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class HomeContinueCacheTest {
    @Test fun disposableHintsAreIsolatedAndRejectBadIdentityModeAndTokens() {
        val fs = InMemoryFileSystem()
        val cache = HomeContinueCache(fs, "/app")
        val store = ProjectStore(fs)
        val a = OpenProject("/a", (store.create("/a", "A", Mode.MOVIE_RECAP) as CoreResult.Ok).value)
        val b = OpenProject("/b", (store.create("/b", "B", Mode.MUSIC_VIDEO) as CoreResult.Ok).value)
        val seenA = checkNotNull(HomeResume.from(a, HomeStep.RECAP_EXPORT.nav()))
        val seenB = checkNotNull(HomeResume.from(b, HomeStep.SHOT_LIST.nav()))
        assertNull(cache.read(a.dir, a.project.mode))
        cache.write(seenA); cache.write(seenB)
        assertEquals(seenA, HomeContinueCache(fs, "/app").read(a.dir, a.project.mode))
        assertEquals(seenB, cache.read(b.dir, b.project.mode))
        assertFalse(seenA.belongsTo(a.copy(project = a.project.copy(id = "replacement"))))
        assertFalse(seenA.belongsTo(b))
        assertNull(cache.read(a.dir, b.project.mode))
        val root = "/app/cache/home-continue-v1"
        for (file in fs.list(root)) fs.writeDurably("$root/$file", ("0".repeat(64) + " Unknown").encodeToByteArray())
        assertNull(cache.read(a.dir, a.project.mode))
        assertNull(cache.read(b.dir, b.project.mode))
        fs.failWhen = { op, path -> op == "write" && path.startsWith(root) }
        cache.write(seenA)
        assertNull(cache.read(a.dir, a.project.mode))
    }
}

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class HomeContinueRoutingTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun recapStoryReopensFromHomeAfterAColdAppGraph() = coldReopen(Mode.MOVIE_RECAP, 412)
    @Test fun musicVideoShotSheetReopensFromHomeAfterAColdAppGraph() = coldReopen(Mode.MUSIC_VIDEO, 1440)

    private fun coldReopen(mode: Mode, width: Int) = runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
        val dir = Files.createTempDirectory("uiv3-home-resume").toFile()
        val fs = JvmFs()
        val appDirs = AppDirs(File(dir, "app").invariantSeparatorsPath)
        val path = File(dir, "resume.autoflow").invariantSeparatorsPath
        val otherPath = File(dir, "other.autoflow").invariantSeparatorsPath
        val store = ProjectStore(fs)
        var project = (store.create(path, "QA Resume", mode) as CoreResult.Ok).value
        if (mode == Mode.MOVIE_RECAP) {
            val media = "source/movie.mp4"
            fs.writeDurably("$path/$media", "unused by navigation test".encodeToByteArray())
            project = (store.save(path, project.copy(recap = RecapV2(rightsConfirmed = true,
                movie = RecapMovie(media, fs.size("$path/$media"), 10_000, 320, 180, false)))) as CoreResult.Ok).value
        }
        val other = (store.create(otherPath, "Other QA Project", Mode.MUSIC_VIDEO) as CoreResult.Ok).value
        fun tree(root: String): Map<String, List<Byte>?> = File(root).walkTopDown().associate {
            it.relativeTo(File(root)).invariantSeparatorsPath to (if (it.isFile) it.readBytes().toList() else null)
        }
        val beforeA = tree(path); val beforeB = tree(otherPath)
        val index = ProjectIndex(fs, appDirs.projectsIndex)
        index.record(otherPath, other)
        index.record(path, project)
        val fakes = module { single<FileSystem> { fs }; single { appDirs } }
        fun graph() = koinApplication { modules(fakePlatformModule(), fakes, uiModule, aiModule) }
        val first = graph()
        var active by mutableStateOf<KoinApplication?>(first)
        var firstClosed = false
        val route = if (mode == Mode.MOVIE_RECAP) HomeStep.STORY else HomeStep.SHOT_LIST
        try {
            first.koin.get<SettingsStore>().update { it.copy(featureOverrides = mapOf("studioV2" to true)) }
            val settings = fs.read(appDirs.settings)
            setContent { active?.let { app -> key(app) { KoinIsolatedContext(app) { App() } } } }
            fun continueProject() {
                onNodeWithTag("home-mode-${mode.name}").performScrollTo().assertIsDisplayed()
                onNodeWithTag("recent-QA Resume").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            }
            fun home() = onNodeWithContentDescription(UiText.HOME.en).assertIsDisplayed().performMouseInput { click() }
            fun assertStep() {
                val label = if (mode == Mode.MOVIE_RECAP) "Story · Step 2/5" else UiText.SHOT_SHEET.en
                onNodeWithTag("home-continue-step").performScrollTo().assertIsDisplayed().assertTextEquals(label)
            }
            onNodeWithTag("home-continue-step").assertDoesNotExist()
            continueProject()
            if (mode == Mode.MOVIE_RECAP) onNodeWithTag("recap-next").assertIsDisplayed().performMouseInput { click() }
            else onNodeWithText(UiText.SHOT_SHEET.en).assertIsDisplayed().performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { HomeContinueCache(fs, appDirs.root).read(path, mode)?.step == route }
            home(); assertStep()
            val dirForImages = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "home-continue").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dirForImages, "01-home-$width-${mode.name}-resume.png"))
            // Koin Module holds its singleton factories; tear down the first graph before opening a second.
            runOnIdle { active = null }
            waitForIdle()
            first.close()
            firstClosed = true
            val restarted = graph()
            try {
                assertNull(restarted.koin.get<CurrentProject>().state.value)
                runOnIdle { active = restarted }
                assertStep(); continueProject()
                assertEquals(path, restarted.koin.get<CurrentProject>().state.value?.dir)
                if (mode == Mode.MOVIE_RECAP) onNodeWithText("Story · Step 2/5").assertIsDisplayed()
                else onNodeWithText(UiText.STORYBOARD_EMPTY.en).assertIsDisplayed()
                assertContentEquals(settings, fs.read(appDirs.settings))
                assertEquals(beforeA, tree(path), "opening and navigating do not write project A")
                assertEquals(beforeB, tree(otherPath), "opening A cannot change or resume project B")
                runOnIdle { active = null }
                waitForIdle()
            } finally { restarted.close() }
        } finally { if (!firstClosed) first.close() }
    }
}
