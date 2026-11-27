package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleOps
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlesFixture.Line
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.AfDesktop
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlin.math.abs
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/** P9-12c: the Subtitles editor from 840 dp (DESIGN.md §9.7 D4, §9.8): video left, the lines in a pane right, key hints. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SubtitleEditorDesktopTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    private fun fixture() = SubtitlesFixture(listOf(Line(1_000, 3_000, "We have to leave tonight, before the rain starts.", "ဒီည ထွက်သွားရမယ်"),
        Line(2_500, 2_900, "Too short"), Line(4_000, 6_000, "Third line", "တတိယ စာကြောင်း")), 12_000)

    private fun ComposeUiTest.show(f: SubtitlesFixture, font: Float = 1f, language: UiLanguage = UiLanguage.ENGLISH) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
                LocalUiFontFamily provides BurmeseTestFont.family) { SubtitlesScreen(f.model, f.session, f.editor, player = f.paused, onBack = {}) }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-cue-c1").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.bounds(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private val keys = listOf("Space", "↑ ↓", "Enter", "Ctrl S", "Ctrl Z")

    /** The list pane is 460 dp (at most 45 % of the window) on the right; the video, its strip and the edit panel left. */
    @Test fun theLinesAreAPaneRightOfTheVideo() {
        for ((width, side) in listOf(1440 to 460f, 1024 to 460f, 900 to 405f)) for (language in UiLanguage.entries) {
            runDesktopComposeUiTest(width = width, height = 900) {
                val f = fixture()
                show(f, language = language)
                val pane = bounds("subtitles-list-pane")
                val left = bounds("subtitles-left")
                assertTrue(abs(pane.width - side) < 1f, "list pane $width: ${pane.width} ≠ $side")
                assertTrue(abs(pane.left - left.right - AfDesktop.PaneGap.value) < 1f, "pane gap at $width")
                assertTrue(left.width > pane.width || width < 1000, "the video side is the larger one at $width")
                for (tag in listOf("subtitles-preview", "subtitles-strip")) {
                    onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("subtitles-left"))).assertIsDisplayed()
                }
                onNode(hasTestTag("subtitles-cues") and hasAnyAncestor(hasTestTag("subtitles-list-pane"))).assertExists()
                onNodeWithTag("subtitles-cue-edit-c2").performMouseInput { click() }
                onNode(hasTestTag("subtitles-edit-panel") and hasAnyAncestor(hasTestTag("subtitles-left"))).assertIsDisplayed()
                onNodeWithTag("subtitles-cue-c1").assertIsDisplayed()
            }
        }
    }

    /** 🆕-7 (P9-G5-8): "Lines" + the count over the pane's list; "#2 / 3" once line 2 is selected. */
    @Test fun theListPaneHasALinesHeader() {
        for (language in UiLanguage.entries) runDesktopComposeUiTest(width = 1440, height = 900) {
            val f = fixture()
            show(f, language = language)
            val header = onNode(hasTestTag("subtitles-lines-header") and hasAnyAncestor(hasTestTag("subtitles-list-pane")))
            header.assertIsDisplayed()
            header.assert(hasText(My.breakSyllables(UiText.V3_SUB_LINES.text(language)))).assert(hasText("3"))
            assertTrue(bounds("subtitles-lines-header").bottom <= bounds("subtitles-cue-c1").top, "the header sits over the lines")
            assertTrue(abs(bounds("subtitles-lines-header").width - bounds("subtitles-list-pane").width) < 1f, "full pane width")
            onNodeWithTag("subtitles-cue-c2").performMouseInput { click() }
            waitForIdle()
            onNodeWithTag("subtitles-lines-header").assert(hasText("#2 / 3"))
        }
        runDesktopComposeUiTest(width = 412, height = 900) { // a phone has no pane and no header
            show(fixture())
            onAllNodesWithTag("subtitles-lines-header").assertCountEquals(0)
        }
    }

    /** H 2 (P9-G5-8): keys left, then 🆕-6's caption, Discard 170 dp and Save 200 dp on the right. */
    @Test fun theDesktopBarHasTheCaptionBeforeFixedButtons() {
        for (language in UiLanguage.entries) runDesktopComposeUiTest(width = 1440, height = 900) {
            val f = fixture()
            show(f, language = language)
            f.editor.edit { SubtitleOps.setText(it, "c3", "Typed") }
            waitForIdle()
            assertTrue(abs(bounds("subtitles-discard").width - 170f) < 1f, "Discard ${bounds("subtitles-discard").width}")
            assertTrue(abs(bounds("subtitles-save").width - 200f) < 1f, "Save ${bounds("subtitles-save").width}")
            val caption = onNodeWithTag("subtitles-edited-caption", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            onNodeWithTag("subtitles-edited-caption", useUnmergedTree = true)
                .assert(hasText(My.breakSyllables(UiText.V3_SUB_EDITED_CAPTION.text(language, "n" to 1))))
            assertTrue(bounds("subtitles-keys").right < caption.left, "keys left of the caption")
            assertTrue(caption.right <= bounds("subtitles-discard").left, "caption before Discard")
            assertTrue(bounds("subtitles-discard").right <= bounds("subtitles-save").left, "Discard before Save")
        }
    }

    /** The sticky bar names the editor's real keys left of the buttons (no Esc: the editor has none); phones show none. */
    @Test fun theDesktopBarListsTheEditorsKeys() {
        for (language in UiLanguage.entries) runDesktopComposeUiTest(width = 1440, height = 900) {
            val f = fixture()
            show(f, language = language)
            onAllNodesWithTag("subtitles-keys").assertCountEquals(0) // nothing to save, no export here: no bar
            f.editor.edit { SubtitleOps.setText(it, "c3", "Typed") }
            waitForIdle()
            val caps = onAllNodesWithTag("key-hint-cap", useUnmergedTree = true).fetchSemanticsNodes()
                .map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { t -> t.text } }
            assertEquals(keys, caps)
            for (key in listOf(UiText.SUB_KEY_PLAY, UiText.SUB_KEY_LINE, UiText.SUB_EDIT_LINE, UiText.SAVE, UiText.SUB_UNDO)) {
                onNode(hasText(My.breakSyllables(key.text(language))) and hasAnyAncestor(hasTestTag("subtitles-keys")), useUnmergedTree = true).assertExists()
            }
            onAllNodes(hasText("Esc", substring = true), useUnmergedTree = true).assertCountEquals(0)
            assertTrue(bounds("subtitles-keys").right < bounds("subtitles-discard").left, "hints left of the buttons")
            // The keys work as listed: Ctrl Z undoes the edit.
            onNodeWithTag("project-shortcuts").performKeyInput { withKeyDown(androidx.compose.ui.input.key.Key.CtrlLeft) { pressKey(androidx.compose.ui.input.key.Key.Z) } }
            assertEquals("Third line", f.editor.state.value.draft!!.doc.cues.first { it.id == "c3" }.text)
        }
        runDesktopComposeUiTest(width = 412, height = 900) {
            val f = fixture()
            show(f)
            f.editor.edit { SubtitleOps.setText(it, "c3", "Typed") }
            waitForIdle()
            onNodeWithTag("subtitles-save").assertIsDisplayed()
            onAllNodesWithTag("key-hints", useUnmergedTree = true).assertCountEquals(0)
        }
    }

    /** 1024 × 680 (the smallest window, P9-18) and 1440 × 900, fonts 1.0 / 1.3, both languages, Both view: strict no-clip. */
    @Test fun fitsWithoutClippingOnTheDesktop() {
        for ((size, font) in listOf((1024 to 680) to 1f, (1024 to 680) to 1.3f, (1440 to 900) to 1f, (1440 to 900) to 1.3f, (1440 to 900) to 2f)) for (language in UiLanguage.entries) {
            runDesktopComposeUiTest(width = size.first, height = size.second) {
                val f = fixture()
                show(f, font, language)
                f.editor.setView(TextChoice.BOTH)
                f.editor.edit { SubtitleOps.setText(it, "c3", "Typed again") }
                waitForIdle()
                onNodeWithTag("subtitles-cue-edit-c1").performMouseInput { click() }
                onNodeWithTag("subtitles-keys").assertIsDisplayed()
                assertEquals(emptyList(), textFitProblems(size.first, font, strict = true), "${size.first} × $font × $language")
            }
        }
    }
}
