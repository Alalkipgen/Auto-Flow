package io.github.alalkipgen.autoflow.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Group 3 I (PR 12, sample 16): the Classic steps page — SHELL_CLASSIC_HINT in an info callout over one glass list of
 * the four classic screens; each row is one ≥ 48 dp button that opens its old screen (the same targets as the buttons
 * it replaces), in English and Burmese at font 1.0 and 2.0 on a 360 dp phone with nothing cut.
 */
@OptIn(ExperimentalTestApi::class)
class MvClassicStepsTest {
    private val tags = listOf("mv-classic-storyboard", "mv-classic-shots", "mv-classic-references", "mv-classic-flow-handoff")

    @Test fun rowsOpenTheirClassicScreens() {
        for (language in UiLanguage.entries) for (font in listOf(1f, 2f)) runDesktopComposeUiTest(width = 360, height = 780) {
            val opened = mutableListOf<String>()
            setContent {
                CompositionLocalProvider(LocalUiLanguage provides language, LocalDensity provides Density(1f, font),
                    LocalUiFontFamily provides BurmeseTestFont.family) {
                    MvClassicSteps { opened += it }
                }
            }
            onNodeWithTag("mv-classic-hint").assertIsDisplayed()
            onNode(hasText(My.breakSyllables(UiText.SHELL_CLASSIC_HINT.text(language))) and hasAnyAncestor(hasTestTag("mv-classic-hint"))).assertExists()
            tags.forEachIndexed { i, tag ->
                val row = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHasClickAction()
                assertTrue(row.fetchSemanticsNode().boundsInRoot.height >= 48f, "$tag is a 48 dp target")
                onNode(hasTestTag(tag) and hasText(My.breakSyllables(My.screen(MV_CLASSIC_SCREENS[i], language)))).assertExists()
                row.performClick()
                waitForIdle()
            }
            assertEquals(MV_CLASSIC_SCREENS, opened, "$language $font")
            assertEquals(emptyList(), textFitProblems(360, font, strict = true, minNodes = 5), "$language $font")
        }
    }
}
