package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * Group 2 C 11–12 (PR 6): the phone block detail in the glass look — the header's ← / → are 40 dp icon buttons (same
 * tags, their description is the label, no arrow text), no ← / ⏱ / ⚠ left in the detail's labels (icons carry them;
 * the caption window keeps its "→" between the two times), Back has no arrow, the Block tools tabs are one segmented
 * control (Record / Clean switch the tab) and the caption timing editor opens and cancels without saving.
 */
@OptIn(ExperimentalTestApi::class)
class StudioVoiceDetailGlassTest {
    private val symbols = listOf("←", "⏱", "⚠", "🔒")

    private fun ComposeUiTest.texts(): List<String> = onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.ContentDescription)).flatten() }

    @Test fun headerIconButtonsNoSymbolsSegmentedTabsAndCaptionTimingCancelDoesNotSave() {
        assertEquals("Back", UiText.BACK.en)
        assertEquals("နောက်သို့", UiText.BACK.my)
        for ((language, font) in listOf(UiLanguage.ENGLISH to 1f, UiLanguage.BURMESE to 2f)) runDesktopComposeUiTest(width = 360, height = 760) {
            val fixture = QaRecapFixture.create(Files.createTempDirectory("g2c-detail").toFile())
            val before = File(fixture.dir, "project.json").readBytes()
            val fs = JvmFs(); val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, fixture.project)) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val model = RecapModel(ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, File(fixture.dir.parentFile, "index.json").path), current), fs, null, null, scope)
            try {
                setContent { CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 360f) {
                        StudioRecapScreen(model, RecapStep.VOICE, 360f, {}, RecapNavigation({}, {}, {}))
                    }
                } }
                onNodeWithTag("voice-open-0").performScrollTo().performMouseInput { click() }
                waitForIdle()
                for ((tag, label) in listOf("voice-detail-previous" to UiText.V3_PREVIOUS_BLOCK, "voice-detail-next" to UiText.V3_NEXT_BLOCK)) {
                    val node = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
                    assertEquals(listOf(label.text(language)), node.config.getOrNull(SemanticsProperties.ContentDescription), tag)
                    assertTrue(node.config.getOrNull(SemanticsProperties.Text).orEmpty().none { "←" in it.text || "→" in it.text }, tag)
                }
                onNodeWithTag("voice-detail-previous").assertIsNotEnabled()
                onNodeWithTag("voice-detail-next").assertIsEnabled()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("voice-take-1").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(emptyList(), texts().filter { text -> symbols.any { it in text } }, "${language.code} labels with symbols")
                assertEquals(emptyList(), textFitProblems(360, font))

                // Block tools: one segmented control; Record shows Import file, Clean its hint.
                onNode(hasText(My.breakSyllables(UiText.VOICE_TAB_RECORD.text(language))) and hasClickAction()).performScrollTo().performMouseInput { click() }
                onNodeWithText(UiText.IMPORT_FILE.text(language)).performScrollTo().assertIsDisplayed()
                onNode(hasText(My.breakSyllables(UiText.VOICE_TAB_RECORD.text(language))) and hasClickAction()).assertIsSelected()
                onNode(hasText(My.breakSyllables(UiText.VOICE_TAB_CLEAN.text(language))) and hasClickAction()).performScrollTo().performMouseInput { click() }
                onNodeWithText(UiText.IMPORT_FILE.text(language)).assertDoesNotExist()
                onNode(hasText(My.breakSyllables(UiText.VOICE_TAB_SPEAK.text(language))) and hasClickAction()).performScrollTo().performMouseInput { click() }

                // Speak · More options: the caption window row, Edit opens the two fields, Cancel closes them, nothing saved.
                onNode(hasContentDescription(UiText.V3_MORE_OPTIONS.text(language)) and hasStateDescription(UiText.V3_COLLAPSED.text(language))).performScrollTo().performMouseInput { click() }
                onNodeWithText(UiText.EDIT_CAPTION_TIMING.text(language)).performScrollTo().assertIsDisplayed().performMouseInput { click() }
                onNodeWithText(UiText.CAPTION_TIMING_VOICE.text(language)).performScrollTo().assertIsDisplayed()
                onNodeWithText(UiText.CANCEL.text(language)).performScrollTo().performMouseInput { click() }
                onNodeWithText(UiText.EDIT_CAPTION_TIMING.text(language)).performScrollTo().assertIsDisplayed()
                assertEquals(emptyList(), texts().filter { text -> symbols.any { it in text } }, "${language.code} labels with symbols (tools)")
                assertContentEquals(before, File(fixture.dir, "project.json").readBytes(), "browsing the detail saves nothing")
            } finally { scope.cancel() }
        }
    }
}
