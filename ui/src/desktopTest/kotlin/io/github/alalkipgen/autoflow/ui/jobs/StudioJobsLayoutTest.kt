package io.github.alalkipgen.autoflow.ui.jobs

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.*
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.media.render.*
import io.github.alalkipgen.autoflow.platform.*
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.export.ExportHarness
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.recap.*
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.test.*

private class JobsTestRunner(initial: List<AiJob>) : AiJobRunner {
    override val jobs = MutableStateFlow(initial)
    val resumed = mutableListOf<AiJob>(); val cancelled = mutableListOf<String>()
    override suspend fun enqueue(job: AiJob): String {
        resumed += job
        jobs.value = jobs.value.map { if (it.id == job.id) it.copy(state = AiJobState.QUEUED, error = null) else it }
        return job.id
    }
    override suspend fun cancel(id: String) {
        cancelled += id
        jobs.value = jobs.value.filter { it.id != id }
    }
    override suspend fun refresh() = Unit
}

private class JobsPicker : FilePicker {
    override suspend fun pickFolder(title: String, startDir: String?) = PlatformResult.Ok<String?>(null)
    override suspend fun pickFiles(title: String, extensions: Set<String>, multiple: Boolean, startDir: String?) =
        PlatformResult.Ok(emptyList<String>())
}

@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class StudioJobsLayoutTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}/bundled={3}")
        fun cases() = StudioStoryLayoutTest.cases()
    }
    @Test fun groupedJobsUseVisibleActionsStableProjectsAndTheExistingBackRule() =
        runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
            ExportHarness().use { h ->
                val project = h.fixture.dir.path
                val items = h.fixture.story.blocks.map { it.id }
                val runner = JobsTestRunner(listOf(
                    AiJob("paused", AiJobKind.TTS_ALL, project, items, AiJobState.PAUSED, "QA model busy"),
                    AiJob("failed", AiJobKind.TRANSCRIBE, project, items.takeLast(1), AiJobState.FAILED, "QA retained failure"),
                    AiJob("done", AiJobKind.TTS_ALL, project, emptyList(), AiJobState.DONE),
                    AiJob("unsupported", AiJobKind.QUICK_RECAP, project, items, AiJobState.FAILED, "Unsupported job kind"),
                ))
                val output = File(h.fixture.dir, "exports/jobs-fixture.mp4").apply { parentFile.mkdirs() }
                h.fixture.dir.walkTopDown().first { it.extension == "mp4" && it != output }.copyTo(output)
                h.model.refresh(); h.model.render(Quality.EXPORT)
                waitUntil(timeoutMillis = 180_000) { h.model.state.value.run != null }
                h.run.value = RenderState.Running(.62, 1.0)
                val other = (h.store.create(File(h.root, "B.autoflow").path, "Other saved project", Mode.MOVIE_RECAP) as CoreResult.Ok).value
                h.current.set(OpenProject(File(h.root, "B.autoflow").path, other))
                val dashboard = DashboardModel(h.fs, h.store, ProjectIndex(h.fs, File(h.root, "jobs-index.json").path),
                    h.settings, JobsPicker(), h.current, projectsHome = h.root.path)
                val files = mutableListOf<String>()
                val share = object : FileShare {
                    override fun open(path: String, mime: String) = PlatformResult.Ok(Unit).also { files += "open:$path" }
                    override fun share(path: String, mime: String, title: String) = PlatformResult.Ok(Unit).also { files += "share:$path" }
                    override suspend fun saveToGallery(path: String, mime: String, displayName: String) =
                        PlatformResult.Ok(displayName).also { files += "gallery:$path" }
                }
                val actions = JobsActions(h.scope, render = h.model, runner = runner, fs = h.fs, share = share)
                val before = h.bytes()
                val outputBytes = output.readBytes()
                var nav by mutableStateOf(V2Nav(StudioV2Dest.JOBS)); var opened: V2Nav? = null
                val back = object : NavigationEventInput() { fun press() = dispatchOnBackCompleted() }
                setContent { CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                    val dispatcher = requireNotNull(LocalNavigationEventDispatcherOwner.current).navigationEventDispatcher
                    DisposableEffect(dispatcher) { dispatcher.addInput(back); onDispose { dispatcher.removeInput(back) } }
                    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = nav.dest == StudioV2Dest.JOBS,
                        onBackCompleted = { nav = requireNotNull(v2Back(nav, true)) })
                    val render by h.model.state.collectAsState(); val ai by runner.jobs.collectAsState()
                    val rows = jobRows(null, render.run, null, ai, language, mapOf(project to h.fixture.project.name))
                    StudioV2Shell(nav.dest, { nav = v2Select(nav, it) }, jobsBadgeValue(rows), width.toFloat()) {
                        if (nav.dest == StudioV2Dest.JOBS) JobsScreen(rows, h.scope,
                            onOpen = { opened = openJobProject(it, dashboard, h.current) }, actions = actions)
                        else AfText("Home", Modifier.testTag("jobs-returned-home"))
                    }
                } }
                fun click(tag: String) {
                    val node = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
                    val bounds = node.getUnclippedBoundsInRoot()
                    val viewport = onNodeWithTag("jobs-content").getUnclippedBoundsInRoot()
                    assertTrue((bounds.bottom - bounds.top).value >= 48 && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
                    node.performMouseInput { click() }
                }
                val selected = width in listOf(360, 1440) ||
                    width == 412 && (bundled && font in listOf(1f, 2f) || !bundled && language == UiLanguage.BURMESE && font == 2f) ||
                    width == 840 && language == UiLanguage.BURMESE && font == 2f && bundled
                fun capture(state: String) {
                    assertEquals(emptyList(), textFitProblems(width, font))
                    if (selected) {
                        val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "jobs").apply { mkdirs() }
                        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "08-jobs-$state-$width-${language.code}-$font-$bundled.png"))
                    }
                }
                onNodeWithTag("jobs-group-RUNNING").assertIsDisplayed()
                onNodeWithTag("jobs-badge", useUnmergedTree = true).assertIsDisplayed().assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "${UiText.V3_JOBS_NEEDS_YOU.text(language)} 3"))
                assertEquals(AutoFlowColors.Amber, jobsBadgeColor(-3)); assertEquals(AutoFlowColors.Cyan, jobsBadgeColor(1))
                capture("running")
                click("job-open-render")
                assertEquals(project, h.current.state.value?.dir); assertEquals("Export", opened?.projectScreen)
                if (language == UiLanguage.BURMESE && font == 2f) {
                    onNodeWithTag("job-resume-paused").performScrollTo().assertIsDisplayed()
                    capture("paused")
                }
                onNodeWithTag("job-resume-failed").performScrollTo().assertIsDisplayed()
                capture("needs-you")
                click("job-open-paused")
                assertEquals(project, h.current.state.value?.dir); assertEquals(RecapStep.VOICE, opened?.recapStep)
                click("job-resume-paused"); click("job-resume-failed")
                assertEquals(listOf("paused", "failed"), runner.resumed.map { it.id })
                assertEquals(items, runner.resumed.first().items); assertTrue(runner.resumed.all { it.project == project })
                onNodeWithTag("job-resume-unsupported").assertDoesNotExist()
                click("job-cancel-paused"); assertEquals(listOf("paused"), runner.cancelled)
                h.run.value = RenderState.Done(output.path, 5_000, 100, null)
                onNodeWithTag("job-file-OPEN-render").performScrollTo().assertIsDisplayed()
                capture("finished")
                JobFileAction.entries.forEach { click("job-file-${it.name}-render") }
                assertEquals(listOf("open:${output.path}", "share:${output.path}", "gallery:${output.path}"), files)
                onNodeWithTag("job-notice-render").performScrollTo().assertIsDisplayed()
                assertEquals(UiText.EXPORT_GALLERY_SAVED, actions.notice?.key)
                if (width == 412 && bundled && (language == UiLanguage.ENGLISH && font == 1f ||
                    language == UiLanguage.BURMESE && font == 2f)) capture("gallery-result")
                output.delete() // Test-owned output, never application/user data.
                val finished = jobRows(null, h.model.state.value.run, null, emptyList()).single()
                assertFalse(actions.hasOutput(finished), "gone files must not be handed to the platform")
                output.writeBytes(outputBytes)
                h.model.render(Quality.EXPORT)
                click("job-cancel-render"); assertEquals(listOf("render-2"), h.cancelled)
                assertEquals(before, h.bytes(), "navigation/AI port dispatch must not change saved project/takes/history")
                back.press(); onNodeWithTag("jobs-returned-home").assertIsDisplayed()
            }
        }
}

@OptIn(ExperimentalTestApi::class)
class StudioJobsWorkflowTest {
    @Test fun pointerResumeRetryAndCancelUseTheRealPersistentRunnerWithoutRedoingCompletedItems() =
        runDesktopComposeUiTest(width = 412, height = 760) {
            ExportHarness().use { h ->
                val dir = File(h.root, "real-jobs")
                val store = AiJobStore(h.fs, dir.path)
                val outputs = File(h.root, "processor-results").apply { mkdirs() }
                File(outputs, "already").writeText("immutable previous output")
                val before = h.bytes()
                listOf(
                    AiJob("resume-real", AiJobKind.TTS_ALL, h.fixture.dir.path, listOf("already", "remaining"), AiJobState.PAUSED),
                    AiJob("retry-real", AiJobKind.TRANSCRIBE, h.fixture.dir.path, listOf("retry"), AiJobState.FAILED, "retained failure"),
                    AiJob("cancel-real", AiJobKind.TTS_ALL, h.fixture.dir.path, listOf("held"), AiJobState.PAUSED),
                ).forEach(store::save)
                val calls = Collections.synchronizedList(mutableListOf<String>())
                val gate = CompletableDeferred<Unit>()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val runner = DesktopAiJobRunner(store, scope, object : AiItemProcessor {
                    override suspend fun alreadyDone(job: AiJob, item: String) = File(outputs, item).exists()
                    override suspend fun process(job: AiJob, item: String) {
                        calls += item
                        if (item == "held") gate.await()
                        File(outputs, item).writeText("${job.project}:${job.id}")
                    }
                }, pacer = { RpmPacer(1_000) })
                try {
                    setContent {
                        val jobs by runner.jobs.collectAsState()
                        JobsScreen(jobRows(null, null, null, jobs), scope, {}, actions = remember { JobsActions(scope, runner = runner) })
                    }
                    fun click(id: String) = onNodeWithTag(id).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                    waitUntil(timeoutMillis = 60_000) { runner.jobs.value.size == 3 }
                    click("job-resume-resume-real")
                    waitUntil(timeoutMillis = 60_000) { store.load("resume-real")?.state == AiJobState.DONE }
                    click("job-resume-retry-real")
                    waitUntil(timeoutMillis = 60_000) { store.load("retry-real")?.state == AiJobState.DONE }
                    click("job-resume-cancel-real")
                    waitUntil(timeoutMillis = 60_000) { store.load("cancel-real")?.state == AiJobState.RUNNING }
                    click("job-cancel-cancel-real")
                    waitUntil(timeoutMillis = 60_000) { store.load("cancel-real")?.state == AiJobState.CANCELLED }
                    assertEquals(listOf("remaining", "retry", "held"), calls.toList())
                    assertEquals("immutable previous output", File(outputs, "already").readText())
                    assertFalse(File(outputs, "held").exists())
                    val reloaded = AiJobStore(h.fs, dir.path)
                    assertTrue(reloaded.load("resume-real")!!.items.isEmpty())
                    assertEquals(listOf("held"), reloaded.load("cancel-real")!!.items)
                    assertEquals(before, h.bytes())
                } finally { scope.cancel() }
            }
        }

    @Test fun emptyJobsAndUnboundStoryboardDoNotInventAProjectOrBadge() = runDesktopComposeUiTest(width = 360, height = 760) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var language by mutableStateOf(UiLanguage.ENGLISH)
            setContent { CompositionLocalProvider(LocalCompact provides true, LocalUiLanguage provides language,
                LocalDensity provides Density(1f, if (language == UiLanguage.BURMESE) 2f else 1f),
                LocalUiFontFamily provides BurmeseTestFont.family) {
                StudioV2Shell(StudioV2Dest.JOBS, {}, 0, 360f) { JobsScreen(emptyList(), scope, {}, actions = remember { JobsActions(scope) }) }
            } }
            onNodeWithTag("jobs-empty").assertIsDisplayed()
            JobGroup.entries.forEach { onNodeWithTag("jobs-group-${it.name}").assertDoesNotExist() }
            onNodeWithTag("jobs-badge", useUnmergedTree = true).assertDoesNotExist()
            assertEquals(0, jobsBadgeValue(emptyList<JobRow>()))
            UiLanguage.entries.forEach { lang ->
                language = lang; waitForIdle()
                val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "jobs").apply { mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "08-jobs-empty-360-${lang.code}.png"))
            }
            ExportHarness().use { h ->
                val dashboard = DashboardModel(h.fs, h.store, ProjectIndex(h.fs, File(h.root, "chooser-index.json").path),
                    h.settings, JobsPicker(), h.current)
                val row = jobRows(null, null, io.github.alalkipgen.autoflow.ui.storyboard.JobState(running = "Storyboard"), emptyList()).single()
                val before = h.current.state.value
                assertEquals(V2Nav(StudioV2Dest.PROJECTS), openJobProject(row, dashboard, h.current))
                assertEquals(before, h.current.state.value)
                assertNull(openJobProject(row.copy(projectDir = File(h.root, "missing.autoflow").path), dashboard, h.current))
                assertEquals(before, h.current.state.value)
            }
        } finally { scope.cancel() }
    }

    @Test fun staleRowsCannotResumeAnotherProjectOrCompletedJobAndResumeReadsCurrentRemainingItems() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val old = AiJob("stable", AiJobKind.TTS_ALL, "/A.autoflow", listOf("done", "remaining"), AiJobState.PAUSED)
            val runner = JobsTestRunner(listOf(old))
            val row = jobRows(null, null, null, listOf(old)).single()
            val actions = JobsActions(scope, runner = runner)
            runner.jobs.value = listOf(old.copy(project = "/B.autoflow"))
            assertFalse(actions.canResume(row)); actions.resume(row); assertTrue(runner.resumed.isEmpty())
            runner.jobs.value = listOf(old.copy(state = AiJobState.DONE, items = emptyList()))
            assertFalse(actions.canResume(row)); actions.resume(row); assertTrue(runner.resumed.isEmpty())
            runner.jobs.value = listOf(old.copy(items = listOf("remaining")))
            actions.resume(row)
            assertEquals(listOf("remaining"), runner.resumed.single().items)
            assertEquals("/A.autoflow", runner.resumed.single().project)
            assertFalse(actions.canResume(row))
        } finally { scope.cancel() }
    }
}