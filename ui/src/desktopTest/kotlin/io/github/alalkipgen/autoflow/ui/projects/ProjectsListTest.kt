package io.github.alalkipgen.autoflow.ui.projects

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.jobs.*
import io.github.alalkipgen.autoflow.ui.media.thumbSource
import io.github.alalkipgen.autoflow.ui.recap.QaRecapFixture
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * P9-9a Projects list (DESIGN.md §9.7, panel 2): count, "+" → create card, filter chips with counts, thumbnail rows
 * with the mode chip, a status chip only from a real job of that project, ⋮ = the existing options; strict no-clipping
 * at phone / medium widths and font 1.0 / 1.3 / 2.0. Project files stay unchanged.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class ProjectsListTest(val width: Int, val font: Float, val language: UiLanguage) {
    companion object {
        private val root by lazy { Files.createTempDirectory("uiv3-projects").toFile() }
        private val fixture by lazy { QaRecapFixture.create(File(root, "recap").apply { mkdirs() }) }
        private val music by lazy { project("Rain Song", Mode.MUSIC_VIDEO) }
        private val subs by lazy { project("Interview", Mode.SUBTITLES) }
        private fun project(name: String, mode: Mode): Pair<String, io.github.alalkipgen.autoflow.core.model.Project> {
            val dir = File(root, "$name.autoflow").path
            return dir to (ProjectStore(JvmFs()).create(dir, name, mode) as CoreResult.Ok).value
        }
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}")
        fun cases() = listOf(412).flatMap { w -> listOf(1f, 1.3f, 2f).flatMap { f -> UiLanguage.entries.map { l -> arrayOf<Any>(w, f, l) } } } +
            listOf(arrayOf<Any>(360, 2f, UiLanguage.BURMESE), arrayOf<Any>(360, 1f, UiLanguage.BURMESE),
                arrayOf<Any>(760, 1f, UiLanguage.BURMESE), arrayOf<Any>(760, 2f, UiLanguage.ENGLISH))
    }

    @Test fun rowsShowThumbnailModeAndRealJobStatusAndFiltersByMode() = runDesktopComposeUiTest(width = width, height = 760) {
        val fs = JvmFs()
        val bytes = listOf(fixture.dir.path, music.first, subs.first).associateWith { File(it, "project.json").readBytes() }
        val index = ProjectIndex(fs, File(root, "index-$width-$font-${language.code}.json").path)
        index.record(subs.first, subs.second); index.record(music.first, music.second); index.record(fixture.dir.path, fixture.project)
        val model = DashboardModel(fs, ProjectStore(fs), index, SettingsStore(fs, File(root, "settings.json").path), FakePicker(), CurrentProject())
        val jobs = jobRows(null, null, null, listOf(
            AiJob("run", AiJobKind.TTS_ALL, fixture.dir.path, listOf(fixture.story.blocks.last().id), AiJobState.RUNNING),
            AiJob("fail", AiJobKind.TRANSCRIBE, music.first, listOf("x"), AiJobState.FAILED, "QA failure"),
        ), language)
        var opened = 0
        setContent {
            CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                StudioV2Shell(StudioV2Dest.PROJECTS, {}, jobsBadgeValue(jobs), width.toFloat()) {
                    DashboardScreen(model, { opened++ }, jobs = jobs, thumbSource = { dir -> ProjectStore(JvmFs()).thumbSource(dir) })
                }
            }
        }
        fun row(name: String) = "project-row-$name"
        fun inRow(name: String, matcher: SemanticsMatcher) = onNode(matcher and hasAnyAncestor(hasTestTag(row(name))))
        fun chip(text: String): SemanticsNodeInteraction {
            onNodeWithTag("projects-filters").performScrollTo()
            return onNode(hasText(text) and hasAnyAncestor(hasTestTag("projects-filters")))
        }
        val recap = fixture.project.name
        onNodeWithTag("projects-count").assertTextEquals(My.breakSyllables(UiText.MANY_PROJECTS.text(language, "projects" to 3)))
        onNodeWithTag("projects-create").assertDoesNotExist()
        // Status chips come only from that project's jobs: running recap, failed music video, nothing for subtitles.
        inRow(recap, hasTestTag("status-RUNNING")).performScrollTo().assertIsDisplayed()
        inRow("Rain Song", hasTestTag("status-FAILED")).performScrollTo().assertIsDisplayed()
        inRow("Interview", hasTestTag("project-mode-SUBTITLES")).performScrollTo().assertIsDisplayed()
        assertTrue(onAllNodes(hasAnyAncestor(hasTestTag(row("Interview"))) and hasTestTagPrefix("status-")).fetchSemanticsNodes().isEmpty())
        // The recap thumbnail carries the recorded movie duration (10 s); thumbnails sit left of the text when it fits.
        inRow(recap, hasText("0:10")).performScrollTo().assertIsDisplayed()
        val thumb = inRow(recap, hasTestTag("media-thumb")).performScrollTo().fetchSemanticsNode()
        val title = inRow(recap, hasText(recap)).fetchSemanticsNode()
        // Screen 14 + card 12 padding each side, thumbnail 100, ⋮ 36 (its 48 dp target overflows), two 12 dp gaps.
        val beside = width - 2 * 14 - 2 * 12 - 100 - 36 - 24 >= 96 * font
        if (beside) assertTrue(thumb.positionInRoot.x + thumb.size.width <= title.positionInRoot.x, "thumbnail left of the title")
        else assertTrue(thumb.positionInRoot.y + thumb.size.height <= title.positionInRoot.y, "thumbnail above the title")
        // ⋮ opens the existing options; the target is at least 48 dp.
        val more = onNodeWithTag("project-more-Interview").performScrollTo().assertIsDisplayed()
        assertTrue(more.fetchSemanticsNode().size.height >= 48 && more.fetchSemanticsNode().size.width >= 48)
        more.performMouseInput { click() }
        onNodeWithTag("project-remove-action").assertIsDisplayed()
        onNodeWithTag("project-actions-cancel").assertIsDisplayed().performMouseInput { click() }
        onNodeWithTag("project-remove-action").assertDoesNotExist()
        // Filters: All 3 + one chip per mode; a mode chip shows only its projects.
        chip(UiText.PROJECTS_FILTER_ALL.text(language)).performScrollTo().assertIsDisplayed()
        listOf(UiText.MOVIE_RECAP, UiText.MUSIC_VIDEO, UiText.V3_SUBTITLES).forEach { chip(it.text(language)).assertExists() }
        chip(UiText.V3_DUBBING.text(language)).assertDoesNotExist()
        chip(UiText.MUSIC_VIDEO.text(language)).performScrollTo().performMouseInput { click() }
        onNodeWithTag(row("Rain Song")).assertExists(); onNodeWithTag(row(recap)).assertDoesNotExist(); onNodeWithTag(row("Interview")).assertDoesNotExist()
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))
        chip(UiText.PROJECTS_FILTER_ALL.text(language)).performScrollTo().performMouseInput { click() }
        onNodeWithTag(row(recap)).assertExists()
        // "+" opens and closes the create card.
        onNodeWithTag("projects-new").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        onNodeWithTag("projects-create").assertIsDisplayed()
        onNodeWithTag("projects-header").performScrollTo()
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))
        val images = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "projects").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(images, "list-$width-${language.code}-$font.png"))
        onNodeWithTag("projects-new").performMouseInput { click() }
        onNodeWithTag("projects-create").assertDoesNotExist()
        inRow("Interview", hasText("Interview")).performScrollTo().performMouseInput { click() }
        assertEquals(1, opened)
        bytes.forEach { (dir, b) -> assertContentEquals(b, File(dir, "project.json").readBytes(), dir) }
    }
}

private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") { node ->
    node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
}

@OptIn(ExperimentalTestApi::class)
class ProjectsEmptyAndRemovedTest {
    @Test fun withNoProjectTheCreateCardIsOpenAndThereIsNoPlus() = runDesktopComposeUiTest(width = 360, height = 760) {
        val fs = InMemoryFileSystem()
        val model = DashboardModel(fs, ProjectStore(fs), ProjectIndex(fs, "/h/index.json"), SettingsStore(fs, "/h/settings.json"),
            FakePicker(), CurrentProject(), projectsHome = "/ext/projects")
        setContent { CompositionLocalProvider(LocalCompact provides true) { DashboardScreen(model, {}) } }
        onNodeWithTag("projects-create").assertIsDisplayed()
        onNodeWithTag("projects-new").assertDoesNotExist()
        onNodeWithTag("projects-count").assertDoesNotExist()
        onNodeWithTag("projects-filters").assertDoesNotExist()
        onNodeWithText(UiText.PROJECTS_EMPTY.en).assertIsDisplayed()
    }

    @Test fun theRemovedRowCountsRemovedProjectsAndOpensTheExistingList() = runDesktopComposeUiTest(width = 360, height = 760) {
        val fs = InMemoryFileSystem(); val store = ProjectStore(fs)
        val model = DashboardModel(fs, store, ProjectIndex(fs, "/h/index.json"), SettingsStore(fs, "/h/settings.json"),
            FakePicker(), CurrentProject(), projectsHome = "/ext/projects")
        store.create("/ext/projects/A.autoflow", "A", Mode.MUSIC_VIDEO); store.create("/ext/projects/B.autoflow", "B", Mode.MOVIE_RECAP)
        setContent { CompositionLocalProvider(LocalCompact provides true) { DashboardScreen(model, {}) } }
        onNodeWithText("A").assertIsDisplayed()
        onNodeWithTag("projects-open-removed").assertDoesNotExist()
        runOnIdle { model.remove("/ext/projects/A.autoflow") }
        onNodeWithText("A").assertDoesNotExist()
        // One removed project reads "1 project" (ONE_PROJECT), not "1 projects".
        onNode(hasText(UiText.ONE_PROJECT.en) and hasAnyAncestor(hasTestTag("projects-open-removed")),
            useUnmergedTree = true).assertIsDisplayed()
        val removed = onNodeWithTag("projects-open-removed").assertHasClickAction()
        assertTrue(removed.fetchSemanticsNode().size.height >= 48)
        removed.performMouseInput { click() }
        onNodeWithTag("removed-project-0").assertIsDisplayed()
        onNode(hasText("A.autoflow") and hasAnyAncestor(hasTestTag("removed-project-0")), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("glass-dialog-title").assertTextEquals("Removed projects")
        onNodeWithTag("glass-dialog-subtitle").assertTextEquals(UiText.ONE_PROJECT.en)
        assertEquals(emptyList(), textFitProblems(360, 1f, strict = true))
    }

    @Test fun backClosesTheCreateCardAndATabSwitchResetsIt() {
        val open = V2Nav(StudioV2Dest.PROJECTS, createOpen = true)
        assertEquals(V2Nav(StudioV2Dest.PROJECTS), v2Back(open, recap = false))
        assertFalse(v2Select(open, StudioV2Dest.JOBS).createOpen)
    }
}
