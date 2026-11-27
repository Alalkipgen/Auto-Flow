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

private class ListJobsRunner(initial: List<AiJob>) : AiJobRunner {
    override val jobs = MutableStateFlow(initial)
    override val supportsHistoryRemoval = true
    val resumed = mutableListOf<String>(); val cancelled = mutableListOf<String>()
    override suspend fun enqueue(job: AiJob): String { resumed += job.id; return job.id }
    override suspend fun cancel(id: String) { cancelled += id }
    override suspend fun refresh() = Unit
}

/**
 * P9-10a Jobs (DESIGN.md §9.7, panel 3): title with the group counts, Running / Needs you / Finished as caption
 * labels, running and finished cards with the project thumbnail and progress, Needs-you cards as warning / error
 * attention cards with the plain reason; the same Open / Resume remaining / Retry / Cancel controls (≥ 48 dp);
 * strict no-clipping at phone / medium widths and font 1.0 / 1.3 / 2.0. Project files stay unchanged.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class JobsListTest(val width: Int, val font: Float, val language: UiLanguage) {
    companion object {
        private val fixture by lazy { QaRecapFixture.create(Files.createTempDirectory("uiv3-jobs-list").toFile()) }
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}")
        fun cases() = listOf(412).flatMap { w -> listOf(1f, 1.3f, 2f).flatMap { f -> UiLanguage.entries.map { l -> arrayOf<Any>(w, f, l) } } } +
            listOf(arrayOf<Any>(360, 2f, UiLanguage.BURMESE), arrayOf<Any>(360, 1f, UiLanguage.BURMESE),
                arrayOf<Any>(760, 1f, UiLanguage.BURMESE), arrayOf<Any>(760, 2f, UiLanguage.ENGLISH))
    }

    @Test fun groupsShowCountsThumbnailsAttentionCardsAndTheSameActions() = runDesktopComposeUiTest(width = width, height = 760) {
        val dir = fixture.dir.path
        val before = File(dir, "project.json").readBytes()
        val items = fixture.story.blocks.map { it.id }
        val runner = ListJobsRunner(listOf(
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
            CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                StudioV2Shell(StudioV2Dest.JOBS, {}, jobsBadgeValue(rows), width.toFloat()) {
                    JobsScreen(rows, scope, { opened += it.jobId.orEmpty() }, actions = actions,
                        thumbSource = { d -> ProjectStore(JvmFs()).thumbSource(d) })
                }
            }
        }
        fun inCard(id: String, matcher: SemanticsMatcher) = onNode(matcher and hasAnyAncestor(hasTestTag("job-card-$id")))
        // Title + counts of the non-empty groups.
        onNodeWithTag("jobs-heading").assertTextEquals(UiText.JOBS.text(language))
        val counts = listOf(UiText.JOB_RUNNING to 1, UiText.V3_JOBS_NEEDS_YOU to 2, UiText.V3_JOBS_FINISHED to 1)
            .joinToString(" · ") { (label, n) -> "${label.text(language)} $n" }
        onNodeWithTag("jobs-counts").assertTextEquals(My.breakSyllables(counts))
        JobGroup.entries.forEach { onNodeWithTag("jobs-group-${it.name}").performScrollTo().assertIsDisplayed() }
        // Running and finished: the project thumbnail; needs you: the attention icon instead, and the plain reason.
        listOf("run", "done").forEach { inCard(it, hasTestTag("media-thumb")).performScrollTo().assertIsDisplayed() }
        listOf("paused", "failed").forEach { inCard(it, hasTestTag("media-thumb")).assertDoesNotExist() }
        inCard("failed", hasText("QA retained failure", substring = true)).performScrollTo().assertIsDisplayed()
        inCard("run", hasTestTag("status-RUNNING")).performScrollTo().assertIsDisplayed()
        inCard("paused", hasTestTag("status-PAUSED")).performScrollTo().assertIsDisplayed()
        // Group order: running above needs you above finished.
        val y = listOf("run", "paused", "done").map { onNodeWithTag("job-card-$it").fetchSemanticsNode().positionInRoot.y }
        assertTrue(y[0] < y[1] && y[1] < y[2], "groups in order: $y")
        // The same controls as before, each ≥ 48 dp.
        fun click(tag: String) {
            val node = onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            assertTrue(node.fetchSemanticsNode().size.height >= 48, tag)
            node.performMouseInput { click() }
        }
        onNodeWithTag("jobs-content").performScrollToNode(hasTestTag("jobs-header"))
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))
        val images = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "jobs").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(images, "list-$width-${language.code}-$font.png"))
        onNodeWithTag("job-open-done").performScrollTo()
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))
        onNodeWithTag("job-resume-paused").performScrollTo().assertTextEquals(UiText.V3_RESUME_REMAINING.text(language, "n" to items.size))
        onNodeWithTag("job-resume-failed").performScrollTo().assertTextEquals(UiText.RETRY.text(language))
        click("job-open-run"); click("job-open-failed")
        assertEquals(listOf("run", "failed"), opened)
        click("job-cancel-run"); assertEquals(listOf("run"), runner.cancelled)
        click("job-resume-paused"); assertEquals(listOf("paused"), runner.resumed)
        assertContentEquals(before, File(dir, "project.json").readBytes())
    }
}
