package io.github.alalkipgen.autoflow.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardGenerator
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.di.uiModule
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.recap.QaRecapFixture
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.shell.App
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardResult
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardService
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.*

/** Brief §9: current-format projects with media and immutable artifacts, using only the routed app UI after setup. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class StudioBrowseOnlyTreeTest {
    @BeforeTest fun dispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun fullCurrentProjectTreesSurviveNarrowBrowse() = browse(412, 760)
    @Test fun fullCurrentProjectTreesSurviveWideBrowse() = browse(1440, 900)

    private fun browse(width: Int, height: Int) = runDesktopComposeUiTest(width = width, height = height) {
        val root = Files.createTempDirectory("uiv3-browse").toFile()
        val a = QaRecapFixture.create(root, "QA Browse Recap")
        val b = File(root, "QA Browse Music.autoflow")
        val bPath = b.invariantSeparatorsPath
        val fs = JvmFs()
        val dirs = AppDirs(File(root, "app").invariantSeparatorsPath)
        val store = ProjectStore(fs)
        val index = ProjectIndex(fs, dirs.projectsIndex)
        val graph = koinApplication {
            modules(fakePlatformModule(), module { single<FileSystem> { fs }; single { dirs } }, uiModule, aiModule)
        }
        try {
            // Persist the fixtures before recording the baseline. No fixture callback is used during browse.
            val initial = (store.create(bPath, "QA Browse Music", Mode.MUSIC_VIDEO,
                ProjectSettings(durationMs = 30_000, clipMs = 8_000)) as CoreResult.Ok).value
            val setup = CurrentProject().apply { set(OpenProject(bPath, initial)) }
            val session = ProjectSession(fs, store, index, setup)
            assertIs<CoreResult.Ok<Unit>>(session.writeLyrics("မိုးတွေရွာနေတယ်"))
            val generator = StoryboardService(session, StoryboardGenerator(FakeTextProvider()))
            assertEquals(StoryboardResult.Done, runBlocking { generator.generateAll() })
            val music = checkNotNull(setup.state.value).project
            assertEquals(4, music.shots.size)
            index.record(bPath, music)
            index.record(a.dir.invariantSeparatorsPath, a.project)
            graph.koin.get<SettingsStore>().update { it.copy(featureOverrides = mapOf("studioV2" to true)) }

            val beforeA = tree(a.dir)
            val beforeB = tree(b)
            assertTrue(beforeA.keys.any { it.startsWith("artifacts/") } && "source/movie.mp4" in beforeA)
            assertTrue(beforeB.keys.any { it.startsWith("artifacts/") })
            fun unchanged(screen: String) {
                waitForIdle()
                assertEquals(beforeA, tree(a.dir), "$width dp $screen changed Recap tree")
                assertEquals(beforeB, tree(b), "$width dp $screen changed Music Video tree")
                println("browse-only $width dp: $screen; Recap=${beforeA.size}, Music=${beforeB.size} tree entries unchanged")
            }
            fun click(node: SemanticsNodeInteraction) = node.assertIsDisplayed().assertIsEnabled().performMouseInput { click() }
            fun scroll(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            fun tap(tag: String) = click(onNodeWithTag(tag))
            fun nav(text: UiText) = click(onNodeWithContentDescription(text.en))
            fun heading(tag: String, screen: String) { onNodeWithTag(tag).assertIsDisplayed(); unchanged(screen) }
            // The desktop Projects grid (P9-9b) selects on click; Continue editing in the detail pane opens.
            fun openProject(name: String) {
                click(onNodeWithText(name).performScrollTo())
                if (width == 1440) { mainClock.advanceTimeBy(1_000); tap("project-detail-open") }
            }

            setContent { KoinIsolatedContext(graph) { App() } }
            heading("home-header", "Home")
            tap("home-settings"); heading("settings-heading", "Settings")
            click(onNodeWithContentDescription(UiText.V3_DIAGNOSTICS.en).performScrollTo())
            scroll("diagnostics-build"); unchanged("Settings / Diagnostics")
            tap("subscreen-back"); heading("home-header", "return to Home")

            nav(UiText.PROJECTS); heading("projects-heading", "routed Projects dashboard")
            openProject(a.project.name)
            scroll("source-movie-name"); unchanged("Recap Source")
            tap("recap-next")
            heading(if (width == 1440) "story-list" else "story-content", "Recap Story")
            tap("recap-next"); heading("voice-content", "Recap Voice list")
            click(scroll("voice-open-0")); scroll("voice-detail-number").assertTextEquals("Block 1 of 2")
            unchanged("Recap Voice block detail")
            tap("project-back"); onNodeWithTag("voice-remaining").assertIsDisplayed()
            tap("recap-next"); scroll("style-sections"); unchanged("Recap Style / Layers")
            click(scroll("style-section-CAPTIONS")); scroll("caption-controls"); unchanged("Recap Style / Captions")
            click(scroll("style-section-LOOK")); scroll("style-look"); unchanged("Recap Style / Look")
            click(scroll("style-section-AUDIO")); scroll("style-audio"); unchanged("Recap Style / Audio")
            tap("recap-next"); heading("export-preview", "Recap Export")

            nav(UiText.LIBRARY); heading("library-heading", "Library (Recap active)")
            val sections = listOf(
                UiText.V3_LIBRARY_KITS to "kits", UiText.V3_LIBRARY_VOICES to "voices",
                UiText.V3_LIBRARY_LEXICON to "lexicon", UiText.V3_LIBRARY_MUSIC to "music",
                UiText.V3_LIBRARY_FONTS to "fonts", UiText.V3_LIBRARY_PRESETS to "presets",
            )
            sections.forEach { (text, tag) ->
                click(onNode(hasText(text.en) and hasAnyAncestor(hasTestTag("library-categories"))).performScrollTo())
                scroll("library-$tag"); unchanged("Library / $tag")
            }
            nav(UiText.JOBS); heading("jobs-empty", "Jobs (no jobs launched)")

            nav(UiText.PROJECTS); nav(UiText.PROJECTS); heading("projects-heading", "routed Projects before switching to B")
            openProject(music.name)
            val shotSheet = onNodeWithText(UiText.SHOT_SHEET.en).assertIsDisplayed()
            unchanged("Music Video Input")
            click(shotSheet)
            if (width == 1440) {
                onNode(hasTestTag("project-list") and hasAnyDescendant(hasTestTag("shot-sheet-summary"))).assertIsDisplayed()
                unchanged("Music Video shot list (wide pane)")
            } else heading("shot-sheet-list", "Music Video shot list")
            click(scroll("shot-sheet-select-1")); scroll("shot-sheet-position").assertTextEquals("1 / 4")
            unchanged("Music Video shot 1")
            click(scroll("shot-sheet-next")); scroll("shot-sheet-position").assertTextEquals("2 / 4")
            unchanged("Music Video shot 2")
            nav(UiText.LIBRARY); heading("library-heading", "Library (Music Video active)")
            nav(UiText.HOME); heading("home-header", "Home after A → B")
        } finally { graph.close() }
    }

    private fun tree(root: File): Map<String, String> {
        assertTrue(root.isDirectory)
        return root.walkTopDown().onFail { _, err -> throw err }.associate { entry ->
            val path = entry.relativeTo(root).invariantSeparatorsPath
            path to if (entry.isDirectory) "directory" else MessageDigest.getInstance("SHA-256").digest(entry.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }.toSortedMap()
    }
}
