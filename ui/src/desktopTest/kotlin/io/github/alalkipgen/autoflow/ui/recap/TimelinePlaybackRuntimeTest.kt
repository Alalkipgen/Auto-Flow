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
import androidx.compose.ui.graphics.toAwtImage
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
            val fixture = QaRecapFixture.create(root)
            val fs = JvmFs(); val store = ProjectStore(fs)
            val dir = fixture.dir.path; val saved = fixture.project
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
                    try { waitUntil(timeoutMillis = 5_000) {
                        val pixels = node.captureToImage().toPixelMap()
                        val c = pixels[(pixels.width * 0.1f).toInt(), (pixels.height * 0.1f).toInt()]
                        (if (red) c.red > 0.7f else c.red < 0.2f) && (if (green) c.green > 0.7f else c.green < 0.2f) && (if (blue) c.blue > 0.7f else c.blue < 0.2f)
                    } } catch (e: Throwable) {
                        val file = File("build/reports/playback/failed-seek.png").apply { parentFile.mkdirs() }
                        javax.imageio.ImageIO.write(node.captureToImage().toAwtImage(), "png", file)
                        println(onRoot().printToString())
                        throw e
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

    @Test fun portraitLiveAllLooksImageBackgroundAndTimedOutputPixels() = renderedOutput("portrait", Ratio.R9_16, Look.entries, BackgroundMode.IMAGE)
    @Test fun squareLiveMirrorZoomBlurBackgroundAndTimedOutputPixels() = renderedOutput("square", Ratio.R1_1, listOf(Look.MIRROR, Look.ZOOM), BackgroundMode.BLUR)

    private fun renderedOutput(name: String, ratio: Ratio, looks: List<Look>, background: BackgroundMode) {
        if (System.getenv("AUTOFLOW_EXPECT_VLC") != "1") throw AssumptionViolatedException("requires separately installed VLC runtime gate")
        val root = Files.createTempDirectory("af-live-output-$name").toFile()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val fixture = QaRecapFixture.create(root, "Live $name", ratio, looks, background)
            val fs = JvmFs(); val store = ProjectStore(fs)
            // Real 3-second WAVs give screenshot automation a representative live cue window; the original
            // one-second fixture test still checks FIRST/SECOND/blank at its original output seeks above.
            val artifacts = ArtifactStore(fs, fixture.dir.path)
            val takes = fixture.project.recap!!.takes.mapIndexed { i, take ->
                val pcm = FloatArray(48_000) { n -> (.15 * kotlin.math.sin(2 * kotlin.math.PI * (440 + 220 * i) * n / 16_000)).toFloat() }
                val wav = (artifacts.put(io.github.alalkipgen.autoflow.media.audio.PcmWav.encode(16_000, pcm), ArtifactKind.AUDIO, "wav") as CoreResult.Ok).value
                take.copy(audio = wav.id, durationMs = 3000)
            }
            // Keep native TEXT away from the caption ROI, so pixel ink proves actual captions, not another label.
            val recap = fixture.project.recap!!.let { r -> r.copy(takes = takes, chosenTakes = takes.associate { it.block to it.audio }, layers = r.layers.map { l -> when (l.type) {
                LayerType.TEXT -> l.copy(rect = listOf(.1f, .25f, .7f, .15f))
                LayerType.IMAGE -> l.copy(params = l.params.copy(opacity = 1f))
                LayerType.BORDER -> l.copy(params = l.params.copy(px = 8))
                else -> l
            } }) }
            val saved = (store.save(fixture.dir.path, fixture.project.copy(recap = recap)) as CoreResult.Ok).value
            val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, saved)) }
            val settings = SettingsStore(fs, File(root, "settings.json").path)
            settings.update { it.copy(vlcPath = System.getenv("AUTOFLOW_VLC_DIR")) }
            val media = MediaTools(settings, DesktopToolLocator(fs), null)
            val model = RecapModel(ProjectSession(fs, store, ProjectIndex(fs, File(root, "index.json").path), current), fs, media, null, scope).also { it.refresh() }
            val output = io.github.alalkipgen.autoflow.media.render.RenderPresets.preview(ratio)
            val captionY = io.github.alalkipgen.autoflow.media.render.RecapRender.captionY(recap, output) ?: .92f
            val afterCaptions = model.previewSource.resolve(model.state.value)!!.timeline.captions.last().endMs + 100
            runDesktopComposeUiTest(width = 520, height = 900) {
                setContent { CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { RecapTimelinePreview(model) }
                } }
                // libVLC uses real wall time. Auto-advancing Compose's idle clock can chase continuous
                // decoded-frame recompositions until a clip ends, hiding the live interval from assertions.
                // Pump a UI frame explicitly; never freeze, seek or slow the native decoder for a live check.
                mainClock.autoAdvance = false
                fun await(timeoutMillis: Long, condition: () -> Boolean) = waitUntil(timeoutMillis = timeoutMillis) {
                    mainClock.advanceTimeByFrame()
                    condition()
                }
                fun position(): Long = onNodeWithTag("timeline-position").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }.substringBefore(" /").toLong()
                fun click(tag: String) {
                    mainClock.advanceTimeByFrame()
                    val node = onNodeWithTag(tag).performScrollTo()
                    mainClock.advanceTimeByFrame()
                    node.assertIsDisplayed().performMouseInput { click() }
                    mainClock.advanceTimeByFrame()
                }
                fun isPlaying(): Boolean = onNodeWithTag("timeline-play").fetchSemanticsNode().config[SemanticsProperties.Text].any { it.text.contains("⏸") }
                fun pause() {
                    if (isPlaying()) click("timeline-play") // do not accidentally restart a clip that already reached EOF
                    try { await(timeoutMillis = 5_000) { !isPlaying() } }
                    catch (failure: Throwable) {
                        val file = File("build/reports/playback/$name-failed-pause.png").apply { parentFile.mkdirs() }
                        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
                        println("LIVE_PAUSE_FAILURE $name position=${position()} playing=${isPlaying()} button=${onNodeWithTag("timeline-play").fetchSemanticsNode().boundsInRoot}")
                        throw failure
                    }
                    val at = position(); Thread.sleep(150); waitForIdle()
                    assertTrue(kotlin.math.abs(position() - at) < 100, "real output clock freezes after user pause")
                }
                fun seek(ms: Long) {
                    assertFalse(isPlaying(), "test seeks below deliberately exercise paused seeking")
                    onNodeWithContentDescription("Output ms").performScrollTo().performTextReplacement(ms.toString()); click("timeline-seek"); waitForIdle()
                    assertEquals(ms, position())
                    println("LIVE_SEEK $name requested=$ms actual=${position()} playing=${isPlaying()}")
                }
                fun pixels(): androidx.compose.ui.graphics.PixelMap {
                    val node = onNodeWithTag("timeline-video").performScrollTo()
                    mainClock.advanceTimeByFrame()
                    return node.assertIsDisplayed().captureToImage().toPixelMap()
                }
                fun ink(): Int {
                    val p = pixels()
                    return ((p.height * (captionY - .06f)).toInt().coerceAtLeast(0) until (p.height * (captionY + .06f)).toInt().coerceAtMost(p.height)).sumOf { y ->
                        (p.width / 5 until p.width * 4 / 5).count { x -> p[x, y].let { it.red > .88f && it.green > .88f && it.blue > .88f } }
                    }
                }
                fun evidence(ms: Long) {
                    val file = File("build/reports/playback/$name-$ms.png").apply { parentFile.mkdirs() }
                    javax.imageio.ImageIO.write(onNodeWithTag("timeline-video").performScrollTo().captureToImage().toAwtImage(), "png", file)
                }
                click("timeline-play"); await(timeoutMillis = 20_000) { position() > 200 }; pause()
                seek(150)
                await(timeoutMillis = 5_000) {
                    val p = pixels(); val c = p[(p.width * .565625f).toInt(), (p.height * (.1f + .015625f * output.width / output.height)).toInt()]
                    c.red > .9f && c.green in .40f.. .60f && c.blue < .1f
                }
                val blankInk = ink(); evidence(150)
                seek(500); onNodeWithTag("timeline-caption").assertTextEquals("FIRST")
                await(timeoutMillis = 5_000) { ink() > blankInk + 20 }; evidence(500)
                click("timeline-play")
                val liveSamples = mutableListOf<String>()
                try { await(timeoutMillis = 5_000) {
                    val at = position(); val count = if (at in 550..3150) ink() else -1
                    val running = isPlaying()
                    liveSamples += "$at/$running/$count"
                    running && at in 550..3150 && count > blankInk + 20
                } } catch (failure: Throwable) {
                    evidence(position())
                    println("LIVE_FAILURE $name baselineInk=$blankInk position/playing/ink=${liveSamples.takeLast(48)}")
                    throw failure
                }
                evidence(position()) // actual glyph pixels WHILE playing and FIRST is still active, not after it ends
                pause()
                seek(1500); click("timeline-play"); await(timeoutMillis = 10_000) { position() > 2200 }; pause()
                await(timeoutMillis = 5_000) { pixels()[1, 1].let { it.red < .1f && it.green > .9f && it.blue > .9f } }
                evidence(2500) // native BORDER activated while decoder/output clock crossed 2000ms
                seek(4500); onNodeWithTag("timeline-caption").assertTextEquals("SECOND")
                await(timeoutMillis = 5_000) { ink() > blankInk + 20 }; evidence(4500)
                val secondInk = ink()
                seek(afterCaptions); onNodeWithTag("timeline-caption").assertTextEquals("—")
                await(timeoutMillis = 5_000) { ink() < secondInk - 20 }; evidence(afterCaptions)
                click("timeline-close"); onNodeWithTag("timeline-video").assertDoesNotExist()
                println("PLAYBACK_EVIDENCE: $name native output aspect; existing VLC decoder; opaque IMAGE pixels; caption ink FIRST/SECOND/blank; live caption/border transitions; Looks=$looks; background=$background; close")
            }
        } finally { scope.cancel(); root.deleteRecursively() }
    }

}
