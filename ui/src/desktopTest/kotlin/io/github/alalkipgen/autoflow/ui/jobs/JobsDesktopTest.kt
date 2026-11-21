package io.github.alalkipgen.autoflow.ui.jobs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.media.thumbSource
import io.github.alalkipgen.autoflow.ui.recap.QaRecapFixture
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

private class DesktopJobsRunner(initial: List<AiJob>) : AiJobRunner {
    override val jobs = MutableStateFlow(initial)
    override val supportsHistoryRemoval = true
    val resumed = mutableListOf<String>(); val cancelled = mutableListOf<String>()
    override suspend fun enqueue(job: AiJob): String { resumed += job.id; return job.id }
    override suspend fun cancel(id: String) { cancelled += id }
    override suspend fun refresh() = Unit
}

/**
 * P9-10b desktop Jobs (DESIGN.md §9.7 / §9.8 list–detail): the list (420–480 dp) on the left, the selected job on the
 * right — the first job until another card is clicked. The job's buttons live only in the detail pane (same tags), a
 * click on a card selects it (finished cards keep right-click → delete from history), the failure reason shows in the
 * detail, strict no-clipping at 1440 × 900 and 1024 × 680 × font 1.0 / 2.0. Project files stay unchanged.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class JobsDesktopTest(val width: Int, val font: Float, val language: UiLanguage) {
    companion object {
        private val fixture by lazy { QaRecapFixture.create(Files.createTempDirectory("uiv3-jobs-desktop").toFile()) }
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}")
        fun cases() = listOf(1440, 1024).flatMap { w -> listOf(1f, 2f).flatMap { f -> UiLanguage.entries.map { l -> arrayOf<Any>(w, f, l) } } }
    }
    private val height get() = if (width == 1440) 900 else 680

    @Test fun listLeftSelectedJobRightButtonsOnlyInTheDetail() = runDesktopComposeUiTest(width = width, height = height) {
        val dir = fixture.dir.path
        val before = File(dir, "project.json").readBytes()
        val items = fixture.story.blocks.map { it.id }
        val runner = DesktopJobsRunner(listOf(
            AiJob("run", AiJobKind.TTS_ALL, dir, items.takeLast(1), AiJobState.RUNNING),
            AiJob("paused", AiJobKind.TTS_ALL, dir, items, AiJobState.PAUSED, "QA model busy"),
            AiJob("failed", AiJobKind.TRANSCRIBE, dir, items.takeLast(1), AiJobState.FAILED, "QA retained failure"),
            AiJob("done", AiJobKind.TTS_ALL, dir, emptyList(), AiJobState.DONE),
        ))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val actions = JobsActions(scope, runner = runner)
        val rows = jobRows(null, null, null, runner.jobs.value, language, mapOf(dir to fixture.project.name))
        val opened = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(LocalCompact provides false, LocalDensity provides Density(1f, font),
                LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                StudioV2Shell(StudioV2Dest.JOBS, {}, jobsBadgeValue(rows), width.toFloat()) {
                    JobsScreen(rows, scope, { opened += it.jobId.orEmpty() }, actions = actions,
                        thumbSource = { d -> ProjectStore(JvmFs()).thumbSource(d) })
                }
            }
        }
        val listDp = onNodeWithTag("list-detail-list").assertIsDisplayed().fetchSemanticsNode().size.width.toFloat()
        assertTrue(listDp in 420f..480f, "list pane $listDp dp")
        val detail = onNodeWithTag("list-detail-detail").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(detail.left > onNodeWithTag("list-detail-list").fetchSemanticsNode().boundsInRoot.right, "detail right of the list")
        // The first job is selected; its buttons are in the detail pane, none stay under the cards.
        onNodeWithTag("job-card-run").assertIsSelected()
        onNodeWithTag("job-card-paused").assertIsNotSelected()
        onNode(hasTestTag("job-cancel-run") and hasAnyAncestor(hasTestTag("jobs-detail"))).assertIsDisplayed()
        listOf("paused", "failed", "done").forEach { onNodeWithTag("job-open-$it").assertDoesNotExist() }
        onAllNodes(hasTestTag("job-open-run")).assertCountEquals(1)
        onNode(hasTestTag("media-thumb") and hasAnyAncestor(hasTestTag("jobs-detail"))).assertIsDisplayed()
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))
        val images = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "jobs").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(images, "desktop-$width-${language.code}-$font.png"))
        fun click(tag: String) {
            val node = onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val b = node.fetchSemanticsNode().boundsInRoot
            val pane = onNodeWithTag("jobs-detail").fetchSemanticsNode().boundsInRoot
            assertTrue(b.height >= 48 && b.top >= pane.top && b.bottom <= pane.bottom, "$tag ≥ 48 dp inside the detail pane")
            node.performMouseInput { click() }
        }
        fun select(id: String) = onNodeWithTag("job-card-$id").performScrollTo().performMouseInput { click() }
        click("job-open-run"); click("job-cancel-run")
        assertEquals(listOf("run"), opened); assertEquals(listOf("run"), runner.cancelled)
        // A paused job (not removable) and a failed one (removable) both select on click.
        select("paused")
        onNodeWithTag("job-card-paused").assertIsSelected(); onNodeWithTag("job-card-run").assertIsNotSelected()
        onNodeWithTag("job-open-run").assertDoesNotExist()
        onNodeWithTag("job-resume-paused").assertTextEquals(UiText.V3_RESUME_REMAINING.text(language, "n" to items.size))
        click("job-resume-paused"); assertEquals(listOf("paused"), runner.resumed)
        select("failed")
        onNodeWithTag("job-card-failed").assertIsSelected()
        onNode(hasTestTag("jobs-detail-reason") and hasText("QA retained failure", substring = true)).assertIsDisplayed()
        onNodeWithTag("job-resume-failed").assertTextEquals(UiText.RETRY.text(language))
        onNodeWithTag("jobs-detail").performScrollToNode(hasTestTag("jobs-detail-card"))
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(images, "desktop-failed-$width-${language.code}-$font.png"))
        click("job-open-failed"); assertEquals(listOf("run", "failed"), opened)
        // Finished: right-click still opens the history menu (cancelled here); a click selects it.
        onNodeWithTag("job-card-done").performScrollTo().performMouseInput { rightClick() }
        onNodeWithTag("job-card-done-remove-menu").assertIsDisplayed().performMouseInput { click() }
        onNodeWithTag("job-card-done-remove-cancel").assertIsDisplayed().performMouseInput { click() }
        assertEquals(4, runner.jobs.value.size)
        select("done")
        onNodeWithTag("job-card-done").assertIsSelected()
        click("job-open-done"); assertEquals(listOf("run", "failed", "done"), opened)
        assertContentEquals(before, File(dir, "project.json").readBytes())
    }

    @Test fun noJobsKeepsTheEmptyStateWithoutADetailPane() = runDesktopComposeUiTest(width = width, height = height) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        setContent {
            CompositionLocalProvider(LocalCompact provides false, LocalDensity provides Density(1f, font),
                LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                StudioV2Shell(StudioV2Dest.JOBS, {}, jobsBadgeValue(emptyList<JobRow>()), width.toFloat()) {
                    JobsScreen(emptyList(), scope, {}, actions = JobsActions(scope, runner = DesktopJobsRunner(emptyList())))
                }
            }
        }
        onNodeWithTag("jobs-empty").assertIsDisplayed()
        onNodeWithTag("list-detail-detail").assertDoesNotExist()
        onNodeWithTag("jobs-counts").assertDoesNotExist()
        // (At 1024 the rail has no labels: too few texts for the fit check, which wants at least three.)
        if (width == 1440) assertEquals(emptyList(), textFitProblems(width, font, strict = true))
    }
}
