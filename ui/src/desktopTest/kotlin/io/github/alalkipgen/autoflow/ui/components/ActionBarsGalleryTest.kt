package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P9-5b gallery (DESIGN.md §9.4): sticky action bars, segmented, switch rows — EN + MY, font 1.0 / 2.0, phone + desktop. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class ActionBarsGalleryTest(val width: Int, val font: Float, val language: UiLanguage) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}dp/{1}/{2}")
        fun cases() = listOf(360 to 1f, 360 to 2f, 1440 to 1f, 1440 to 2f).flatMap { (w, f) ->
            UiLanguage.entries.map { arrayOf<Any>(w, f, it) }
        }
    }

    @Test fun barsSegmentsAndSwitchesFitAndWork() = runDesktopComposeUiTest(width = width, height = if (font > 1f) 2000 else 1100) {
        var view by mutableStateOf(1)
        var burn by mutableStateOf(true)
        var format by mutableStateOf(0)
        var next = 0
        setContent {
            CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(1f, font),
                LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                Column(Modifier.fillMaxSize().background(AutoFlowColors.Background), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    StickyActionBar(StudioAction(tr(UiText.NEXT_STEP, "step" to tr(UiText.VOICE)), { next++ }, tag = "g-next"),
                        StudioAction(tr(UiText.PREVIOUS_STEP), {}, tag = "g-back"), tr(UiText.APPROVED), Modifier.testTag("g-pair"))
                    StickyActionBar(StudioAction(tr(UiText.DUB_SPEAK_LINES, "n" to 5), {}, tag = "g-one"), caption = tr(UiText.V3_SHORTCUTS_HINT),
                        modifier = Modifier.testTag("g-hint"))
                    StickyActionBar(StudioAction(tr(UiText.VA_SAVE_M4A), {}, enabled = false, tag = "g-save"),
                        leading = { Segmented(listOf("m4a", "wav"), format, { format = it }, Modifier.testTag("g-format")) })
                    Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Segmented(listOf(tr(UiText.SUB_VIEW_ORIGINAL), tr(UiText.SUB_VIEW_BURMESE), tr(UiText.SUB_VIEW_BOTH)), view, { view = it },
                            Modifier.fillMaxWidth().testTag("g-view"))
                        Segmented(listOf(tr(UiText.DUB_ORIGINAL), tr(UiText.DUB_LOWER), tr(UiText.DUB_MUTE)), 1, {}, Modifier.fillMaxWidth(), enabled = false)
                        SwitchRow(tr(UiText.BURN_CAPTIONS), tr(UiText.SUB_BURN_KEEPS), burn, { burn = it }, Modifier.testTag("g-burn"), AfIcons.Regular.Subtitles)
                        SwitchRow(tr(UiText.VA_LOWER_ORIGINAL), null, false, {}, enabled = false)
                        AfSwitch(tr(UiText.VA_LOWER_ORIGINAL), true, {})
                    }
                }
            }
        }
        // Labels are never cut (≤ 2 lines in segments, then "…" with the full text kept), every target ≥ 48 dp.
        assertEquals(emptyList(), textFitProblems(width, font, strict = true))
        onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach {
            assertTrue(it.size.width >= 48 && it.size.height >= 48, "48 dp target at $width/$font/$language: ${it.size}")
        }
        // Phone: secondary + primary share one row at equal width, or stack full width; desktop: right-aligned, ≥ 150 dp.
        val bar = onNodeWithTag("g-pair").fetchSemanticsNode().boundsInRoot
        val back = onNodeWithTag("g-back").fetchSemanticsNode().boundsInRoot
        val go = onNodeWithTag("g-next").fetchSemanticsNode().boundsInRoot
        if (width < 600) {
            if (back.top == go.top) assertEquals(back.width, go.width, 1f) else assertTrue(back.bottom <= go.top && go.width > bar.width - 30f)
            val hint = onNodeWithText(tr(UiText.APPROVED, language)).fetchSemanticsNode().boundsInRoot
            assertTrue(hint.top >= go.bottom, "the hint sits below the buttons on a phone")
        } else {
            assertTrue(go.right in bar.right - 17f..bar.right - 15f && back.right <= go.left && go.width >= 150f && back.width >= 150f && go.width < bar.width / 2,
                "desktop buttons sit right, ≥ 150 dp, never full width: $back $go in $bar")
            val hint = onNodeWithText(tr(UiText.APPROVED, language)).fetchSemanticsNode().boundsInRoot
            assertTrue(hint.right <= back.left, "the hint sits left of the buttons on the desktop")
        }
        val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "gallery").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "actions-$width-$font-${language.name.lowercase()}.png"))
        System.getenv("AUTOFLOW_PR_SHOTS_DIR")?.let { if (language == UiLanguage.BURMESE)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(File(it).apply { mkdirs() }, "actions-${if (width < 600) "phone-$font" else "desktop-$font"}.png")) }
        onNodeWithTag("g-next").performMouseInput { click() }; assertEquals(1, next)
        onNodeWithTag("g-save").assertIsNotEnabled()
        // Segmented: one selected choice, a click selects another; the switch row toggles as a whole.
        val choices = onAllNodes(isSelectable() and hasAnyAncestor(hasTestTag("g-view")))
        choices.assertCountEquals(3); choices[1].assertIsSelected(); choices[0].assertIsNotSelected()
        choices[2].performMouseInput { click() }; waitForIdle(); assertEquals(2, view)
        onAllNodes(isSelectable() and hasAnyAncestor(hasTestTag("g-format")))[1].performMouseInput { click() }; waitForIdle(); assertEquals(1, format)
        onNodeWithTag("g-burn").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "on")).performMouseInput { click() }
        waitForIdle(); assertEquals(false, burn)
    }

    private fun tr(key: UiText, language: UiLanguage) = key.text(language)
}
