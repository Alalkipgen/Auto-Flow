package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.tool.*
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class StudioTakeWaveformIsolationTest {
    @Test fun lateRealDecodeCannotBeRelabelledAfterAProjectSwitchWithTheSameBlockIds() =
        runDesktopComposeUiTest(width = 412, height = 760) {
            val root = Files.createTempDirectory("waveform-switch").toFile()
            val a = TakeWaveformFixture.create(File(root, "a")); val b = TakeWaveformFixture.create(File(root, "b"), 2)
            val beforeA = TakeWaveformFixture.bytes(a.dir); val beforeB = TakeWaveformFixture.bytes(b.dir)
            val (session, current) = TakeWaveformFixture.session(a)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val commands = Collections.synchronizedList(mutableListOf<List<String>>())
            val real = StudioThumbnailsLayoutTest.tools(commands)
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val waiting = ToolRunner { cmd, dir, timeout, keep, line ->
                if ("s16le" in cmd && cmd[cmd.indexOf("-i") + 1].startsWith(a.dir.path)) {
                    started.complete(Unit)
                    // Deliberately uncooperative test port: even a late real result must never appear in B.
                    withContext(NonCancellable) { release.await(); real.run(cmd, dir, timeout, keep, line) }
                } else real.run(cmd, dir, timeout, keep, line)
            }
            val model = RecapModel(session, JvmFs(),
                io.github.alalkipgen.autoflow.ui.media.MediaTools(
                    io.github.alalkipgen.autoflow.ui.settings.SettingsStore(JvmFs(), File(root, "settings.json").path),
                    io.github.alalkipgen.autoflow.platform.DesktopToolLocator(JvmFs()), waiting),
                null, scope, thumbnailCacheDir = File(root, "cache").path)
            try {
                setContent { CompositionLocalProvider(LocalCompact provides true) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 412f) {
                        StudioRecapScreen(model, RecapStep.VOICE, 412f, {}, RecapNavigation({}, {}, {}))
                    }
                } }
                fun openTake() {
                    onNodeWithTag("voice-open-0").performScrollTo().assertIsDisplayed().performMouseInput { click() }
                    waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("take-waveform-slot").fetchSemanticsNodes().isNotEmpty() }
                    waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("take-waveform-slot").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("take-waveform-slot").performScrollTo().assertIsDisplayed()
                }
                openTake(); waitUntil(timeoutMillis = 20_000) { started.isCompleted }
                onNodeWithTag("take-waveform-ready").assertDoesNotExist()
                runOnIdle { current.set(OpenProject(b.dir.path, b.project)); model.refresh() }
                onNodeWithTag("take-waveform-ready").assertDoesNotExist()
                openTake(); release.complete(Unit)
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("take-waveform-ready").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("take-waveform-caption").performScrollTo().assertIsDisplayed()
                    .assertTextEquals(UiText.V3_WAVEFORM_MONO.text(UiLanguage.ENGLISH, "duration" to RecapModel.seconds(2_000)))
                assertEquals(beforeA, TakeWaveformFixture.bytes(a.dir)); assertEquals(beforeB, TakeWaveformFixture.bytes(b.dir))
                assertTrue(commands.any { "s16le" in it && it[it.indexOf("-i") + 1].startsWith(b.dir.path) })
            } finally { release.complete(Unit); scope.cancel() }
        }

    @Test fun unavailableDecoderKeepsHonestDurationFallbackPlayHistoryAndStickyControlsAtBurmeseDoubleFont() =
        runDesktopComposeUiTest(width = 360, height = 760) {
            val root = Files.createTempDirectory("waveform-unavailable").toFile(); val f = TakeWaveformFixture.create(root)
            val before = TakeWaveformFixture.bytes(f.dir); val (session, _) = TakeWaveformFixture.session(f)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val model = RecapModel(session, JvmFs(), null, null, scope, thumbnailCacheDir = File(root, "cache").path)
            try {
                setContent { CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(1f, 2f),
                    LocalUiLanguage provides UiLanguage.BURMESE, LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 360f) {
                        StudioRecapScreen(model, RecapStep.VOICE, 360f, {}, RecapNavigation({}, {}, {}))
                    }
                } }
                onNodeWithTag("voice-open-0").performScrollTo().assertIsDisplayed().performMouseInput { click() }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("take-waveform-caption").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("take-waveform-caption").performScrollTo().assertIsDisplayed()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("take-waveform-caption").fetchSemanticsNodes()
                    .any { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].any { t -> t.text == UiText.V3_WAVEFORM_UNAVAILABLE.text(UiLanguage.BURMESE) } } }
                onNodeWithTag("take-waveform-fallback").assertIsDisplayed()
                onNodeWithTag("take-waveform-ready").assertDoesNotExist()
                onNodeWithTag("voice-take-play-2").performScrollTo().assertIsDisplayed().assert(hasAnyDescendant(hasClickAction()))
                onNodeWithTag("voice-block-request").assertIsDisplayed().assertIsEnabled()
                val out = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "take-waveform").apply { mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "04-fallback-360-my-2.0-true.png"))
                onNodeWithTag("project-back").assertIsDisplayed().performMouseInput { click() }
                assertEquals(before, TakeWaveformFixture.bytes(f.dir))
            } finally { scope.cancel() }
        }

    @Test fun offscreenTakesDoNotDecodeAndAnExternalRevisionDiscardsTheVisibleOldWaveform() =
        runDesktopComposeUiTest(width = 412, height = 760) {
            val root = Files.createTempDirectory("waveform-visibility").toFile(); val f = TakeWaveformFixture.create(root)
            val (session, current) = TakeWaveformFixture.session(f); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val count = AtomicInteger(); val media = TakeWaveformFixture.media(root)
            val tracked = ToolRunner { cmd, dir, timeout, keep, line ->
                if ("s16le" in cmd) println("TAKE_WAVEFORM_REVISION: decode=${count.incrementAndGet()} input=${File(cmd[cmd.indexOf("-i") + 1]).name}")
                media.runner!!.run(cmd, dir, timeout, keep, line)
            }
            val model = RecapModel(session, JvmFs(),
                io.github.alalkipgen.autoflow.ui.media.MediaTools(
                    io.github.alalkipgen.autoflow.ui.settings.SettingsStore(JvmFs(), File(root, "tracked-settings.json").path),
                    io.github.alalkipgen.autoflow.platform.DesktopToolLocator(JvmFs()), tracked),
                null, scope, thumbnailCacheDir = File(root, "cache").path)
            try {
                setContent { CompositionLocalProvider(LocalCompact provides true) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 412f) {
                        StudioRecapScreen(model, RecapStep.VOICE, 412f, {}, RecapNavigation({}, {}, {}))
                    }
                } }
                onNodeWithTag("voice-open-0").performScrollTo().assertIsDisplayed().performMouseInput { click() }
                waitForIdle(); assertEquals(0, count.get(), "composition below the clipped viewport must not decode")
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("take-waveform-slot").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("take-waveform-slot").performScrollTo().assertIsDisplayed()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("take-waveform-ready").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(1, count.get())
                // Stimulus is an external saved revision, not an action secretly attached to the waveform.
                val r = f.project.recap!!; val older = r.takes.first { it.block == "stable-a" }
                val revised = (ProjectStore(JvmFs()).save(f.dir.path, f.project.copy(recap = r.copy(
                    chosenTakes = r.chosenTakes + ("stable-a" to older.audio)))) as CoreResult.Ok).value
                val afterExternalSave = TakeWaveformFixture.bytes(f.dir)
                runOnIdle { current.set(OpenProject(f.dir.path, revised)); model.refresh() }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("take-waveform-slot").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("take-waveform-slot").performScrollTo().assertIsDisplayed()
                try {
                    waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("take-waveform-ready").fetchSemanticsNodes().isNotEmpty() && count.get() == 2 }
                } catch (failure: Throwable) {
                    val evidence = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "take-waveform-failures").apply { mkdirs() }
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(evidence, "revision-failure.png"))
                    println("TAKE_WAVEFORM_REVISION_FAILURE: decodeCount=${count.get()}\n${onRoot().printToString()}")
                    throw failure
                }
                assertEquals(afterExternalSave, TakeWaveformFixture.bytes(f.dir))
                onNodeWithTag("voice-take-play-1").performScrollTo().assertIsDisplayed()
                onNodeWithTag("voice-take-2").assertDoesNotExist()
            } finally { scope.cancel() }
        }
}