package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.DesktopToolLocator
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.ProcessRunner
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.*

/** Additive observation only: never change the old waveform test's readiness or hide its first capture. */
@OptIn(ExperimentalTestApi::class)
class StudioThumbnailObservationTest {
    @Test fun retainWaveformReadySnapshotThenBoundedActualThumbnailOutcomesWithoutSaving() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            val root = Files.createTempDirectory("thumbnail-observation").toFile()
            val f = TakeWaveformFixture.create(root)
            val before = TakeWaveformFixture.bytes(f.dir)
            val (session, _) = TakeWaveformFixture.session(f)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val evidence = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "thumbnail-observation").apply { mkdirs() }
            val trace = File(evidence, "tool-events.jsonl")
            val monitor = Any(); val start = System.nanoTime()
            fun elapsed() = (System.nanoTime() - start) / 1_000_000
            fun log(event: JsonObject) = synchronized(monitor) { trace.appendText(event.toString() + "\n") }
            val runner = ToolRunner { cmd, wd, timeout, keep, onLine ->
                assertFalse(javax.swing.SwingUtilities.isEventDispatchThread(), "real media work stays off the UI thread")
                log(buildJsonObject { put("event", "start"); put("atMs", elapsed()); put("argv", JsonArray(cmd.map(::JsonPrimitive))); put("workDir", wd); put("timeoutMs", timeout) })
                when (val result = ProcessRunner.run(cmd, File(wd), timeout, keep, onLine)) {
                    is ProcessRunner.Exec.Done -> {
                        log(buildJsonObject { put("event", "done"); put("atMs", elapsed()); put("argv", JsonArray(cmd.map(::JsonPrimitive))); put("exitCode", result.exitCode); put("timedOut", result.timedOut); put("wallMs", result.wallMs); put("output", result.output); put("tail", JsonArray(result.tail.map(::JsonPrimitive))) })
                        ToolExec.Done(result.exitCode, result.output, result.tail, result.wallMs, result.timedOut)
                    }
                    is ProcessRunner.Exec.CannotStart -> {
                        log(buildJsonObject { put("event", "cannot-start"); put("atMs", elapsed()); put("message", result.message) })
                        ToolExec.CannotStart(result.message)
                    }
                }
            }
            val fs = JvmFs()
            val media = MediaTools(SettingsStore(fs, File(root, "settings.json").path), DesktopToolLocator(fs), runner)
            assertNotNull(media.ffmpeg(), "this observation requires the actual ffmpeg, never an assumed thumbnail")
            val cache = File(root, "app-cache")
            val model = RecapModel(session, fs, media, null, scope, thumbnailCacheDir = cache.path)
            val tags = listOf("block-thumbnail-stable-a", "block-thumbnail-stable-b")
            fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            fun snapshot(label: String) {
                val state = buildJsonObject {
                    put("event", label); put("atMs", elapsed())
                    tags.forEach { put(it, onAllNodesWithTag(it, useUnmergedTree = true).fetchSemanticsNodes().size) }
                    put("cache", JsonArray(cache.walkTopDown().filter { it.isFile }.map { p -> buildJsonObject {
                        put("file", p.relativeTo(cache).invariantSeparatorsPath)
                        runCatching { put("bytes", p.length()); put("sha256", hash(p)) }
                            .onFailure { put("observation", "temporary cache entry changed during snapshot: ${it::class.simpleName}") }
                    } }.toList()))
                }
                log(state)
            }
            fun capture(name: String) {
                assertEquals(emptyList(), textFitProblems(1440, 1f))
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(evidence, "$name-1440-en-1.0-true.png"))
            }
            try {
                setContent { CompositionLocalProvider(LocalCompact provides false, LocalDensity provides Density(1f, 1f),
                    LocalUiLanguage provides UiLanguage.ENGLISH, LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 1440f) {
                        StudioRecapScreen(model, RecapStep.VOICE, 1440f, {}, RecapNavigation({}, {}, {}))
                    }
                } }
                onNodeWithTag("voice-open-0").performScrollTo().assertIsDisplayed().performMouseInput { moveTo(center); click(); exit() }
                // Same existing20s waveform-ready checkpoint. No thumbnail wait precedes this first snapshot/capture.
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("take-waveform-slot").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("take-waveform-slot").performScrollTo().assertIsDisplayed()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("take-waveform-ready").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("take-waveform-caption").performScrollTo().assertIsDisplayed()
                snapshot("waveform-ready-before-first-capture"); capture("04-first-waveform-ready")
                snapshot("after-first-capture")
                // Separate additive diagnostic, not an added delay/readiness condition in any existing test.
                waitUntil(timeoutMillis = 20_000) { tags.all { onAllNodesWithTag(it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
                tags.forEach { onNodeWithTag(it, useUnmergedTree = true).assertIsDisplayed() }
                snapshot("both-actual-thumbnail-tags-visible"); capture("04-eventual-thumbnails")
                val savedCache = File(evidence, "cache").apply { mkdirs() }
                cache.listFiles().orEmpty().filter { it.isFile && it.extension == "bin" }.forEach { record ->
                    val bytes = record.readBytes(); val prefix = "autoflow-scene-thumb-v1\n"
                    assertTrue(bytes.copyOfRange(0, prefix.length).decodeToString() == prefix)
                    val jpeg = bytes.copyOfRange(prefix.length + 65, bytes.size)
                    val file = File(savedCache, record.nameWithoutExtension + ".jpg").apply { writeBytes(jpeg) }
                    val image = assertNotNull(ImageIO.read(file)); assertEquals(240, image.width); assertTrue(image.height > 0)
                    record.copyTo(File(savedCache, record.name))
                }
                assertTrue(savedCache.listFiles().orEmpty().any { it.extension == "jpg" }, "retain real cache payload, allowing documented64-slot key collisions")
                assertEquals(before, TakeWaveformFixture.bytes(f.dir), "all project/artifact bytes remain unchanged")
                println("THUMBNAIL_OBSERVATION: first and eventual captures retained separately; two actual displayed thumbnail tags; no production/readiness changes or cause claim; trace=${trace.path}")
            } catch (failure: Throwable) {
                runCatching { snapshot("failure"); capture("04-failure") }
                throw failure
            } finally { scope.cancel() }
        }
}
