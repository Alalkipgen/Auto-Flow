package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.media.tool.*
import io.github.alalkipgen.autoflow.platform.*
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class StudioThumbnailsVisibilityTest {
    @Test fun viewportNotCompositionStartsDecodingAndWheelPagingAndPicksStillWork() =
        runDesktopComposeUiTest(width = 840, height = 760) {
            val root = Files.createTempDirectory("uiv3-lazy-thumbnails").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val fs = JvmFs(); val store = ProjectStore(fs); val f = QaRecapFixture.create(root)
                val project = (store.save(f.dir.path, f.project.copy(recap = f.project.recap!!.copy(
                    scenes = List(100) { RecapScene(it * 100L, (it + 1) * 100L) }))) as CoreResult.Ok).value
                val before = StudioThumbnailsLayoutTest.bytes(f.dir)
                val current = CurrentProject().apply { set(OpenProject(f.dir.path, project)) }
                val commands = Collections.synchronizedList(mutableListOf<List<String>>())
                val model = RecapModel(ProjectSession(fs, store, ProjectIndex(fs, File(root, "index.json").path), current), fs,
                    MediaTools(SettingsStore(fs, File(root, "settings.json").path), DesktopToolLocator(fs),
                        StudioThumbnailsLayoutTest.tools(commands)), null, scope, thumbnailCacheDir = File(root, "cache").path)
                setContent { CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 840f) {
                        StudioRecapScreen(model, RecapStep.SOURCE, 840f, {}, RecapNavigation({}, {}, {}))
                    }
                } }
                fun waitImage(index: Int) {
                    try {
                        waitUntil(timeoutMillis = 20_000) {
                            onAllNodesWithTag("scene-thumbnail-$index", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                        }
                    } catch (failure: Throwable) {
                        println("Thumbnail $index commands: ${commands.toList()}")
                        println(onRoot().printToString())
                        throw failure
                    }
                }
                onNodeWithTag("scene-pick-0").performScrollTo().assertIsDisplayed(); waitImage(0)
                assertTrue(commands.size < 30, "composing a page must not decode the entire page")
                fun seeks() = synchronized(commands) { commands.map { it[it.indexOf("-ss") + 1] } }
                assertFalse("2.850" in seeks(), "an offscreen row must not be decoded")
                onNodeWithTag("source-picks").performMouseInput { scroll(20_000f) }
                onNodeWithTag("scene-pick-28").performScrollTo().assertIsDisplayed(); waitImage(28)
                assertTrue("2.850" in seeks())
                assertEquals(before, StudioThumbnailsLayoutTest.bytes(f.dir), "decoding and scrolling never write any project file")
                onNodeWithTag("scene-pick-28").assertIsDisplayed().performMouseInput { click() }
                assertTrue(model.state.value.recap!!.scenes[28].picked, "thumbnail remains decorative; parent checkbox handles real pointer")
                val afterPick = StudioThumbnailsLayoutTest.bytes(f.dir)
                assertEquals(before.keys, afterPick.keys, "picking must not create thumbnail files in the project")
                assertNotEquals(before["project.json"], afterPick["project.json"], "the existing explicit pick action saves normally")
                assertEquals(before["project.json"], afterPick["project.json.bak"], "the existing atomic-save backup is the exact pre-pick file")
                val savedFiles = setOf("project.json", "project.json.bak", ".cache/project.lock")
                assertEquals(before - savedFiles, afterPick - savedFiles, "all source and immutable artifacts remain identical after picking")
                onNodeWithTag("source-page-next").performScrollTo().assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("scene-pick-0").assertDoesNotExist()
                onNodeWithTag("scene-pick-30").performScrollTo().assertIsDisplayed(); waitImage(30)
                onNodeWithTag("source-page-back").performScrollTo().assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("source-picks").performMouseInput { scroll(-20_000f) }
                onNodeWithTag("scene-pick-0").performScrollTo().assertIsDisplayed(); waitImage(0)
                assertProjectPanesAndStickyActions(840, "recap-next")
            } finally { scope.cancel(); root.deleteRecursively() }
        }

    @Test fun aLateOldProjectDecodeNeverAttachesToTheNewProjectsBlock() =
        runDesktopComposeUiTest(width = 840, height = 760) {
            val root = Files.createTempDirectory("uiv3-thumbnail-switch").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val release = CompletableDeferred<Unit>()
            try {
                val fs = JvmFs(); val a = QaRecapFixture.create(root, "QA A"); val b = QaRecapFixture.create(root, "QA B")
                val beforeA = StudioThumbnailsLayoutTest.bytes(a.dir); val beforeB = StudioThumbnailsLayoutTest.bytes(b.dir)
                val current = CurrentProject().apply { set(OpenProject(a.dir.path, a.project)) }
                val started = CompletableDeferred<Unit>()
                val tools = ToolRunner { cmd, _, _, _, _ ->
                    val old = cmd[cmd.indexOf("-i") + 1].startsWith(a.dir.path)
                    if (old) {
                        started.complete(Unit)
                        // Deliberately late fake decoder completion, even after UI cancellation.
                        withContext(NonCancellable) { release.await() }
                    }
                    val image = BufferedImage(240, 136, BufferedImage.TYPE_INT_RGB)
                    image.createGraphics().apply {
                        color = if (old) java.awt.Color.RED else java.awt.Color.BLUE
                        fillRect(0, 0, 240, 136); dispose()
                    }
                    fs.writeDurably(cmd.last(), ByteArrayOutputStream().also { check(ImageIO.write(image, "jpg", it)) }.toByteArray())
                    ToolExec.Done(0, "", emptyList(), 1, false)
                }
                val model = RecapModel(ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, File(root, "index.json").path), current),
                    fs, MediaTools(SettingsStore(fs, File(root, "settings.json").path), DesktopToolLocator(fs), tools), null, scope,
                    thumbnailCacheDir = File(root, "cache").path)
                setContent { CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 840f) {
                        StudioRecapScreen(model, RecapStep.STORY, 840f, {}, RecapNavigation({}, {}, {}))
                    }
                } }
                onNodeWithTag("block-select-0").performScrollTo().assertIsDisplayed()
                waitUntil(timeoutMillis = 10_000) { started.isCompleted }
                // AppShell refreshes the shared model after opening a different project.
                runOnIdle { current.set(OpenProject(b.dir.path, b.project)); model.refresh() }
                onNodeWithTag("block-select-0").performScrollTo().assertIsDisplayed()
                onNodeWithTag("block-thumbnail-stable-a", useUnmergedTree = true).assertDoesNotExist()
                release.complete(Unit)
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithTag("block-thumbnail-stable-a", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                val image = onNodeWithTag("block-thumbnail-stable-a", useUnmergedTree = true)
                    .assertIsDisplayed().captureToImage().toAwtImage()
                val pixel = image.getRGB(image.width / 2, image.height / 2)
                assertTrue((pixel and 255) > 200 && ((pixel ushr 16) and 255) < 30, "new project's blue image, never old project's red")
                assertEquals(beforeA, StudioThumbnailsLayoutTest.bytes(a.dir)); assertEquals(beforeB, StudioThumbnailsLayoutTest.bytes(b.dir))
            } finally { release.complete(Unit); scope.cancel(); root.deleteRecursively() }
        }

    @Test fun unavailableDecoderKeepsRealTimesAndExistingPointerNavigationWithoutProjectWrites() =
        runDesktopComposeUiTest(width = 412, height = 760) {
            val root = Files.createTempDirectory("uiv3-thumbnail-fallback").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val fs = JvmFs(); val store = ProjectStore(fs); val f = QaRecapFixture.create(root)
                val project = (store.save(f.dir.path, f.project.copy(recap = f.project.recap!!.copy(
                    scenes = listOf(RecapScene(2_000, 4_000, true))))) as CoreResult.Ok).value
                val before = StudioThumbnailsLayoutTest.bytes(f.dir)
                val current = CurrentProject().apply { set(OpenProject(f.dir.path, project)) }
                val calls = java.util.concurrent.atomic.AtomicInteger()
                val runner = ToolRunner { _, _, _, _, _ -> calls.incrementAndGet(); ToolExec.CannotStart("offline unavailable decoder") }
                val model = RecapModel(ProjectSession(fs, store, ProjectIndex(fs, File(root, "index.json").path), current), fs,
                    MediaTools(SettingsStore(fs, File(root, "settings.json").path), DesktopToolLocator(fs), runner), null, scope,
                    thumbnailCacheDir = File(root, "cache").path)
                var step by mutableStateOf(RecapStep.SOURCE)
                setContent { CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 412f) {
                        StudioRecapScreen(model, step, 412f, {}, RecapNavigation({},
                            { step = if (step == RecapStep.SOURCE) RecapStep.STORY else RecapStep.VOICE }, {}))
                    }
                } }
                onNodeWithTag("scene-pick-0").performScrollTo().assertIsDisplayed()
                waitUntil(timeoutMillis = 10_000) { calls.get() > 0 }
                onNodeWithText("0:02–0:04").assertIsDisplayed()
                onNodeWithTag("scene-thumbnail-0", useUnmergedTree = true).assertDoesNotExist()
                assertProjectPanesAndStickyActions(412, "recap-next")
                val capture = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "thumbnails")
                    .apply { mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(capture, "02-source-unavailable-412-en.png"))
                onNodeWithTag("recap-next").assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("block-select-0").performScrollTo().assertIsDisplayed()
                onNodeWithTag("block-thumbnail-stable-a", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithTag("block-select-0").performMouseInput { click() }
                onNodeWithTag("project-back").assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("recap-next").assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("voice-open-0").performScrollTo().assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("project-back").assertIsDisplayed().performMouseInput { click() }
                assertProjectPanesAndStickyActions(412, "voice-remaining", "recap-next")
                assertEquals(before, StudioThumbnailsLayoutTest.bytes(f.dir), "unavailable thumbnails never save a project or block existing navigation")
            } finally { scope.cancel(); root.deleteRecursively() }
        }
}
