package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.export.ExportHarness
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.captionControl(tag: String): SemanticsNodeInteraction {
    val node = onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    val b = node.getUnclippedBoundsInRoot(); val bar = onNodeWithTag("sticky-action-bar").getUnclippedBoundsInRoot()
    assertTrue(b.top.value >= 0 && b.bottom <= bar.top && (b.bottom - b.top).value >= 48f, "$tag must be uncovered and >=48dp: $b/$bar")
    return node
}
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.captionClick(tag: String) = captionControl(tag).assertIsEnabled().performMouseInput { moveTo(center); click(); exit() }
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.captionStatus(language: UiLanguage, label: UiText) {
    onNodeWithTag("caption-preset-status").performScrollTo().assertIsDisplayed()
        .assertTextEquals(UiText.V3_CAPTION_PRESET_STATUS.text(language, "name" to label.text(language)))
}

@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class CaptionPresetUiTest(val width: Int, val font: Float, val language: UiLanguage, val bundled: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}/{2}/bundled={3}") fun cases() = listOf(
            arrayOf<Any>(412, 1f, UiLanguage.ENGLISH, true), arrayOf<Any>(412, 2f, UiLanguage.ENGLISH, true),
            arrayOf<Any>(412, 1f, UiLanguage.BURMESE, true), arrayOf<Any>(412, 2f, UiLanguage.BURMESE, true),
            arrayOf<Any>(360, 2f, UiLanguage.BURMESE, true), arrayOf<Any>(840, 2f, UiLanguage.BURMESE, false),
            arrayOf<Any>(1440, 1f, UiLanguage.ENGLISH, true))
    }
    @Test fun realStyleChipsPersistOnlyCaptionStyleAndManualEditsShowCustom() =
        runDesktopComposeUiTest(width = width, height = if (width == 1440) 900 else 760) {
            ExportHarness().use { h ->
                val initial = h.session.open!!.project.recap!!
                val artifacts = h.bytes().filterKeys { it.replace('\\', '/').startsWith("artifacts/") }
                setContent { CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) {
                        StudioRecapScreen(h.recap, RecapStep.STYLE, width.toFloat(), {}, RecapNavigation({}, {}, {}))
                    }
                } }
                captionClick("style-section-CAPTIONS")
                fun capture(state: String) {
                    assertEquals(emptyList(), textFitProblems(width, font))
                    val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "caption-presets").apply { mkdirs() }
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$state-$width-${language.code}-$font-$bundled.png"))
                }
                for (preset in CaptionLookPreset.entries) {
                    val beforeClick = h.bytes()
                    val alreadyApplied = h.session.open!!.project.recap!!.captionStyle == preset.style
                    captionClick("caption-preset-${preset.name}"); captionStatus(language, preset.label)
                    if (alreadyApplied) assertEquals(beforeClick, h.bytes(), "selecting the same preset must not save or stale the next callback")
                    onNodeWithTag("caption-preset-${preset.name}").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "selected"))
                    val saved = (h.store.open(h.fixture.dir.path) as CoreResult.Ok).value.recap!!
                    assertEquals(preset.style, saved.captionStyle)
                    assertEquals(initial.copy(captionStyle = preset.style), saved, "only existing captionStyle changes")
                    capture(preset.name)
                }
                captionClick("caption-preset-CLEAN")
                // A manual change must say Custom EVEN when its fields happen to equal another preset.
                onNodeWithText(UiText.DARK_CAPTION_BOX.text(language)).performScrollTo().assertIsDisplayed()
                    .performMouseInput { moveTo(center); click(); exit() }
                assertEquals(CaptionLookPreset.BOXED.style, h.session.open!!.project.recap!!.captionStyle)
                captionStatus(language, UiText.V3_CAPTION_CUSTOM)
                captionClick("caption-preset-BOXED"); captionStatus(language, UiText.V3_CAPTION_BOXED)
                captionClick("caption-size-XL")
                assertEquals(CAPTION_XL_FACTOR, h.session.open!!.project.recap!!.captionStyle.customSizeFactor)
                assertEquals(CaptionSize.L, h.session.open!!.project.recap!!.captionStyle.size)
                capture("XL")
                captionStatus(language, UiText.V3_CAPTION_CUSTOM)
                captionClick("caption-size-S")
                assertEquals(CaptionSize.S, h.session.open!!.project.recap!!.captionStyle.size)
                assertNull(h.session.open!!.project.recap!!.captionStyle.customSizeFactor)
                captionStatus(language, UiText.V3_CAPTION_CUSTOM)
                assertEquals(artifacts, h.bytes().filterKeys { it.replace('\\', '/').startsWith("artifacts/") })
            }
        }
}

@OptIn(ExperimentalTestApi::class)
class CaptionPresetWorkflowTest {
    @Test fun typedOverridesResetOnPresetBusyDisablesAndProjectSwitchCannotLeakSelectionOrWrites() =
        runDesktopComposeUiTest(width = 412, height = 760) {
            ExportHarness().use { h ->
                setContent { CompositionLocalProvider(LocalCompact provides true, LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioRecapScreen(h.recap, RecapStep.STYLE, 412f, {}, RecapNavigation({}, {}, {}))
                } }
                captionClick("style-section-CAPTIONS")
                val colour = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("caption-custom-colour")))
                colour.performScrollTo().assertIsDisplayed().performTextReplacement("#AABBCC")
                captionClick("caption-colour-apply")
                assertEquals("#AABBCC", h.session.open!!.project.recap!!.captionStyle.customColour)
                captionStatus(UiLanguage.ENGLISH, UiText.V3_CAPTION_CUSTOM)
                captionClick("caption-preset-OUTLINE")
                colour.performScrollTo().assertIsDisplayed().assertTextEquals("")
                assertEquals(CaptionLookPreset.OUTLINE.style, h.session.open!!.project.recap!!.captionStyle)
                captionClick("caption-size-XL")
                val factor = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("caption-custom-size")))
                factor.performScrollTo().assertIsDisplayed().assertTextEquals("1.4")
                factor.performTextReplacement("1.6"); captionClick("caption-factor-apply")
                assertEquals(1.6f, h.session.open!!.project.recap!!.captionStyle.customSizeFactor)
                captionClick("caption-factor-clear"); assertNull(h.session.open!!.project.recap!!.captionStyle.customSizeFactor)
                captionClick("caption-preset-CLEAN")
                val beforeBusy = h.bytes()
                runOnIdle { h.recap.busy("busy caption gate") }; waitForIdle()
                captionControl("caption-preset-BOXED").assertIsNotEnabled()
                captionControl("caption-size-XL").assertIsNotEnabled()
                runOnIdle { h.recap.setCaptionStyle { CaptionLookPreset.BOXED.style } }
                assertEquals(beforeBusy, h.bytes(), "existing model busy guard refuses mutation")
                runOnIdle { h.recap.done(null) }; waitForIdle()
                val aBytes = h.bytes(); val b = QaRecapFixture.create(h.root, "QA Caption B")
                val bBytes = File(b.dir, "project.json").readBytes()
                runOnIdle { h.current.set(OpenProject(b.dir.path, b.project)); h.recap.refresh() }
                waitUntil { h.recap.state.value.projectDir == b.dir.path }; waitForIdle()
                captionClick("style-section-CAPTIONS")
                assertContentEquals(bBytes, File(b.dir, "project.json").readBytes(), "switch/browse never saves")
                captionClick("caption-preset-BOXED")
                assertEquals(CaptionLookPreset.BOXED.style, (h.store.open(b.dir.path) as CoreResult.Ok).value.recap!!.captionStyle)
                assertEquals(aBytes, h.bytes(), "B's preset must not touch A")
            }
        }
}
