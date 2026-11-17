package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.syllableCuts
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PR-7 follow-up: a missing decorative glyph must not change labels, Burmese shaping or button behavior. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class AfButtonSymbolTest(val language: UiLanguage, val bundled: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/bundled={1}")
        fun cases() = UiLanguage.entries.flatMap { language ->
            listOf(true, false).map { bundled -> arrayOf<Any>(language, bundled) }
        }
    }

    @Test fun regenerateSymbolKeepsReadableLabelAndAccessibleActionAtLargeFont() =
        runDesktopComposeUiTest(width = 412, height = 760) {
            val font = if (bundled) BurmeseTestFont.family else FontFamily.Default
            val one = "↻ ${UiText.REGENERATE.text(language)}"
            val all = "↻ ${UiText.REGENERATE_ALL.text(language)}"
            val plain = "မြန်မာစာ"
            var clicked = 0
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalUiFontFamily provides font) {
                    Column(
                        Modifier.fillMaxSize().background(AutoFlowColors.Background).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        AfButton(one, { clicked++ })
                        AfButton(all, { clicked++ }, enabled = false)
                        AfText(plain)
                    }
                }
            }
            onNodeWithText(one).assertIsDisplayed().assertHasClickAction().assertIsEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .performMouseInput { click() }
            assertEquals(1, clicked)
            onNodeWithText(all).assertIsDisplayed().assertIsNotEnabled().performMouseInput { click() }
            assertEquals(1, clicked, "a disabled regenerate action must not run")
            for (label in listOf(one, all)) {
                val bounds = onNodeWithText(label).fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.width >= 48 && bounds.height >= 48)
                val layouts = mutableListOf<TextLayoutResult>()
                onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
                    .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                val layout = layouts.single()
                assertEquals(label, layout.layoutInput.text.text, "keep the existing accessible/test label")
                val symbol = layout.layoutInput.placeholders.single()
                assertEquals(0, symbol.start)
                assertEquals(1, symbol.end, "only the decorative arrow may be drawn inline")
                assertTrue(layout.layoutInput.text.spanStyles.isEmpty(), "keep the original prose styling")
                assertEquals(font, layout.layoutInput.style.fontFamily, "do not replace the prose font")
                assertFalse(layout.hasVisualOverflow)
                assertEquals(emptyList(), syllableCuts(layout))
            }
            val ordinaryText = onNodeWithText(plain).fetchSemanticsNode().config[SemanticsProperties.Text].single()
            assertTrue(ordinaryText.spanStyles.isEmpty(), "ordinary Burmese text must remain unstyled")
            val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "regenerate-symbol")
                .apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "${language.code}-2.0-$bundled.png"))
        }
}