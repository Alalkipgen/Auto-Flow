package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.graphics.toPixelMap
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.*
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.DesktopToolLocator
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import org.junit.AssumptionViolatedException
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Real production screen + callback video surface + native decoder. Still S10 tests do not stand in for this. */
@OptIn(ExperimentalTestApi::class)
class TimelinePlaybackRuntimeTest {
    @Test fun embeddedFramesAndOutputClockCaptionSeekPauseResumeAndScreenDisposal() {
        if (System.getenv("AUTOFLOW_EXPECT_VLC") != "1") throw AssumptionViolatedException("requires separately installed VLC runtime gate")
        val root = Files.createTempDirectory("af-live-ui").toFile()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val fs = JvmFs(); val store = ProjectStore(fs); val dir = File(root, "live.autoflow").path
            val p = (store.create(dir, "Live", Mode.MOVIE_RECAP) as CoreResult.Ok).value
            val movie = File(dir, "source/movie.mp4").apply { parentFile.mkdirs() }
            // Encoded source-time colour patch lets assertions measure decoded pictures, not only a shared clock.
            val patches = listOf("0xff0000", "0x00ff00", "0x0000ff", "0xffff00", "0xff00ff").mapIndexed { i, colour ->
                "drawbox=x=0:y=0:w=80:h=60:color=$colour:t=fill:enable='gte(t,${i * 2})*lt(t,${i * 2 + 2})'"
            }.joinToString(",")
            val process = ProcessBuilder(System.getenv("AUTOFLOW_FFMPEG") ?: "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=25",
                "-t", "10", "-vf", patches, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25", movie.path).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText(); assertEquals(0, process.waitFor(), log)
            val story = RecapStory(source = "manual", blocks = listOf(
                RecapBlock("stable-a", listOf(RecapClip(2_000, 4_000), RecapClip(6_000, 8_000)), "FIRST"),
                RecapBlock("stable-b", listOf(RecapClip(4_000, 5_500)), "SECOND"),
            ))
            val artifacts = ArtifactStore(fs, dir)
            val script = (artifacts.put(CoreJson.encodeToString(RecapStory.serializer(), story).encodeToByteArray(), ArtifactKind.RECAP_SCRIPT, "json") as CoreResult.Ok).value
            val takes = story.blocks.mapIndexed { i, block ->
                // Only file presence is resolved in muted movie mode; this test never pretends to preview voice audio.
                val audio = (artifacts.put(byteArrayOf(1, 2, i.toByte()), ArtifactKind.AUDIO, "wav") as CoreResult.Ok).value
                BlockTake(block.id, audio.id, TakeSource.RECORDED, durationMs = 1_000, spokenHash = SpokenText.hash(block))
            }
            val r = RecapV2(movie = RecapMovie("source/movie.mp4", movie.length(), 10_000, 320, 180), story = script.id, storyApproved = true, takes = takes,
                visual = RecapVisual(outputRatio = Ratio.R16_9, masks = listOf(RecapMask(MaskPreset.BOTTOM, MaskPreset.BOTTOM.rect))))
            val saved = (store.save(dir, p.copy(recap = r)) as CoreResult.Ok).value
            val current = CurrentProject().apply { set(OpenProject(dir, saved)) }
            val settings = SettingsStore(fs, File(root, "settings.json").path)
            settings.update { it.copy(vlcPath = System.getenv("AUTOFLOW_VLC_DIR")) }
            val media = MediaTools(settings, DesktopToolLocator(fs), null)
            val model = RecapModel(ProjectSession(fs, store, ProjectIndex(fs, File(root, "index.json").path), current), fs, media, null, scope).also { it.refresh() }
            runDesktopComposeUiTest(width = 600, height = 900) {
                var show by mutableStateOf(true)
                setContent {
                    CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { if (show) RecapTimelinePreview(model) }
                    }
                }
                fun position(): Long = onNodeWithTag("timeline-position").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }.substringBefore(" /").toLong()
                fun click(tag: String) { onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() } }
                click("timeline-play")
                try { waitUntil(timeoutMillis = 20_000) { position() > 200 } }
                catch (e: Throwable) { println(onRoot().printToString()); throw e }
                click("timeline-play") // pause the real decoder, not just the text clock
                val paused = position()
                Thread.sleep(300); waitForIdle(); assertTrue(kotlin.math.abs(position() - paused) < 100)
                fun seek(ms: String) {
                    onNodeWithContentDescription("Output ms").performScrollTo().performTextReplacement(ms)
                    click("timeline-seek")
                }
                fun decodedPatch(red: Boolean, green: Boolean, blue: Boolean) {
                    val node = onNodeWithTag("timeline-video").performScrollTo().assertIsDisplayed()
                    waitUntil(timeoutMillis = 5_000) {
                        val pixels = node.captureToImage().toPixelMap()
                        val c = pixels[(pixels.width * 0.1f).toInt(), (pixels.height * 0.1f).toInt()]
                        (if (red) c.red > 0.7f else c.red < 0.2f) && (if (green) c.green > 0.7f else c.green < 0.2f) && (if (blue) c.blue > 0.7f else c.blue < 0.2f)
                    }
                }
                seek("150"); waitForIdle(); onNodeWithTag("timeline-caption").assertTextEquals("—"); decodedPatch(false, true, false)
                seek("500"); waitForIdle(); decodedPatch(false, true, false)
                onNodeWithTag("timeline-caption").assertTextEquals("FIRST")
                Thread.sleep(250)
                val first = onNodeWithTag("timeline-video").performScrollTo().captureToImage().toPixelMap()
                val colorful = (0 until first.height step 4).sumOf { y -> (0 until first.width step 4).count { x ->
                    val c = first[x, y]; maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue) > 0.2f
                } }
                assertTrue(colorful > 200, "actual native video pixels rendered through Compose, not an empty surface")
                seek("4_500".replace("_", "")); waitForIdle()
                onNodeWithTag("timeline-caption").assertTextEquals("SECOND"); decodedPatch(false, false, true)
                assertEquals(4_500L, position(), "second block source 4.5s maps to OUTPUT 4.5s, not first-clip looping")
                seek("2_500".replace("_", "")); waitForIdle()
                onNodeWithTag("timeline-caption").assertTextEquals("—"); decodedPatch(true, true, false)
                click("timeline-play"); waitUntil(timeoutMillis = 10_000) { position() > 2_700 }
                click("timeline-close"); onNodeWithTag("timeline-video").assertDoesNotExist()
                click("timeline-play"); waitUntil(timeoutMillis = 10_000) { position() > 2_900 }
                // Project replacement removes the old source and its player. A late cursor must not leak into B.
                val otherDir = File(root, "other.autoflow").path
                val other = (store.create(otherDir, "Other", Mode.MOVIE_RECAP) as CoreResult.Ok).value
                runOnIdle { current.set(OpenProject(otherDir, other)); model.refresh() }; waitForIdle()
                onNodeWithTag("timeline-video").assertDoesNotExist()
                assertNull(model.previewSource.currentPosition())
                runOnIdle { current.set(OpenProject(dir, saved)); model.refresh() }; waitForIdle()
                click("timeline-play"); waitUntil(timeoutMillis = 10_000) { position() > 200 }
                runOnIdle { show = false }; waitForIdle() // real DisposableEffect tears down the native player
                onNodeWithTag("timeline-video").assertDoesNotExist()
                println("PLAYBACK_UI_EVIDENCE: Compose native pixels=$colorful; decoded source-time patches green/blue/yellow match output seek FIRST/SECOND/no-cue; pause <100ms; resume; stop/reopen; project switch; screen dispose")
            }
        } finally { scope.cancel(); root.deleteRecursively() }
    }
}
