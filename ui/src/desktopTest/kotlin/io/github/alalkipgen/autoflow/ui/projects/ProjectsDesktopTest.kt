package io.github.alalkipgen.autoflow.ui.projects

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
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
import kotlin.math.abs
import kotlin.test.*

/**
 * P9-9b desktop Projects (DESIGN.md §9.8, D2): from 900 dp the projects are a card grid (columns from the 240 dp card
 * minimum × the font scale); a click selects (primary border + detail pane), double-click / Enter / Continue editing open,
 * right-click / ⋮ open the existing options, × closes the pane, Show in folder calls the file manager; strict
 * no-clipping at 1440 × 900 and the 1024 × 680 minimum window, font 1.0 / 2.0. Project files stay unchanged.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class ProjectsDesktopTest(val width: Int, val font: Float, val language: UiLanguage) {
    companion object {
        private val root by lazy { Files.createTempDirectory("uiv3-projects-desktop").toFile() }
        private val fixture by lazy { QaRecapFixture.create(File(root, "recap").apply { mkdirs() }) }
        private val music by lazy { project("Rain Song", Mode.MUSIC_VIDEO) }
        private val subs by lazy { project("Interview", Mode.SUBTITLES) }
        private fun project(name: String, mode: Mode): Pair<String, io.github.alalkipgen.autoflow.core.model.Project> {
            val dir = File(root, "$name.autoflow").path
            return dir to (ProjectStore(JvmFs()).create(dir, name, mode) as CoreResult.Ok).value
        }
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}")
        fun cases() = listOf(1440, 1024).flatMap { w -> listOf(1f, 2f).flatMap { f -> UiLanguage.entries.map { l -> arrayOf<Any>(w, f, l) } } }
    }

    @Test fun gridSelectsDetailPaneOpensAndOptionsStayTheSame() = runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 680) {
        val fs = JvmFs()
        val bytes = listOf(fixture.dir.path, music.first, subs.first).associateWith { File(it, "project.json").readBytes() }
        val index = ProjectIndex(fs, File(root, "index-$width-$font-${language.code}.json").path)
        index.record(subs.first, subs.second); index.record(music.first, music.second); index.record(fixture.dir.path, fixture.project)
        val current = CurrentProject()
        val model = DashboardModel(fs, ProjectStore(fs), index, SettingsStore(fs, File(root, "settings.json").path), FakePicker(), current)
        val jobs = jobRows(null, null, null, listOf(
            AiJob("run", AiJobKind.TTS_ALL, fixture.dir.path, listOf(fixture.story.blocks.last().id), AiJobState.RUNNING),
        ), language)
        var opened = 0
        val shown = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(LocalCompact provides false, LocalDensity provides Density(1f, font),
                LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                StudioV2Shell(StudioV2Dest.PROJECTS, {}, jobsBadgeValue(jobs), width.toFloat()) {
                    DashboardScreen(model, { opened++ }, jobs = jobs, thumbSource = { dir -> ProjectStore(JvmFs()).thumbSource(dir) },
                        onShowFolder = { shown += it; null })
                }
            }
        }
        val recap = fixture.project.name
        fun card(name: String) = "project-card-$name"
        fun click(tag: String) { onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }; mainClock.advanceTimeBy(1_000) }
        // Equal columns: as many as fit 240 dp × the font scale with 12 dp gaps.
        fun assertColumns() {
            val grid = onNodeWithTag("projects-grid").fetchSemanticsNode().size.width.toFloat()
            val min = 240f * font; val gap = 12f
            val columns = ((grid + gap) / (min + gap)).toInt().coerceAtLeast(1)
            val expected = (grid - gap * (columns - 1)) / columns
            listOf(recap, "Rain Song", "Interview").forEach { name ->
                val w = onNodeWithTag(card(name)).fetchSemanticsNode().size.width
                assertTrue(abs(w - expected) <= 1.5f, "$name card $w px, expected $expected ($columns columns in $grid)")
            }
            val firstRow = listOf(recap, "Rain Song", "Interview").take(columns)
                .map { onNodeWithTag(card(it)).fetchSemanticsNode().positionInRoot.y }.distinct()
            assertEquals(1, firstRow.size, "the first $columns cards share a row")
        }
        onNodeWithTag("projects-grid").assertExists()
        listOf(recap, "Rain Song", "Interview").forEach { name ->
            onNodeWithTag(card(name)).assertExists(); onNodeWithTag("project-row-$name").assertDoesNotExist()
            onNode(hasTestTag("media-thumb") and hasAnyAncestor(hasTestTag(card(name)))).assertExists()
        }
        // The running chip is drawn (a wrapped chip row must not be dropped), and a row's cards are equally tall.
        onNode(hasTestTag("status-RUNNING") and hasAnyAncestor(hasTestTag(card(recap)))).performScrollTo().assertIsDisplayed()
        val layers = listOf(recap, "Rain Song", "Interview").map { onNodeWithTag("project-card-click-$it").fetchSemanticsNode() }
        layers.groupBy { it.positionInRoot.y }.values.forEach { same -> assertEquals(1, same.map { it.size.height }.distinct().size) }
        listOf(recap, "Rain Song", "Interview").forEach { name ->
            val content = onNodeWithTag(card(name)).fetchSemanticsNode(); val layer = onNodeWithTag("project-card-click-$name").fetchSemanticsNode()
            assertEquals(content.positionInRoot, layer.positionInRoot); assertTrue(layer.size.height >= content.size.height, name)
        }
        onNodeWithTag("project-detail-pane").assertDoesNotExist()
        assertColumns()
        if (width == 1440 && font == 1f) assertEquals(4, ((onNodeWithTag("projects-grid").fetchSemanticsNode().size.width + 12f) / 252f).toInt())
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))
        // A click selects: primary border (selected), the detail pane; nothing opens.
        click("project-card-click-$recap")
        assertEquals(0, opened)
        onNodeWithTag("project-card-click-$recap").assertIsSelected()
        onNodeWithTag("project-card-click-Rain Song").assertIsNotSelected()
        onNodeWithTag("project-detail-pane").assertIsDisplayed()
        onNodeWithTag("project-detail-title").assertTextEquals(recap)
        onNode(hasText(My.breakSyllables(UiText.PROJECT_LAST_OPENED.text(language))) and hasAnyAncestor(hasTestTag("project-detail-pane"))).assertExists()
        onNode(hasTestTag("status-RUNNING") and hasAnyAncestor(hasTestTag("project-detail-pane"))).assertExists()
        onNodeWithTag("project-detail-folder-path").assertTextEquals(UiText.FOLDER_PATH.text(language, "it" to fixture.dir.path))
        assertColumns()
        if (width == 1440 && font == 1f) assertEquals(3, ((onNodeWithTag("projects-grid").fetchSemanticsNode().size.width + 12f) / 252f).toInt(),
            "3 columns beside the pane at 1440")
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))
        onNodeWithTag("project-detail-remove").performScrollTo()
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))
        val images = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "projects").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(images, "desktop-$width-${language.code}-$font.png"))
        // Show in folder hands the project folder to the file manager.
        click("project-detail-folder")
        assertEquals(listOf(fixture.dir.path), shown)
        // Remove from list asks first (the existing confirm dialog); Cancel keeps the project.
        click("project-detail-remove")
        onNodeWithText(UiText.REMOVE_PROJECT_QUESTION.text(language, "name" to recap)).assertIsDisplayed()
        onNodeWithText(UiText.CANCEL.text(language)).assertIsDisplayed().performMouseInput { click() }
        onNodeWithTag(card(recap)).assertExists()
        // × closes the pane; selecting another card moves the pane.
        click("project-detail-close")
        onNodeWithTag("project-detail-pane").assertDoesNotExist()
        onNodeWithTag("project-card-click-$recap").assertIsNotSelected()
        click("project-card-click-Interview")
        onNodeWithTag("project-detail-title").assertTextEquals("Interview")
        // Right-click and ⋮ open the same options as on the phone.
        onNodeWithTag("project-card-click-Rain Song").performScrollTo().performMouseInput { rightClick() }
        onNodeWithTag("project-remove-action").assertIsDisplayed()
        onNodeWithText(UiText.CANCEL.text(language)).assertIsDisplayed().performMouseInput { click() }
        click("project-more-Rain Song")
        onNodeWithTag("project-remove-action").assertIsDisplayed()
        onNodeWithText(UiText.CANCEL.text(language)).assertIsDisplayed().performMouseInput { click() }
        assertEquals(0, opened)
        // Double-click opens a card's project; Continue editing opens the selected one.
        onNodeWithTag("project-card-click-Rain Song").performScrollTo().performMouseInput { doubleClick() }
        mainClock.advanceTimeBy(1_000)
        assertEquals(1, opened); assertEquals(music.first, current.state.value!!.dir)
        click("project-detail-open")
        assertEquals(2, opened); assertEquals(subs.first, current.state.value!!.dir)
        // Enter opens the focused card.
        onNodeWithTag("project-card-click-$recap").performScrollTo().requestFocus().performKeyInput { pressKey(Key.Enter) }
        mainClock.advanceTimeBy(1_000)
        assertEquals(3, opened); assertEquals(fixture.dir.path, current.state.value!!.dir)
        bytes.forEach { (dir, b) -> assertContentEquals(b, File(dir, "project.json").readBytes(), dir) }
    }
}
