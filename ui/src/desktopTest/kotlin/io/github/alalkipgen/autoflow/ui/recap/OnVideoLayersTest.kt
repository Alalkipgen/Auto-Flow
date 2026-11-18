package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.media.render.RenderPresets
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.export.ExportHarness
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

internal fun onVideoEvidence(name: String) = File(System.getenv("AUTOFLOW_ON_VIDEO_DIR") ?: "build/reports/on-video", name).apply { mkdirs() }
internal fun onVideoDecode(file: File, ms: Long, target: File): BufferedImage {
    val args = listOf(System.getenv("AUTOFLOW_FFMPEG") ?: "ffmpeg", "-v", "error", "-nostdin", "-y", "-i", file.path,
        "-ss", "${ms / 1000.0}", "-frames:v", "1", target.path)
    File(target.path + ".command.txt").writeText(args.joinToString("\n"))
    val p = ProcessBuilder(args).redirectErrorStream(true).start()
    val log = p.inputStream.bufferedReader().readText()
    File(target.path + ".log").writeText(log)
    assertEquals(0, p.waitFor(), log)
    return assertNotNull(ImageIO.read(target))
}

class OnVideoLayerFormTest {
    @Test fun numericAndPointerShareOneUnsavedTransactionAndSaveOnlyTheStableLayer() = ExportHarness().use { h ->
        val before = h.bytes(); val original = h.session.open!!.project.recap!!; val e = h.recap.layerEditor
        e.edit("qa-blur"); val token = e.state.value.draft!!.token
        e.field(token, "from", "1200"); e.field(token, "to", "3500"); e.field(token, "sigma", "11")
        e.rect(token, listOf(.2f, .3f, .3f, .2f)); e.move(-1)
        val layer = assertNotNull(e.value())
        assertEquals("0.2", e.state.value.fields["x"]); assertEquals(11f, layer.params.sigma)
        assertEquals(1200L, layer.fromMs); assertEquals(3500L, layer.toMs)
        val preview = e.preview(original)
        assertEquals(layer, preview.layers.first()); assertEquals(before, h.bytes(), "draft/preview/order are read-only")
        assertTrue(e.save(layer)); val saved = (h.store.open(h.fixture.dir.path) as CoreResult.Ok).value.recap!!
        assertEquals(preview, saved); assertEquals(original.copy(layers = saved.layers), saved)
        assertEquals(before.filterKeys { it.startsWith("artifacts/") }, h.bytes().filterKeys { it.startsWith("artifacts/") })
        e.edit(layer.id); assertEquals(layer, e.value()); e.cancel()
    }
    @Test fun invalidTextCannotBeSilentlyReplacedByOldNumbersOrAGesture() = ExportHarness().use { h ->
        val before = h.bytes(); val original = h.session.open!!.project.recap!!; val e = h.recap.layerEditor
        e.edit("qa-blur"); val token = e.state.value.draft!!.token
        for (invalid in listOf("NaN", "Infinity", "-0.1", "2", "not a number")) {
            e.field(token, "x", invalid); assertNull(e.value()); e.rect(token, listOf(.2f, .2f, .3f, .2f))
            assertEquals(invalid, e.state.value.fields["x"]); assertEquals(original, e.preview(original))
        }
        e.field(token, "x", "0.1"); assertNotNull(e.value()); e.field(token, "to", "1000")
        e.field(token, "from", "2000"); assertNull(e.value()); e.cancel(); assertEquals(before, h.bytes())
    }
    @Test fun lateGestureCannotAffectAnotherDraftRevisionOrProject() = ExportHarness().use { h ->
        val e = h.recap.layerEditor; e.edit("qa-blur"); val old = e.state.value.draft!!.token
        e.edit("qa-image"); val next = e.state.value
        e.rect(old, listOf(.2f, .2f, .2f, .2f)); e.field(old, "opacity", "0.2"); assertEquals(next, e.state.value)
        assertIs<CoreResult.Ok<*>>(h.session.save(h.session.open!!.project.copy(name = "Other revision")))
        val bytes = h.bytes(); e.rect(next.draft!!.token, listOf(.2f, .2f, .2f, .2f)); assertNull(e.value())
        assertEquals(next.fields, e.state.value.fields); assertEquals(bytes, h.bytes())
        val dir = File(h.root, "other.autoflow").path
        val other = (h.store.create(dir, "Other", Mode.MOVIE_RECAP) as CoreResult.Ok).value
        h.current.set(OpenProject(dir, other)); e.field(next.draft!!.token, "x", "0.4")
        assertNull(e.value()); assertEquals(next.fields, e.state.value.fields); assertEquals(bytes, h.bytes()); e.cancel()
    }
    @Test fun imageWidthKeepsAspectAndFullFrameLayersNeverGainRects() {
        val image = Layer("image", LayerType.IMAGE, listOf(.2f, .2f, .3f, .1f), params = LayerParams(image = "im_0123456789ab"))
        val changed = outputLayerChange(image, .05f, .02f, 1.2f, .25f, .25f, false, 1.5f)
        assertEquals(.1f, changed[3]); assertTrue(abs(changed[2] - .36f) < .0001f)
        assertTrue(abs(outputLayerRect(image.copy(rect = changed), 1.5f)[3] - .54f) < .0001f)
        for (type in listOf(LayerType.BACKGROUND, LayerType.BORDER)) {
            val full = Layer("full", type)
            assertEquals(full.rect, outputLayerChange(full, .1f, .1f, 2f, .5f, .5f, true, null))
        }
    }
}

/** Production compositor + controls in a scrollable component harness, NOT a fake native-player claim. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class OnVideoOverlayLayoutTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object { @JvmStatic @Parameterized.Parameters(name = "{0}px/{1}/{2}/bundled={3}") fun cases() = StudioVoiceLayoutTest.cases() }
    @Test fun realPointerMoveResizePinchSharedInvalidFormSaveDiscardAndReferenceOnlyRemove() =
        runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
            ExportHarness().use { h ->
                val evidence = onVideoEvidence("layout-$width-${language.code}-$font-$bundled")
                val picture = onVideoDecode(File(h.fixture.dir, h.fixture.project.recap!!.movie!!.file), 3500, File(evidence, "source.png")).toComposeImageBitmap()
                val out = RenderPresets.preview(Ratio.R16_9); val e = h.recap.layerEditor
                val artifacts = h.bytes().filterKeys { it.startsWith("artifacts/") }; val before = h.bytes()
                setContent { CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
                    LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                    val open by h.current.state.collectAsState(); val recap = open!!.project.recap!!
                    val images = remember(recap) { NativePreviewAssets(h.fs).load(h.fixture.dir.path, recap) }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        NativeLayerPreview(picture, recap, out, 1500, emptyList(), images, e, true,
                            Modifier.widthIn(max = 640.dp).fillMaxWidth().aspectRatio(16f / 9).testTag("on-video-frame"))
                        RecapLayerControls(recap, true, h.recap, studio = true)
                    }
                } }
                fun frame() = onNodeWithTag("on-video-frame").performScrollTo().assertIsDisplayed()
                fun click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                fun capture(name: String) {
                    frame(); assertEquals(emptyList(), textFitProblems(width, font))
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(evidence, "$name.png"))
                }
                frame().performMouseInput { click(Offset(width * .675f, height * .24f)) }
                waitForIdle(); assertEquals("qa-image", e.state.value.draft?.layer?.id)
                val fw = frame().fetchSemanticsNode().size.width.toFloat(); val fh = frame().fetchSemanticsNode().size.height.toFloat()
                for (tag in listOf("layer-move-handle", "layer-resize-handle", "layer-video-remove")) {
                    val b = onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
                    assertTrue((b.right - b.left).value >= 48 && (b.bottom - b.top).value >= 48, "$tag: $b")
                }
                val initial = e.value()!!.rect
                onNodeWithTag("layer-move-handle").performTouchInput {
                    val p = Offset(width * .25f, height * .25f); down(p); moveTo(p + Offset(-fw * .06f, fh * .04f)); up()
                }
                waitForIdle(); val moved = e.value()!!.rect
                assertTrue(abs(moved[0] - (initial[0] - .06f)) < .01f, "$initial -> $moved")
                assertTrue(abs(moved[1] - (initial[1] + .04f)) < .01f)
                onNodeWithTag("layer-resize-handle").performMouseInput { moveTo(center); press(); moveTo(center + Offset(-fw * .03f, -fh * .02f)); release(); exit() }
                waitForIdle(); val resized = e.value()!!.rect
                assertTrue(resized[2] < moved[2]); assertEquals(initial[3], resized[3], "IMAGE stored height is not stretched")
                onNodeWithTag("layer-move-handle").performTouchInput {
                    val c = Offset(width * .3f, height * .3f); val d = width * .08f
                    down(0, c - Offset(d, 0f)); down(1, c + Offset(d, 0f))
                    moveTo(0, c - Offset(d * 1.2f, 0f)); moveTo(1, c + Offset(d * 1.2f, 0f)); up(0); up(1)
                }
                waitForIdle(); val draft = e.value()!!
                assertTrue(draft.rect[2] > resized[2] * 1.05f, "real pinch changed width: ${draft.rect}")
                assertEquals(draft.rect[0], e.state.value.fields.getValue("x").toFloat()); assertEquals(before, h.bytes())
                capture("draft"); click("layer-save"); waitForIdle()
                val saved = (h.store.open(h.fixture.dir.path) as CoreResult.Ok).value.recap!!
                assertEquals(draft, saved.layers.single { it.id == "qa-image" }); capture("saved")
                val savedBytes = h.bytes()
                click("layer-edit-qa-image")
                onNode(hasContentDescription(UiText.V3_MORE_OPTIONS.text(language)) and hasAnyAncestor(hasTestTag("layer-exact-options")))
                    .performScrollTo().assertIsDisplayed().performMouseInput { click() }
                onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("layer-field-x"))).performScrollTo().performTextReplacement("NaN")
                onNodeWithTag("layer-save").performScrollTo().assertIsNotEnabled(); frame()
                onNodeWithTag("layer-move-handle").assertDoesNotExist(); onNodeWithTag("layer-resize-handle").assertDoesNotExist()
                click("layer-cancel"); assertEquals(savedBytes, h.bytes())
                for (id in listOf("qa-background", "qa-border")) {
                    click("layer-edit-$id"); frame(); onNodeWithTag("layer-move-handle").assertDoesNotExist()
                    onNodeWithTag("layer-resize-handle").assertDoesNotExist(); click("layer-cancel")
                }
                click("layer-edit-qa-image"); frame(); onNodeWithTag("layer-video-remove").assertIsDisplayed().performMouseInput { click() }
                waitForIdle(); assertFalse(h.session.open!!.project.recap!!.layers.any { it.id == "qa-image" })
                assertEquals(artifacts, h.bytes().filterKeys { it.startsWith("artifacts/") }, "× removes only the reference")
            }
        }
}
