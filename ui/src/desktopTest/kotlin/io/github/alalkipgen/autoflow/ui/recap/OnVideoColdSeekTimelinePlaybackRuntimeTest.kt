package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.platform.DesktopToolLocator
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.export.ExportHarness
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import org.junit.AssumptionViolatedException
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Cold real-decoder regression for the stale red priming frame retained in CI run 44273229930. */
@OptIn(ExperimentalTestApi::class)
class OnVideoColdSeekTimelinePlaybackRuntimeTest {
    @Test fun coldStyleEditsNeverAcceptAPrimingFrameForTheRequestedOutputTime() {
        if (System.getenv("AUTOFLOW_EXPECT_VLC") != "1") throw AssumptionViolatedException("cold on-video seeking requires the separately installed VLC gate")
        repeat(12) { scenario(412, 1f, UiLanguage.ENGLISH, it) }
    }
    private fun scenario(width: Int, font: Float, language: UiLanguage, trial: Int) {
        ExportHarness().use { h ->
            val initial = h.session.open!!.project.recap!!
            val recap = initial.copy(layers = initial.layers.map { if (it.id == "qa-image") it.copy(fromMs = 1500, toMs = 3500) else it })
            assertIs<CoreResult.Ok<*>>(h.session.save(h.session.open!!.project.copy(recap = recap)))
            h.settings.update { it.copy(vlcPath = System.getenv("AUTOFLOW_VLC_DIR")) }
            val model = RecapModel(h.session, h.fs, MediaTools(h.settings, DesktopToolLocator(h.fs), h.media.runner), null, h.scope, frames = h.frames)
            val evidence = File("build/reports/playback/on-video-cold/$trial-$width-${language.code}-$font").apply { mkdirs() }
            runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
                setContent { CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiFontFamily provides BurmeseTestFont.family, LocalUiLanguage provides language) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) { StudioRecapScreen(model, RecapStep.STYLE, width.toFloat(), {}, RecapNavigation({}, {}, {})) }
                } }
                mainClock.autoAdvance = false
                fun await(condition: () -> Boolean) {
                    try { waitUntil(timeoutMillis = 20_000) { mainClock.advanceTimeByFrame(); condition() } }
                    catch (failure: Throwable) {
                        runCatching { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(evidence, "failure.png")) }
                        val tree = runCatching { onRoot().printToString() }.getOrDefault("tree unavailable")
                        throw AssertionError("on-video $width/${language.code}/font=$font; output=${model.previewSource.currentPosition()}; draft=${model.layerEditor.value()}; ${tree.take(2500)}", failure)
                    }
                }
                fun reach(label: String, preview: Boolean = false, target: () -> SemanticsNodeInteraction): SemanticsNodeInteraction {
                    repeat(24) {
                        mainClock.advanceTimeBy(500)
                        val container = if (width >= 840 && preview) "style-preview" else "style-content"
                        val node = target().fetchSemanticsNode(); val viewport = onNodeWithTag(container).fetchSemanticsNode().boundsInRoot
                        val y = node.positionInRoot.y
                        if (y >= viewport.top - .5f && y + node.size.height <= viewport.bottom + .5f) return target().assertIsDisplayed()
                        val above = y < viewport.top
                        onNodeWithTag(container).performTouchInput {
                            swipe(Offset(center.x, this.height * if (above) .25f else .75f), Offset(center.x, this.height * if (above) .75f else .25f), 250)
                        }
                    }
                    error("real scrolling could not reach $label")
                }
                fun reach(tag: String) = reach(tag, preview = tag.startsWith("timeline-")) { onNodeWithTag(tag) }
                fun click(tag: String) { reach(tag).performMouseInput { moveTo(center); click(); exit() }; mainClock.advanceTimeBy(500) }
                fun playing() = onNodeWithTag("timeline-play").fetchSemanticsNode().config[SemanticsProperties.Text].any { it.text == UiText.PAUSE.text(language) }
                click("layer-edit-qa-image")
                await { onAllNodesWithTag("layer-move-handle").fetchSemanticsNodes().size == 1 && model.previewSource.currentPosition() == 1500L }
                assertFalse(playing()); reach("timeline-video")
                fun greenSourceFrame(): Boolean {
                    val pixels = onNodeWithTag("timeline-video").captureToImage().toPixelMap()
                    val c = pixels[(pixels.width * .1f).toInt(), (pixels.height * .1f).toInt()]
                    return c.red < .2f && c.green > .7f && c.blue < .2f
                }
                await { greenSourceFrame() } // Independent temporal patch: output 1500 -> source 3500, not a priming frame.
                ImageIO.write(onNodeWithTag("timeline-video").captureToImage().toAwtImage(), "png", File(evidence, "cold-source-3500.png"))
                println("ON_VIDEO_COLD: trial=$trial real source-time marker is green at output 1500/source 3500")
            }
        }
    }
}
