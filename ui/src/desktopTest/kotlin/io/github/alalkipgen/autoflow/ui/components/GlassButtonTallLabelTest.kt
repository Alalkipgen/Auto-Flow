package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A text button's label can be taller than its 48 dp target (Windows' Myanmar Text fallback at font 2.0 — CI on
 * #286). The button must still lay out (no negative padding) and stay clickable.
 */
@OptIn(ExperimentalTestApi::class)
class GlassButtonTallLabelTest {
    @Test fun textButtonWithALabelTallerThanTheTargetStillLaysOut() = runDesktopComposeUiTest(width = 412, height = 400) {
        var clicks = 0
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 4f)) {
                GlassButton("Step-by-step →", { clicks++ }, kind = ButtonKind.Text)
            }
        }
        onNodeWithText("Step-by-step →", useUnmergedTree = true).assertHeightIsAtLeast(49.dp)
        onNodeWithText("Step-by-step →").performClick()
        assertEquals(1, clicks)
    }
}
