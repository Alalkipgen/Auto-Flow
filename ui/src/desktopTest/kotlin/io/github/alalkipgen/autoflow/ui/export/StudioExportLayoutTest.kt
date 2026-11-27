package io.github.alalkipgen.autoflow.ui.export

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.*
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.render.*
import io.github.alalkipgen.autoflow.media.tool.*
import io.github.alalkipgen.autoflow.platform.*
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.recap.*
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*

internal class ExportHarness : AutoCloseable {
    val root = Files.createTempDirectory("uiv3-export").toFile()
    val fixture = QaRecapFixture.create(root)
    val fs = JvmFs()
    val store = ProjectStore(fs)
    val current = CurrentProject()
    val session = ProjectSession(fs, store, ProjectIndex(fs, File(root, "index.json").path), current)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val copied = mutableListOf<String>()
    val shared = mutableListOf<String>()
    val queued = mutableListOf<RenderJob>()
    val cancelled = mutableListOf<String>()
    val run = MutableStateFlow<RenderState>(RenderState.Queued)
    val queue = object : RenderQueue {
        override fun enqueue(job: RenderJob): String { queued += job; run.value = RenderState.Queued; return "render-${queued.size}" }
        override fun observe(id: String) = run
        override fun cancel(id: String) { cancelled += id; run.value = RenderState.Cancelled }
    }
    val settings = SettingsStore(fs, File(root, "settings.json").path)
    val media: MediaTools
    val frames: RecapFrames
    val model: ExportModel
    val recap: RecapModel
    init {
        val saved = (store.save(fixture.dir.path, fixture.project.copy(recap = fixture.project.recap!!.copy(
            publishKit = PublishKit(title = "QA export", description = "Offline fixture", hashtags = listOf("#QA"))))) as CoreResult.Ok).value
        current.set(OpenProject(fixture.dir.path, saved))
        val font = File(root, "Noto.ttf").apply { writeBytes(Base64.getMimeDecoder().decode(
            ExportHarness::class.java.getResourceAsStream("/fonts/NotoSansMyanmar-subset.ttf.b64")!!.readBytes())) }
        settings.update { it.copy(fontPaths = listOf(font.path)) }
        val locator = object : ToolLocator {
            override fun findFfmpeg() = System.getenv("AUTOFLOW_FFMPEG") ?: "ffmpeg"
            override fun findFfprobe() = System.getenv("AUTOFLOW_FFPROBE") ?: "ffprobe"
            override fun findVlc(): String? = null
            override fun isVlcDir(dir: String) = false
        }
        val runner = ToolRunner { cmd, wd, timeout, keep, line -> when (val r = ProcessRunner.run(cmd, File(wd), timeout, keep, line)) {
            is ProcessRunner.Exec.Done -> ToolExec.Done(r.exitCode, r.output, r.tail, r.wallMs, r.timedOut)
            is ProcessRunner.Exec.CannotStart -> ToolExec.CannotStart(r.message)
        } }
        media = MediaTools(settings, locator, runner)
        frames = RecapFrames(session, settings, fs, media)
        val share = object : FileShare {
            override fun open(path: String, mime: String) = PlatformResult.Ok(Unit).also { shared += "open:$path" }
            override fun share(path: String, mime: String, title: String) = PlatformResult.Ok(Unit).also { shared += "share:$path" }
            override suspend fun saveToGallery(path: String, mime: String, displayName: String) =
                PlatformResult.Ok(displayName).also { shared += "gallery:$path" }
        }
        val clipboard = object : Clipboard { override fun copyText(text: String) = PlatformResult.Ok(Unit).also { copied += text } }
        model = ExportModel(session, settings, fs, queue, share, null, scope, clipboard, frames = frames)
        recap = RecapModel(session, fs, media, null, scope, frames = frames)
    }
    fun bytes() = fixture.dir.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(fixture.dir).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
    }
    override fun close() { scope.cancel() }
}

@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class StudioExportLayoutTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}/bundled={3}")
        fun cases() = StudioStoryLayoutTest.cases()
    }
    @Test fun realScrollCopyDiscardAndNavigationKeepProjectBytesAndActionsUncovered() =
        runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
            ExportHarness().use { h ->
                val before = h.bytes(); var back = 0; var classic = 0
                val platformBack = object : NavigationEventInput() { fun press() = dispatchOnBackCompleted() }
                setContent { CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                    val dispatcher = requireNotNull(LocalNavigationEventDispatcherOwner.current).navigationEventDispatcher
                    DisposableEffect(dispatcher) { dispatcher.addInput(platformBack); onDispose { dispatcher.removeInput(platformBack) } }
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) {
                        StudioExportScreen(h.model, h.recap, width.toFloat(), RecapNavigation({ back++ }, {}, { classic++ },
                            handleBack = { enabled, action -> NavigationBackHandler(
                                rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = enabled, onBackCompleted = action) }))
                    }
                } }
                assertProjectPanesAndStickyActions(width, "export-render", "export-preview")
                // F-5 (D-94): the live preview (picture + voice + music) is on the Export step before any render, on a phone too.
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("timeline-play").fetchSemanticsNodes().isNotEmpty() }
                // Group 2 A §1: on a phone the top card's caption names picture + voice + music instead of the hint.
                if (width >= 840) onNodeWithTag("timeline-sound-hint").assertExists()
                else onNode(hasText(My.breakSyllables(UiText.V3_PREVIEW_SCOPE.text(language))) and hasAnyAncestor(hasTestTag("recap-top-preview"))).assertExists()
                fun click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                fun title() = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("publish-title")))
                fun capture(state: String) {
                    assertEquals(emptyList(), textFitProblems(width, font))
                    val action = onNodeWithTag("export-render").assertIsDisplayed().assertIsEnabled().getUnclippedBoundsInRoot()
                    val bar = onNodeWithTag("sticky-action-bar").getUnclippedBoundsInRoot()
                    assertTrue((action.bottom - action.top).value >= 48f && action.top >= bar.top && action.bottom <= bar.bottom)
                    val selected = bundled && width == 412 && font in listOf(1f, 2f) ||
                        width in listOf(360, 1440) || !bundled && language == UiLanguage.BURMESE && font == 2f && width in listOf(412, 840)
                    if (selected) {
                        val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "export").apply { mkdirs() }
                        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "07-export-$state-$width-${language.code}-$font-$bundled.png"))
                    }
                }
                repeat(5) { onNodeWithTag("progress-segment-$it").assertExists() }
                onNodeWithTag("export-output").assertExists()
                capture("output")
                onNodeWithTag("export-thumbs-make").performScrollTo().assertIsDisplayed().assertIsEnabled()
                val exact = onNodeWithTag("export-thumbs-make").getUnclippedBoundsInRoot()
                assertTrue(exact.bottom <= onNodeWithTag("sticky-action-bar").getUnclippedBoundsInRoot().top)
                capture("exact")
                onNodeWithContentDescription("Publish Kit").performScrollTo().assertIsDisplayed().performMouseInput { click() }
                title().performScrollTo().assertIsDisplayed().performMouseInput { click() }.performTextReplacement("UNSAVED QA")
                click("publish-copy-title"); assertEquals("UNSAVED QA", h.copied.last())
                click("publish-copy-description"); assertTrue(h.copied.last().contains("Offline fixture"))
                click("publish-copy-chapters"); assertTrue(h.copied.last().contains("FIRST"))
                click("publish-copy-hashtags"); assertEquals("#QA", h.copied.last())
                click("publish-discard")
                title().assertTextContains("QA export")
                onNodeWithTag("publish-save").performScrollTo().assertIsDisplayed()
                capture("publish")
                onNodeWithTag("publish-thumb-share").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
                assertTrue(onNodeWithTag("publish-thumb-share").getUnclippedBoundsInRoot().bottom <=
                    onNodeWithTag("sticky-action-bar").getUnclippedBoundsInRoot().top)
                onNodeWithTag("project-back").assertIsDisplayed().performMouseInput { click() }; assertEquals(1, back)
                runOnIdle { platformBack.press() }; assertEquals(2, back)
                onNodeWithTag("project-overflow").assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("classic-recap").assertIsDisplayed().performMouseInput { click() }; assertEquals(1, classic)
                assertTrue(h.queued.isEmpty()); assertEquals(before, h.bytes())
            }
        }
}

@OptIn(ExperimentalTestApi::class)
class StudioExportWorkflowTest {
    @Test fun qualityAndPublishSaveReopenAndProjectSwitchDiscardOldDraft() =
        runDesktopComposeUiTest(width = 412, height = 760) {
            ExportHarness().use { h ->
                setContent { CompositionLocalProvider(LocalCompact provides true, LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioExportScreen(h.model, h.recap, 412f, RecapNavigation({}, {}, {}))
                } }
                fun click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                fun title() = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("publish-title")))
                val artifacts = h.bytes().filterKeys { it.startsWith("artifacts/") }
                click("export-quality-P1080")
                assertEquals(ExportQuality.P1080, h.session.open!!.project.recap!!.quality)
                onNodeWithContentDescription("Publish Kit").performScrollTo().assertIsDisplayed().performMouseInput { click() }
                title().performScrollTo().assertIsDisplayed().performMouseInput { click() }.performTextReplacement("Saved QA")
                click("publish-save")
                val saved = (h.store.open(h.fixture.dir.path) as CoreResult.Ok).value
                assertEquals("Saved QA", saved.recap!!.publishKit!!.title)
                assertEquals(ExportQuality.P1080, saved.recap!!.quality)
                title().performScrollTo().assertIsDisplayed().performMouseInput { click() }.performTextReplacement("DO NOT COPY TO B")
                val b = QaRecapFixture.create(h.root, "QA Other")
                val beforeB = File(b.dir, "project.json").readBytes()
                runOnIdle { h.current.set(OpenProject(b.dir.path, b.project)) }
                waitUntil { h.model.state.value.projectDir == b.dir.path }
                onNodeWithContentDescription("Publish Kit").performScrollTo().assertIsDisplayed().performMouseInput { click() }
                click("publish-copy-title"); assertEquals("", h.copied.last())
                assertContentEquals(beforeB, File(b.dir, "project.json").readBytes())
                assertEquals(artifacts, h.bytes().filterKeys { it.startsWith("artifacts/") })
            }
        }

    @Test fun queuedRunningCancelFailureAndRealExactFrameRemainUserReachable() =
        runDesktopComposeUiTest(width = 412, height = 760) {
            ExportHarness().use { h ->
                setContent { CompositionLocalProvider(LocalCompact provides true, LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioExportScreen(h.model, h.recap, 412f, RecapNavigation({}, {}, {}))
                } }
                fun click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                fun action(tag: String) = onNodeWithTag(tag).assertIsDisplayed().performMouseInput { click() }
                fun capture(state: String) {
                    val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "export").apply { mkdirs() }
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "07-export-workflow-$state-412-en-1.0-true.png"))
                }
                click("export-thumbs-make")
                waitUntil(timeoutMillis = 180_000) { h.model.state.value.thumbs?.working == false }
                val thumbs = assertNotNull(h.model.state.value.thumbs); assertNull(thumbs.failed, thumbs.failed)
                assertTrue(thumbs.frames.isNotEmpty() && thumbs.frames.all { ImageIO.read(it.bytes.inputStream()) != null })
                onNodeWithTag("export-thumb-use-0").performScrollTo().assertIsDisplayed().assertIsEnabled()
                capture("exact")
                action("export-render"); assertEquals(Quality.EXPORT, h.model.state.value.run!!.quality)
                assertEquals("exports/export", h.queued.last().outputName)
                onNodeWithTag("export-render").assertIsNotEnabled()
                onNodeWithTag("export-status").performScrollTo().assertIsDisplayed()
                    .assertTextEquals(UiText.RENDER_QUEUED.text(UiLanguage.ENGLISH, "what" to UiText.EXPORT.en))
                capture("queued")
                runOnIdle { h.run.value = RenderState.Running(.42, 1.0) }
                onNodeWithTag("export-status").performScrollTo().assertIsDisplayed(); capture("running")
                onNodeWithText(UiText.CANCEL.en).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                assertEquals(listOf("render-1"), h.cancelled)
                action("export-preview"); assertEquals(Quality.PREVIEW, h.model.state.value.run!!.quality)
                assertEquals("exports/preview", h.queued.last().outputName)
                runOnIdle { h.run.value = RenderState.Failed("QA retained render failure") }
                onNodeWithTag("export-status").performScrollTo().assertIsDisplayed()
                onNodeWithText("QA retained render failure", substring = true).assertExists()
                onNodeWithTag("export-render").assertIsDisplayed().assertIsEnabled()
                capture("failed")
                action("export-render")
                val output = File(h.fixture.dir, "exports/export.mp4").apply { parentFile.mkdirs(); File(h.fixture.dir, "source/movie.mp4").copyTo(this) }
                runOnIdle { h.run.value = RenderState.Done(output.path, 5_500, 2_000, null) }
                onNodeWithText(UiText.OPEN.en).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                capture("done")
                onNodeWithText(UiText.SHARE.en).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                onNodeWithText(UiText.SAVE_GALLERY.en).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                assertEquals(listOf("open:${output.path}", "share:${output.path}", "gallery:${output.path}"), h.shared)
            }
        }
}