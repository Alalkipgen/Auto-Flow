package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.recap.BackgroundMode
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.export.ExportHarness
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlin.test.*

/**
 * Group 2 C 14 / 17 · 4–7 (PR 8): the Style layer form in the glass look — the strings lost their arrows (LAYER_EARLIER /
 * LAYER_LATER, the icons carry them), Save / Remove layer carry their icons, the exact numbers and the hints sit in More
 * options, a new Image layer cannot move later, invalid numbers keep Save disabled with the amber problem, the Background
 * colour mode shows its colour field, every text fits on a 360 dp phone and Discard writes nothing.
 */
@OptIn(ExperimentalTestApi::class)
class StyleLayersGlassTest {
    @Test fun layerFormsKeepTheirTagsIconsAndConditionsAndDiscardWithoutWriting() {
        for ((language, font) in listOf(UiLanguage.ENGLISH to 1f, UiLanguage.BURMESE to 2f)) runDesktopComposeUiTest(width = 360, height = 760) {
            ExportHarness().use { h ->
                val before = h.bytes()
                setContent { CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 360f) {
                        StudioRecapScreen(h.recap, RecapStep.STYLE, 360f, {}, RecapNavigation({}, {}, {}))
                    }
                } }
                fun click(tag: String) { onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled().performMouseInput { click() }; waitForIdle() }
                fun fits(state: String) = assertEquals(emptyList(), textFitProblems(360, font), "$state ${language.code}")
                fun icons(tag: String) = onAllNodes(hasTestTag("button-icon") and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                    .fetchSemanticsNodes().size
                fun expand() = onNode(hasContentDescription(UiText.V3_MORE_OPTIONS.text(language)) and hasAnyAncestor(hasTestTag("layer-exact-options")))
                    .performScrollTo().assertIsDisplayed().performMouseInput { click() }

                // 14: an existing Text layer — draw order buttons without arrows in their text, both with an icon.
                click("style-section-LAYERS"); click("layer-edit-qa-text")
                assertEquals("Draw earlier", UiText.LAYER_EARLIER.text(UiLanguage.ENGLISH))
                assertEquals("နောက်မှဆွဲ", UiText.LAYER_LATER.text(UiLanguage.BURMESE))
                onNodeWithTag("layer-earlier").performScrollTo().assertTextEquals(UiText.LAYER_EARLIER.text(language))
                onNodeWithTag("layer-later").assertTextEquals(UiText.LAYER_LATER.text(language))
                assertEquals(1, icons("layer-earlier")); assertEquals(1, icons("layer-save")); assertEquals(1, icons("layer-remove"))
                onNodeWithTag("layer-field-text").performScrollTo().assertIsDisplayed()
                fits("text")
                // 17 · 4: More options open — the exact numbers, the placement box.
                expand(); onNodeWithTag("layer-field-x").performScrollTo().assertIsDisplayed()
                onNodeWithTag("layer-field-h").performScrollTo().assertIsDisplayed()
                onNodeWithTag("native-layer-rect").performScrollTo().assertIsDisplayed()
                fits("exact")
                click("layer-cancel"); assertNull(h.recap.layerEditor.state.value.draft)

                // 17 · 5: a new Image layer — aspect hint, Choose image…, no Remove, Draw later disabled at the end.
                click("layer-add-IMAGE")
                onNodeWithText(UiText.V3_IMAGE_ASPECT.text(language)).performScrollTo().assertIsDisplayed()
                onNodeWithTag("layer-image").performScrollTo().assertIsDisplayed().assertIsEnabled()
                onNodeWithTag("layer-later").performScrollTo().assertIsNotEnabled()
                onNodeWithTag("layer-remove").assertDoesNotExist()
                fits("image")
                click("layer-cancel")

                // 17 · 6: Blur with an invalid number — amber problem, Save disabled.
                click("layer-add-BLUR")
                onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("layer-field-sigma"))).performScrollTo().performTextReplacement("x")
                waitForIdle()
                onNodeWithText(UiText.NUMBERS_INVALID.text(language)).performScrollTo().assertIsDisplayed()
                onNodeWithTag("layer-save").performScrollTo().assertIsNotEnabled()
                fits("blur")
                click("layer-cancel")

                // 17 · 7: Background — whole frame, mode chips, Colour → the colour field.
                click("layer-add-BACKGROUND")
                onNodeWithText(UiText.LAYER_FULL_FRAME.text(language)).performScrollTo().assertIsDisplayed() // outside More options
                val colour = BackgroundMode.COLOUR.uiLabel(language)
                onNode((hasText(colour) or hasText(My.breakSyllables(colour))) and hasClickAction()).performScrollTo().performMouseInput { click() }
                waitForIdle()
                onNodeWithTag("layer-field-colour").performScrollTo().assertIsDisplayed()
                fits("background")
                click("layer-cancel")
                assertNull(h.recap.layerEditor.state.value.draft)
                assertEquals(before, h.bytes(), "${language.code}: opening and discarding layer forms writes nothing")
            }
        }
    }
}
