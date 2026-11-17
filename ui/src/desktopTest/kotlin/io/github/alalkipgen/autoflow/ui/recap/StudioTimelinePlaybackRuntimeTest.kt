package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.*
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import org.junit.AssumptionViolatedException
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

/** Real v3 container and pointer scrubber, separately installed native VLC, no fake player/Exact-frame loop. */
@OptIn(ExperimentalTestApi::class)
class StudioTimelinePlaybackRuntimeTest {
    @Test fun nativePlayerStartsAtSelectedBlockAndScrubsAllClipsThenDisposesOnSwitchAndLeave() {
        if (System.getenv("AUTOFLOW_EXPECT_VLC") != "1") throw AssumptionViolatedException("requires separately installed VLC runtime gate")
        val root = Files.createTempDirectory("uiv3-runtime").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val fixture = QaRecapFixture.create(root)
            val before = File(fixture.dir, "project.json").readBytes()
            val fs = JvmFs(); val store = ProjectStore(fs)
            val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, fixture.project)) }
            val settings = SettingsStore(fs, File(root, "settings.json").path)
            settings.update { it.copy(vlcPath = System.getenv("AUTOFLOW_VLC_DIR")) }
            val model = RecapModel(ProjectSession(fs, store, ProjectIndex(fs, File(root, "index.json").path), current), fs,
                MediaTools(settings, DesktopToolLocator(fs), null), null, scope)
            runDesktopComposeUiTest(width = 600, height = 900) {
                var show by mutableStateOf(true)
                setContent { CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family, LocalUiLanguage provides UiLanguage.ENGLISH) {
                    if (show) StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 600f) {
                        StudioRecapScreen(model, RecapStep.VOICE, 600f, {}, RecapNavigation({}, {}, {}))
                    }
                } }
                // Pump Compose frames explicitly; the native decoder keeps its real wall clock.
                mainClock.autoAdvance = false
                fun await(condition: () -> Boolean) = waitUntil(timeoutMillis = 15_000) { mainClock.advanceTimeByFrame(); condition() }
                fun position() = model.previewSource.currentPosition() ?: -1
                fun reach(tag: String): SemanticsNodeInteraction {
                    // performScrollTo's synchronous semantics loop queues ScrollBy coroutines without
                    // pumping a manually paused UI clock. Use real vertical gestures and settle only UI
                    // animations; libVLC still runs on wall time, not this virtual Compose clock.
                    repeat(20) {
                        mainClock.advanceTimeBy(500)
                        val nodes = onAllNodesWithTag(tag).fetchSemanticsNodes()
                        assertEquals(1, nodes.size, "one live $tag")
                        val target = nodes.single()
                        val viewport = onNodeWithTag("voice-content").fetchSemanticsNode().boundsInRoot
                        val y = target.positionInRoot.y
                        if (y >= viewport.top - .5f && y + target.size.height <= viewport.bottom + .5f)
                            return onNodeWithTag(tag).assertIsDisplayed()
                        val above = y < viewport.top
                        onNodeWithTag("voice-content").performTouchInput {
                            swipe(Offset(width * .5f, height * if (above) .25f else .75f),
                                Offset(width * .5f, height * if (above) .75f else .25f), 250)
                        }
                    }
                    error("real scrolling could not reach $tag")
                }
                fun click(tag: String) {
                    println("LIVE_V3_CLICK $tag at=${position()}")
                    reach(tag).performMouseInput { click() }; mainClock.advanceTimeBy(500)
                }
                fun playing() = onNodeWithTag("timeline-play").fetchSemanticsNode().config[SemanticsProperties.Text].any { it.text == UiText.PAUSE.en }
                fun pause() {
                    if (playing()) click("timeline-play")
                    await { !playing() }
                    val at = position(); Thread.sleep(200); mainClock.advanceTimeByFrame()
                    assertTrue(kotlin.math.abs(position() - at) < 100, "pause freezes the real output clock")
                }
                fun scrub(ms: Long) {
                    assertFalse(playing())
                    val node = reach("timeline-scrubber")
                    val w = node.fetchSemanticsNode().size.width
                    node.assertIsDisplayed().performMouseInput { click(Offset(12f + (w - 24) * ms / 5499f, 24f)) }
                    await { kotlin.math.abs(position() - ms) <= 2 }
                }
                fun pixels() = reach("timeline-video").captureToImage().toPixelMap()
                fun patch(ms: Long, red: Boolean, green: Boolean, blue: Boolean) {
                    println("LIVE_V3_SCRUB $ms")
                    scrub(ms)
                    await {
                        val p = pixels(); val c = p[(p.width * .1f).toInt(), (p.height * .1f).toInt()]
                        (if (red) c.red > .7f else c.red < .2f) && (if (green) c.green > .7f else c.green < .2f) && (if (blue) c.blue > .7f else c.blue < .2f)
                    }
                    val file = File("build/reports/playback/uiv3-$ms.png").apply { parentFile.mkdirs() }
                    ImageIO.write(onNodeWithTag("timeline-video").captureToImage().toAwtImage(), "png", file)
                }
                fun captionInk(): Int {
                    val p = pixels()
                    return (p.height * 85 / 100 until p.height).sumOf { y -> (p.width / 5 until p.width * 4 / 5).count { x ->
                        p[x, y].let { it.red > .88f && it.green > .88f && it.blue > .88f }
                    } }
                }
                click("voice-open-1"); await { position() == 4_000L }
                onNodeWithTag("timeline-time").assertTextEquals("Output 0:04 / 0:05")
                click("timeline-play"); await { position() > 4_250 }; pause()
                // Retain the pointer gates above/below; additionally exercise Space against the actual decoder.
                scrub(500)
                fun space() {
                    reach("timeline-time").performMouseInput { click() }
                    onNodeWithTag("project-shortcuts").assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
                    mainClock.advanceTimeByFrame()
                }
                space(); await { playing() && position() > 750 }
                space(); await { !playing() }
                val keyboardPausedAt = position(); Thread.sleep(200); mainClock.advanceTimeByFrame()
                assertTrue(kotlin.math.abs(position() - keyboardPausedAt) < 100, "Space pauses the real clock")
                patch(2500, true, true, false); val blankInk = captionInk()
                patch(500, false, true, false); await { captionInk() > blankInk + 20 }
                onNodeWithTag("timeline-video").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "FIRST"))
                patch(4500, false, false, true); await { captionInk() > blankInk + 20 }
                onNodeWithTag("timeline-video").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "SECOND"))
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/reports/playback/uiv3-container-4500.png"))
                click("timeline-play"); await { position() > 4_700 }; pause()
                click("timeline-close"); onNodeWithTag("timeline-video").assertDoesNotExist()
                click("timeline-play"); await { position() > 4_850 }
                val otherDir = File(root, "other.autoflow").path
                val other = (store.create(otherDir, "Other", Mode.MOVIE_RECAP) as CoreResult.Ok).value
                runOnIdle { current.set(OpenProject(otherDir, other)); model.refresh() }; mainClock.advanceTimeByFrame()
                onNodeWithTag("timeline-video").assertDoesNotExist(); assertNull(model.previewSource.currentPosition())
                runOnIdle { current.set(OpenProject(fixture.dir.path, fixture.project)); model.refresh() }; mainClock.advanceTimeByFrame()
                click("voice-open-1"); click("timeline-play"); await { position() > 4_200 }
                runOnIdle { show = false }; mainClock.advanceTimeByFrame()
                onNodeWithTag("timeline-video").assertDoesNotExist()
                println("PLAYBACK_UI_V3_EVIDENCE: real Studio container; stable block B starts at output 4000ms; pointer scrub to output 500/2500/4500 decodes green/yellow/blue (source 2500/6500/4500); actual FIRST/SECOND caption ink > blank; pause <100ms; resume; close; project switch; leave disposes player")
            }
            assertContentEquals(before, File(fixture.dir, "project.json").readBytes())
        } finally { scope.cancel(); root.deleteRecursively() }
    }
}