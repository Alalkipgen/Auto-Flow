package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ControlsTest {
    @Test
    fun buttonsClickOnlyWhenEnabled() = runComposeUiTest {
        var clicks = 0
        setContent {
            Row {
                AfButton("Save", onClick = { clicks++ }, kind = ButtonKind.Primary)
                AfButton("Test", onClick = { clicks += 100 }, enabled = false)
            }
        }
        onNodeWithText("Save").performClick()
        onNodeWithText("Test").assertIsNotEnabled()
        assertEquals(1, clicks)
    }

    @Test
    fun switchAndChipReportTheirState() = runComposeUiTest {
        var on by mutableStateOf(false)
        var picked = ""
        setContent {
            Column {
                AfSwitch("Use fake providers", checked = on, onChange = { on = it })
                AfChip("9:16", selected = true, onClick = { picked = "9:16" })
            }
        }
        onNodeWithText("Use fake providers").performClick()
        waitForIdle()
        assertEquals(true, on)
        onNodeWithText("9:16").performClick()
        assertEquals("9:16", picked)
    }

    @Test
    fun secretFieldTakesInputButNeverShowsIt() = runComposeUiTest {
        var typed = ""
        setContent {
            var value by remember { mutableStateOf("") }
            AfTextField(value, onChange = { value = it; typed = it }, placeholder = "Gemini API key", secret = true)
        }
        val field = onNodeWithContentDescription("Gemini API key")
        field.performTextInput("AIzaSecret1234")
        waitForIdle()
        assertEquals("AIzaSecret1234", typed)
        // What is drawn (EditableText) is masked; the field is marked as a password for accessibility tools.
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("•".repeat(14))))
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }
}
