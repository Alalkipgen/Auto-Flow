package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AfScrubberTest {
    @Test fun pointerTouchAndFocusedKeyboardSeekWithinTheSameRange() = runDesktopComposeUiTest(width = 360, height = 200) {
        var value by mutableStateOf(.5f)
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { AfScrubber(value, "Output seek", { value = it }) } }
        val node = onNodeWithContentDescription("Output seek").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val width = node.fetchSemanticsNode().size.width
        node.performMouseInput { click(Offset(12 + (width - 24) * .25f, 24f)) }
        assertEquals(.25f, value, .001f)
        node.performKeyInput { pressKey(Key.DirectionRight) }; assertEquals(.26f, value, .001f)
        node.performKeyInput { pressKey(Key.MoveEnd) }; assertEquals(1f, value)
        node.performKeyInput { pressKey(Key.MoveHome) }; assertEquals(0f, value)
        node.performTouchInput { swipe(Offset(12f, 24f), Offset(width - 12f, 24f), 300) }
        assertEquals(1f, value, .001f)
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(1f, 0f..1f)))
    }
    @Test fun disabledAndNonFiniteInputAreHonestAndNeverDispatchAHiddenAction() = runDesktopComposeUiTest(width = 360, height = 200) {
        var changes = 0
        setContent { Column {
            AfScrubber(Float.NaN, "Invalid input", { changes++ }, enabled = false)
            AfScrubber(3f, "Clamped input", { changes++ })
        } }
        val disabled = onNodeWithContentDescription("Invalid input").assertIsNotEnabled().assertIsDisplayed()
        disabled.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0f, 0f..1f)))
        disabled.performMouseInput { click() }
        assertEquals(0, changes)
        onNodeWithContentDescription("Clamped input").assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(1f, 0f..1f)))
    }
}