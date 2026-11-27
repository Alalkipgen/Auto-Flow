package io.github.alalkipgen.autoflow.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.theme.GlassShapes
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlin.test.*

/**
 * The delete confirm on the glass dialog (Library follow-up README §3): step 1 = the item (leading, name, caption — no
 * header without a name), one Danger "Delete…" menu row and Cancel; step 2 = Failed trash tile + title, the explanation,
 * Cancel + Delete. Confirm calls the removal once; Cancel, a tap outside (both steps) call nothing. Font 1.0 / 2.0 ×
 * English / Burmese at 360 dp, nothing clipped.
 */
@OptIn(ExperimentalTestApi::class)
class CardRemovalTest {
    private val phones = listOf(1f, 2f).flatMap { f -> UiLanguage.entries.map { f to it } }

    /** A tap on the scrim outside the dialog (the dialog's own root covers the window). */
    private fun ComposeUiTest.tapOutside() { onAllNodes(isRoot())[1].performMouseInput { click(Offset(5f, 5f)) }; waitForIdle() }
    private fun ComposeUiTest.click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }
    private fun ComposeUiTest.open(tag: String) {
        val action = onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        runOnIdle { assertTrue(action.action()) }
        waitForIdle()
    }

    @Test fun stepOneShowsTheItemOnlyWithANameStepTwoTheConfirmAndOnlyConfirmRemoves() = phones.forEach { (font, language) ->
        runDesktopComposeUiTest(width = 360, height = 780) {
            var removed = 0
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
                    LocalUiFontFamily provides BurmeseTestFont.family) {
                    Column {
                        CardRemoval("named", UiText.CARD_DELETE, tr(UiText.LIBRARY_DELETE_KIT, "name" to "Auto Flow Studio"),
                            UiText.LIBRARY_DELETE_KEEPS_STYLE, { { removed++ } }, name = "Auto Flow Studio", caption = tr(UiText.V3_LIBRARY_KITS),
                            leading = { SoftTile(AfIcons.Fill.Swatches, GlassColors.LibraryKits, size = GlassShapes.DialogTile) }) {
                            Box(it.fillMaxWidth().height(56.dp).testTag("named")) { AfText("Brand kit card") }
                        }
                        CardRemoval("plain", UiText.JOB_DELETE_HISTORY, tr(UiText.JOB_DELETE_HISTORY_CONFIRM),
                            UiText.JOB_DELETE_HISTORY_KEEPS_FILES, { { removed += 10 } }) { Box(it.fillMaxWidth().height(56.dp).testTag("plain")) { AfText("Job history card") } }
                    }
                }
            }
            fun t(key: UiText, vararg args: Pair<String, Any?>) = My.breakSyllables(key.text(language, *args))
            // Step 1 with a name: the item's leading, name and caption; one Danger menu row; Cancel.
            open("named")
            onNodeWithTag("glass-dialog-lead").assertIsDisplayed()
            onNodeWithTag("glass-dialog-title").assertTextEquals("Auto Flow Studio")
            onNodeWithTag("glass-dialog-subtitle").assertTextEquals(t(UiText.V3_LIBRARY_KITS))
            onNodeWithTag("glass-dialog-tile").assertDoesNotExist()
            onNodeWithTag("named-remove-menu").assertIsDisplayed().assert(hasText(t(UiText.CARD_DELETE)))
            onNodeWithTag("named-remove-cancel").assertIsDisplayed().assertTextEquals(t(UiText.CANCEL_KEEP))
            onNodeWithTag("named-remove-confirm").assertDoesNotExist()
            assertEquals(emptyList(), textFitProblems(360, font, strict = true), "step 1 $font ${language.code}")
            click("named-remove-cancel")
            onNodeWithTag("glass-dialog").assertDoesNotExist()
            // A tap outside closes step 1 and step 2; nothing is removed.
            open("named"); tapOutside(); onNodeWithTag("glass-dialog").assertDoesNotExist()
            open("named"); click("named-remove-menu")
            // Step 2: the Failed trash tile + the title, the explanation, Cancel + Delete.
            onNodeWithTag("glass-dialog-tile").assertIsDisplayed()
            onNodeWithTag("glass-dialog-title").assertTextEquals(t(UiText.LIBRARY_DELETE_KIT, "name" to "Auto Flow Studio"))
            onNodeWithTag("glass-dialog-lead").assertDoesNotExist()
            onNodeWithText(t(UiText.LIBRARY_DELETE_KEEPS_STYLE)).assertIsDisplayed()
            onNodeWithTag("named-remove-confirm").assertIsDisplayed().assertTextEquals(t(UiText.DELETE)).assertHeightIsAtLeast(44.dp)
            onNodeWithTag("named-remove-cancel").assertIsDisplayed().assertTextEquals(t(UiText.CANCEL_KEEP))
            assertEquals(emptyList(), textFitProblems(360, font, strict = true), "step 2 $font ${language.code}")
            tapOutside(); onNodeWithTag("glass-dialog").assertDoesNotExist()
            open("named"); click("named-remove-menu"); click("named-remove-cancel")
            assertEquals(0, removed)
            open("named"); click("named-remove-menu"); click("named-remove-confirm")
            onNodeWithTag("glass-dialog").assertDoesNotExist()
            assertEquals(1, removed)
            // Step 1 without a name: no header — the menu row (the caller's action) and Cancel only.
            open("plain")
            onNodeWithTag("glass-dialog-title").assertDoesNotExist()
            onNodeWithTag("glass-dialog-lead").assertDoesNotExist()
            onNodeWithTag("plain-remove-menu").assertIsDisplayed().assert(hasText(t(UiText.JOB_DELETE_HISTORY)))
            click("plain-remove-menu")
            onNodeWithTag("glass-dialog-title").assertTextEquals(t(UiText.JOB_DELETE_HISTORY_CONFIRM))
            click("plain-remove-confirm")
            assertEquals(11, removed)
        }
    }
}
