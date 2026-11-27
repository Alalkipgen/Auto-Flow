package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ProjectShortcutsTest {
    @Test fun passivePointerFocusModifiersBoundariesAndDisposal() = runDesktopComposeUiTest(width = 412, height = 760) {
        var at by mutableIntStateOf(0); var plays = 0
        var registered by mutableStateOf(true)
        setContent { ProjectShortcutHost {
            if (registered) {
                BlockShortcuts(if (at > 0) ({ at-- }) else null, if (at < 2) ({ at++ }) else null)
                PlayPauseShortcut { plays++ }
            }
            Box(Modifier.fillMaxSize().testTag("background"))
        } }
        val host = onNodeWithTag("project-shortcuts")
        onNodeWithTag("background").performMouseInput { click() }; host.assertIsFocused()
        host.performKeyInput { pressKey(Key.DirectionLeft) }; assertEquals(0, at); host.assertIsFocused()
        repeat(4) { host.performKeyInput { pressKey(Key.DirectionRight) } }
        assertEquals(2, at); host.assertIsFocused()
        host.performKeyInput { pressKey(Key.Spacebar) }; assertEquals(1, plays)
        for (modifier in listOf(Key.CtrlLeft, Key.AltLeft, Key.MetaLeft, Key.ShiftLeft)) {
            host.performKeyInput { keyDown(modifier); pressKey(Key.Spacebar); pressKey(Key.DirectionLeft); keyUp(modifier) }
        }
        host.performKeyInput { pressKey(Key.A); keyDown(Key.CtrlLeft); pressKey(Key.C); keyUp(Key.CtrlLeft) }
        assertEquals(1, plays); assertEquals(2, at)
        host.performKeyInput { pressKey(Key.DirectionLeft) }; assertEquals(1, at)
        runOnIdle { registered = false }
        host.performKeyInput { pressKey(Key.Spacebar); pressKey(Key.DirectionRight) }
        assertEquals(1, plays); assertEquals(1, at, "disposed targets cannot run")
        runOnIdle { registered = true }
        host.performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.Spacebar) }
        assertEquals(2, at); assertEquals(2, plays)
    }

    @Test fun afAndRawTextFieldsKeepTypingSpacesCaretAndSelectionKeys() = runDesktopComposeUiTest(width = 412, height = 760) {
        var af by mutableStateOf("before"); var raw by mutableStateOf("before"); var actions = 0
        setContent { ProjectShortcutHost {
            PlayPauseShortcut { actions++ }; BlockShortcuts({ actions++ }, { actions++ })
            Column {
                AfTextField(af, { af = it }, "Af input", Modifier.testTag("af"))
                BasicTextField(raw, { raw = it }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("raw"))
                Box(Modifier.size(100.dp).testTag("background"))
            }
        } }
        for (tag in listOf("af", "raw")) {
            val field = if (tag == "af") onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag))) else onNodeWithTag(tag)
            field.assertIsDisplayed().performMouseInput { click() }; field.assertIsFocused()
            field.performTextInput(" typed space ")
            field.performKeyInput {
                pressKey(Key.Spacebar); pressKey(Key.DirectionLeft); pressKey(Key.DirectionRight)
                keyDown(Key.ShiftLeft); pressKey(Key.DirectionLeft); keyUp(Key.ShiftLeft)
                keyDown(Key.CtrlLeft); pressKey(Key.A); keyUp(Key.CtrlLeft)
            }
            field.assertIsFocused()
            assertEquals(0, actions, "$tag must never leak a shortcut")
            assertTrue((if (tag == "af") af else raw).contains("typed space"))
        }
        onNodeWithTag("background").performMouseInput { click() }
        onNodeWithTag("project-shortcuts").assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(1, actions, "unconsumed passive tap restores project shortcuts")
    }

    @Test fun buttonsScrubberDraggingAndWheelKeepTheirOwnInput() = runDesktopComposeUiTest(width = 412, height = 500) {
        var actions = 0; var clicks = 0; var value by mutableFloatStateOf(.25f)
        setContent { ProjectShortcutHost {
            PlayPauseShortcut { actions++ }; BlockShortcuts({ actions++ }, { actions++ })
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("scroll")) {
                AfButton("Button", { clicks++ }, Modifier.testTag("button"))
                AfScrubber(value, "Seek", { value = it }, Modifier.testTag("scrubber"))
                Spacer(Modifier.height(900.dp))
                AfButton("Last", { clicks++ }, Modifier.testTag("last"))
            }
        } }
        // Tab, not a hidden callback, gives the native button keyboard focus.
        onNodeWithTag("project-shortcuts").performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("button").assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(1, clicks); assertEquals(0, actions)
        val scrubber = onNodeWithTag("scrubber").assertIsDisplayed()
        scrubber.performMouseInput { click() }; scrubber.assertIsFocused()
        val before = value
        scrubber.performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(before + .01f, value, .001f); assertEquals(0, actions)
        scrubber.performTouchInput { swipeRight() }
        assertTrue(value > before, "real drag must reach the child scrubber")
        onNodeWithTag("scroll").performMouseInput { scroll(20_000f) }; waitForIdle()
        onNodeWithTag("last").assertIsDisplayed().performMouseInput { click() }
        assertEquals(2, clicks); assertEquals(0, actions)
    }
}
