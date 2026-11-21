package io.github.alalkipgen.autoflow.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.jobs.*
import io.github.alalkipgen.autoflow.ui.media.thumbSource
import io.github.alalkipgen.autoflow.ui.projects.ProjectEntry
import io.github.alalkipgen.autoflow.ui.recap.QaRecapFixture
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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Home D1 (DESIGN.md §9.8) at 1440 × 900 and 1024 × 680, font 1.0 / 2.0: the hero beside the Jobs column (≤ 3 cards),
 * up to 4 Continue cards in one row (fewer at font 2.0), the Create + Quick-tools tiles as one row, nothing clipped.
 * `AUTOFLOW_PR_SHOTS_DIR` set → `home-d1-<w>-<font>-<lang>.png` (top) and `…-end.png` (scrolled to the tiles).
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class HomeDesktopTest(val width: Int, val height: Int, val font: Float, val language: UiLanguage) {
    companion object {
        private val fixture by lazy { QaRecapFixture.create(Files.createTempDirectory("uiv3-home-d1").toFile()) }
        @JvmStatic @Parameterized.Parameters(name = "{0}x{1}/{2}/{3}")
        fun cases() = listOf(1440 to 900, 1024 to 680).flatMap { (w, h) -> listOf(1f, 2f).map { arrayOf<Any>(w, h, it, UiLanguage.BURMESE) } } +
            listOf(arrayOf<Any>(1440, 900, 1f, UiLanguage.ENGLISH))
    }

    @Test fun heroBesideJobsContinueRowAndOneTileRow() = runDesktopComposeUiTest(width = width, height = height) {
        val t0 = Instant.parse("2026-11-20T10:00:00Z")
        val recent = listOf(ProjectEntry(fixture.dir.path, fixture.project.name, Mode.MOVIE_RECAP, Ratio.R16_9, 3, 8, t0)) +
            listOf("ဇာတ်လမ်းတို" to Mode.MUSIC_VIDEO, "Trailer cut" to Mode.MOVIE_RECAP, "Rain clip" to Mode.SUBTITLES, "Old one" to Mode.VOICE_AUDIO)
                .mapIndexed { i, (name, mode) -> ProjectEntry("${fixture.dir.parent}/p$i.autoflow", name, mode, Ratio.R16_9, 0, 0, t0 - (i + 1).hours) }
        val rows = jobRows(null, null, null, (1..4).map { AiJob("d1-$it", AiJobKind.TTS_ALL, fixture.dir.path, listOf(fixture.story.blocks.last().id),
            state = AiJobState.RUNNING) }, language, mapOf(fixture.dir.path to fixture.project.name))
        var opened: String? = null; var jobs = 0; var projects = 0
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
                LocalUiFontFamily provides BurmeseTestFont.family) {
                StudioV2Shell(StudioV2Dest.HOME, {}, jobsBadgeValue(rows), width.toFloat()) {
                    HomeScreen(recent = recent, onOpenProject = { opened = it }, jobs = rows, onViewJobs = { jobs++ }, onOpenProjects = { projects++ },
                        continueStep = "Story", continueProjectPath = fixture.dir.path,
                        thumbSource = { dir -> ProjectStore(JvmFs()).thumbSource(dir) }, onNewDubbing = {}, onNewVoice = {}, onNewSubtitles = {})
                }
            }
        }
        waitForIdle()
        System.getenv("AUTOFLOW_PR_SHOTS_DIR")?.let { out ->
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(File(out).apply { mkdirs() }, "home-d1-$width-$font-${language.code}.png"))
            onNodeWithTag("home-subtitles-tool").performScrollTo()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "home-d1-$width-$font-${language.code}-end.png"))
            onNodeWithTag("home-header").performScrollTo()
        }
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))

        // Hero and Jobs column side by side, top-aligned; at most 3 job cards + "All ›".
        onNodeWithTag("home-running").performScrollTo()
        val hero = onNodeWithTag("home-quick").fetchSemanticsNode().unclipped()
        val jobsColumn = onNodeWithTag("home-running").fetchSemanticsNode().unclipped()
        assertTrue(jobsColumn.left >= hero.right, "jobs right of the hero: $hero / $jobsColumn")
        assertTrue(jobsColumn.top < hero.bottom && hero.top < jobsColumn.bottom, "same row: $hero / $jobsColumn")
        onAllNodes(hasTestTagPrefix("home-job-") and !hasTestTagPrefix("home-job-cancel")).assertCountEquals(3)
        onNodeWithTag("home-view-jobs").performScrollTo().performMouseInput { click() }
        assertEquals(1, jobs)

        // Continue: cards in one row, the latest first with its step and the only mode tag.
        val cards = recent.map { it.name }.filter { onAllNodesWithTag("recent-$it").fetchSemanticsNodes().isNotEmpty() }
        val expected = if (font >= 2f) 2 else 4
        assertEquals(recent.take(expected).map { it.name }, cards)
        onNodeWithTag("recent-${cards[0]}").performScrollTo()
        val tops = cards.map { onNodeWithTag("recent-$it").fetchSemanticsNode().unclipped() }
        assertTrue(tops.all { abs(it.top - tops[0].top) < 1f && abs(it.bottom - tops[0].bottom) < 1f }, "one row of equal cards: $tops")
        onAllNodesWithTag("home-mode-MOVIE_RECAP").assertCountEquals(1)
        onNodeWithTag("home-continue-step").performScrollTo().assertIsDisplayed()
        onNode(hasText("0:10") and hasAnyAncestor(hasTestTag("home-continue"))).performScrollTo().assertIsDisplayed()
        onNodeWithTag("recent-${recent[1].name}").performScrollTo().performMouseInput { click() }
        assertEquals(recent[1].path, opened)
        onNodeWithTag("home-all-projects").performScrollTo().performMouseInput { click() }
        assertEquals(1, projects)

        // Create + Quick tools: one section, one row at font 1.0.
        val tags = listOf("home-new-recap", "home-new-mv", "home-dubbing", "home-voice-tool", "home-subtitles-tool")
        tags.forEach { onNodeWithTag(it).performScrollTo().assertIsEnabled() }
        // Unclipped rectangles, all read at one scroll position.
        val tiles = tags.map { onNodeWithTag(it).fetchSemanticsNode().unclipped() }
        if (font < 2f) assertTrue(tiles.all { abs(it.top - tiles[0].top) < 1f }, "one tile row: $tiles")
        assertTrue(tiles.zipWithNext().all { (a, b) -> b.left >= a.right || b.top >= a.bottom }, "tiles never overlap: $tiles")
    }
}

private fun androidx.compose.ui.semantics.SemanticsNode.unclipped() =
    androidx.compose.ui.geometry.Rect(positionInRoot, androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))

private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
}
