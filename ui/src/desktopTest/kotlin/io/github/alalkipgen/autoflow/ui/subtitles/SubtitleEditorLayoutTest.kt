package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.subtitles.CueIssue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleOps
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlesFixture.Line
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlin.math.abs
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/** P9-12a: the Subtitles editor's top bar, video and cue strip on a phone and a medium window (DESIGN.md §9.7, panel 5). */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SubtitleEditorLayoutTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    /** Line 1 overlaps line 2 (too short, no Burmese yet); lines 1 and 3 have Burmese; a 12-second video. */
    private fun fixture() = SubtitlesFixture(listOf(Line(1_000, 3_000, "We have to leave tonight, before the rain starts.", "ဒီည ထွက်သွားရမယ်"),
        Line(2_500, 2_900, "Too short"), Line(4_000, 6_000, "Third line", "တတိယ စာကြောင်း")), 12_000)

    private fun ComposeUiTest.show(f: SubtitlesFixture, font: Float = 1f, language: UiLanguage = UiLanguage.ENGLISH) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
            LocalUiFontFamily provides BurmeseTestFont.family) { SubtitlesScreen(f.model, f.session, f.editor, player = f.paused, onBack = {}) }
    }

    /** The editor is open; at font 2.0 the first line can start below the fold, so the list scrolls to it. */
    private fun ComposeUiTest.ready() {
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-cues").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("subtitles-cues").performScrollToKey("c1")
    }

    private fun ComposeUiTest.click(tag: String) = onNodeWithTag(tag).also { runCatching { it.performScrollTo() } }.performMouseInput { click() }

    private fun inTopBar(text: String) = hasText(text) and hasAnyAncestor(hasTestTag("subtitles-top-bar"))

    @Test fun theTopBarNamesTheToolAndProjectAndStaysWhileTheListScrolls() {
        for (language in UiLanguage.entries) runDesktopComposeUiTest(width = 412, height = 900) {
            val f = fixture()
            show(f, language = language)
            ready()
            onNode(inTopBar(UiText.V3_SUBTITLES.text(language)), useUnmergedTree = true).assertIsDisplayed()
            onNode(inTopBar("Clip"), useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("subtitles-back").assertContentDescriptionEquals(UiText.BACK.text(language))
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            onAllNodes(hasText(UiText.PROJECTS_BACK.text(language))).assertCountEquals(0) // the old text button is gone
            val top = onNodeWithTag("subtitles-top-bar").fetchSemanticsNode().boundsInRoot
            onNodeWithTag("subtitles-cues").performScrollToKey("c3")
            assertEquals(top, onNodeWithTag("subtitles-top-bar").fetchSemanticsNode().boundsInRoot, "pinned above the list")
            f.editor.edit { SubtitleOps.setText(it, "c3", "Typed") }
            waitForIdle()
            onNode(inTopBar(My.breakSyllables("${UiText.SUB_NOT_SAVED.text(language)} · Clip")), useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test fun theStripDrawsEveryLineInItsStateAndThePlayhead() = runDesktopComposeUiTest(width = 412, height = 900) {
        val f = fixture()
        show(f)
        ready()
        fun near(expected: Color, ms: Long, what: String, px: Int? = null) {
            val image = onNodeWithTag("subtitles-strip-track").captureToImage()
            val x = px ?: (ms * image.width / 12_000f).toInt()
            val got = image.toPixelMap()[x, image.height / 2]
            val d = maxOf(abs(expected.red - got.red), abs(expected.green - got.green), abs(expected.blue - got.blue))
            assertTrue(d < 0.03f, "$what at ${ms}ms: expected $expected, got $got")
        }
        val idle = AutoFlowColors.PrimaryFill.copy(alpha = .33f).compositeOver(AutoFlowColors.Card)
        near(AutoFlowColors.Amber, 1_500, "line 1 overlaps: amber")
        near(AutoFlowColors.Failed, 2_700, "line 2 has no Burmese: red")
        near(idle, 5_000, "line 3 needs nothing: primary at a third")
        near(AutoFlowColors.Card, 9_000, "no line at 0:09")
        onNodeWithTag("subtitles-strip-length").assertTextEquals("0:12.0")
        click("subtitles-cue-c3") // select + seek
        near(AutoFlowColors.PrimaryFill, 5_000, "the selected line: primary")
        onNodeWithTag("subtitles-strip-now").assertTextEquals("0:04.0")
        val width = onNodeWithTag("subtitles-strip-track").fetchSemanticsNode().size.width
        near(AutoFlowColors.Text, 4_000, "the playhead", px = (4_000f / 12_000 * width).toInt() + 1)
        click("subtitles-cue-c2")
        near(AutoFlowColors.PrimaryFill, 2_700, "a selected line stays primary")
        near(idle, 5_000, "line 3 no longer selected")
        assertEquals(CueMark.UNTRANSLATED, cueMark(false, setOf(CueIssue.OVERLAP, CueIssue.NOT_TRANSLATED)))
        assertEquals(CueMark.SELECTED, cueMark(true, setOf(CueIssue.NOT_TRANSLATED)))
        assertEquals(CueMark.NORMAL, cueMark(false, null))
    }

    /** The video and its strip stay above the list only while the list keeps its room; else they top the list. */
    @Test fun theVideoIsPinnedOnlyWhenTheListKeepsItsRoom() {
        for ((height, font, pinned) in listOf(Triple(760, 1f, true), Triple(900, 1.3f, true), Triple(760, 2f, false), Triple(480, 1f, false))) {
            runDesktopComposeUiTest(width = 360, height = height) {
                val f = fixture()
                show(f, font, UiLanguage.BURMESE)
                ready()
                for (tag in listOf("subtitles-preview-card", "subtitles-strip")) {
                    val inList = onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("subtitles-cues"))).fetchSemanticsNodes().isNotEmpty()
                    assertEquals(!pinned, inList, "$tag at 360 × $height × $font")
                }
                onNodeWithTag("subtitles-cue-c1").assertIsDisplayed()
                if (!pinned) {
                    onNodeWithTag("subtitles-cues").performScrollToIndex(0)
                    onNodeWithTag("subtitles-strip").assertIsDisplayed()
                }
            }
        }
    }

    /** Phone 360 at font 1.0 / 2.0, 412 at 1.3 and a 760 medium window, both languages, unsaved, Both view: strict no-clip. */
    @Test fun fitsWithoutClippingOnPhonesAndMedium() {
        for ((width, font) in listOf(360 to 1f, 360 to 2f, 412 to 1.3f, 760 to 1f)) for (language in UiLanguage.entries) {
            runDesktopComposeUiTest(width = width, height = 900) {
                val f = fixture()
                show(f, font, language)
                ready()
                f.editor.setView(TextChoice.BOTH)
                f.editor.edit { SubtitleOps.setText(it, "c3", "Typed again") }
                waitForIdle()
                onNodeWithTag("subtitles-cues").performScrollToIndex(0)
                onNodeWithTag("subtitles-strip").assertIsDisplayed()
                onNodeWithTag("subtitles-save").assertIsDisplayed()
                assertEquals(emptyList(), textFitProblems(width, font, strict = true), "top · $width × $font × $language")
                onNodeWithTag("subtitles-cues").performScrollToKey("c3")
                assertEquals(emptyList(), textFitProblems(width, font, strict = true), "line 3 · $width × $font × $language")
            }
        }
    }
}
