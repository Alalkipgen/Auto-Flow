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
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.platform.DesktopToolLocator
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.export.ExportHarness
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import org.junit.AssumptionViolatedException
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

/** The existing installed-VLC CI pattern '*TimelinePlaybackRuntimeTest*' includes this test without changing it. */
@OptIn(ExperimentalTestApi::class)
class OnVideoTimelinePlaybackRuntimeTest {
    @Test fun realStyleDecoderPausesForEditDragsSavesAtSameOutputTimeDiscardsAndDisposes() {
        if (System.getenv("AUTOFLOW_EXPECT_VLC") != "1") throw AssumptionViolatedException("on-video live editing requires the separately installed VLC gate")
        for ((width, font, language) in listOf(Triple(412, 1f, UiLanguage.ENGLISH),
            Triple(1440, 1f, UiLanguage.ENGLISH), Triple(840, 2f, UiLanguage.BURMESE))) scenario(width, font, language)
    }
    private fun scenario(width: Int, font: Float, language: UiLanguage) {
        ExportHarness().use { h ->
            val initial = h.session.open!!.project.recap!!
            val recap = initial.copy(layers = initial.layers.map { if (it.id == "qa-image") it.copy(fromMs = 1500, toMs = 3500) else it })
            assertIs<CoreResult.Ok<*>>(h.session.save(h.session.open!!.project.copy(recap = recap)))
            h.settings.update { it.copy(vlcPath = System.getenv("AUTOFLOW_VLC_DIR")) }
            val model = RecapModel(h.session, h.fs, MediaTools(h.settings, DesktopToolLocator(h.fs), h.media.runner), null, h.scope, frames = h.frames)
            val evidence = File("build/reports/playback/on-video/$width-${language.code}-$font").apply { mkdirs() }
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
                fun click(tag: String) { reach(tag).performMouseInput { click() }; mainClock.advanceTimeBy(500) }
                fun playing() = onNodeWithTag("timeline-play").fetchSemanticsNode().config[SemanticsProperties.Text].any { it.text == UiText.PAUSE.text(language) }
                val before = h.bytes()
                click("layer-edit-qa-image")
                await { onAllNodesWithTag("layer-move-handle").fetchSemanticsNodes().size == 1 && model.previewSource.currentPosition() == 1500L }
                assertFalse(playing()); reach("timeline-video")
                fun greenSourceFrame(): Boolean {
                    val pixels = onNodeWithTag("timeline-video").captureToImage().toPixelMap()
                    val c = pixels[(pixels.width * .1f).toInt(), (pixels.height * .1f).toInt()]
                    return c.red < .2f && c.green > .7f && c.blue < .2f
                }
                await { greenSourceFrame() } // Independent temporal patch: output 1500 -> source 3500, not a priming frame.
                val frame = onNodeWithTag("timeline-video").fetchSemanticsNode().size
                val old = model.layerEditor.value()!!.rect
                onNodeWithTag("layer-move-handle").assertIsDisplayed().performMouseInput {
                    val p = center; moveTo(p); press(); moveTo(p + Offset(-frame.width * .05f, frame.height * .04f)); release()
                }
                mainClock.advanceTimeBy(500)
                val draft = model.layerEditor.value()!!
                assertTrue(abs(draft.rect[0] - old[0] + .05f) < .01f); assertEquals(before, h.bytes())
                ImageIO.write(onNodeWithTag("timeline-video").captureToImage().toAwtImage(), "png", File(evidence, "live-unsaved.png"))
                onNode(hasText(UiText.EXACT_FRAME.text(language)) and hasClickAction()).assertIsNotEnabled()
                click("layer-save"); await { model.layerEditor.state.value.draft == null && model.previewSource.currentPosition() == 1500L }
                assertEquals(draft, h.session.open!!.project.recap!!.layers.single { it.id == draft.id }); assertFalse(playing())
                reach("timeline-video"); await { greenSourceFrame() }; onNodeWithTag("layer-move-handle").assertDoesNotExist()
                ImageIO.write(onNodeWithTag("timeline-video").captureToImage().toAwtImage(), "png", File(evidence, "live-saved-1500.png"))
                val exact = onNode(hasText(UiText.EXACT_FRAME.text(language)) and hasClickAction())
                reach("Exact frame", preview = true) { exact }.assertIsEnabled().performMouseInput { click() }
                await { onAllNodesWithContentDescription(UiText.EXACT_FRAME_DESCRIPTION.text(language)).fetchSemanticsNodes().size == 1 }
                assertEquals(1500L, model.previewSource.currentPosition())
                val savedBytes = h.bytes(); click("layer-edit-qa-image"); await { model.layerEditor.value() != null }
                click("layer-cancel"); assertEquals(savedBytes, h.bytes(), "Discard cannot write project/artifact/output bytes")
                val dir = File(h.root, "other.autoflow").path
                val other = (h.store.create(dir, "Other", Mode.MOVIE_RECAP) as CoreResult.Ok).value
                runOnIdle { h.current.set(OpenProject(dir, other)); model.refresh() }; mainClock.advanceTimeByFrame()
                onNodeWithTag("output-layer-overlay").assertDoesNotExist(); onNodeWithTag("timeline-video").assertDoesNotExist()
                assertNull(model.previewSource.currentPosition())
                println("ON_VIDEO_LIVE: real $width/${language.code}/font=$font Style container, separately installed VLC frame, edit pauses+seeks output 1500/source 3500, real drag, explicit Save retains 1500, real Exact Frame, byte-identical Discard, project switch disposes handles/player")
            }
        }
    }
}
