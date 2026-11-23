package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.recap.AiRunTiming
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.workflow.Blocker
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.export.ExportHarness
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark

/**
 * The Story step's states sheet (Group 2 D 22 · 1–8), each under its [label] in a `story-state-n` box: the Script run as the
 * pinned glass job card (writing · Gemini limit wait), the Writing options card with its confirms (first story · again ·
 * write it myself), the edited credit line, and the two blockers. [open] makes the clicks and the typing of 3–6. Shared by
 * [StoryStatesGlassTest] and the screenshots; [timing] / [clear] set and remove the two runs' fixed live times.
 */
internal object StoryStates {
    private const val RUN = "script-run-1"
    private const val WAIT = "script-run-2"
    const val CREDIT = "ကား — Interstellar (2014) မှ"
    val SCENES = List(12) { RecapScene(it * 800L, it * 800L + 800L, picked = true) }
    /** A summary to write from (the fixtures have none, and no subtitles): Write script is enabled, as in the sample. */
    const val SUMMARY = "A farmer and former pilot leaves his family to find humanity a new home."

    /** "Waiting for Gemini · 0:12" and "Gemini limit reached · retrying in 23 s" (fixed marks: the labels never tick). */
    fun timing() {
        AiRunTiming(id = RUN).restore(AiRunTiming.Live(object : TimeMark { override fun elapsedNow() = 12.seconds }))
        AiRunTiming(id = WAIT).restore(AiRunTiming.Live(object : TimeMark { override fun elapsedNow() = 40.seconds },
            retryUntil = object : TimeMark { override fun elapsedNow() = (-23).seconds }))
    }
    fun clear() { AiRunTiming(id = RUN).restore(null); AiRunTiming(id = WAIT).restore(null) }

    /**
     * Bumped by the screenshots with the test clock held: re-creates the two job cards, whose moving bar segment the test
     * stops while the clock runs by itself, so the bar can be stepped to mid-way as in the sample.
     */
    val bars = mutableIntStateOf(0)

    @Composable
    fun Sheet(model: RecapModel, base: RecapUiState, label: @Composable (String) -> Unit) {
        // Picked scenes to write from: the demo's picks, or 12 for the QA fixture, which has none (the sample's "12 segments").
        val recap = base.recap!!.let { r -> if (r.picked.isNotEmpty()) r else r.copy(scenes = SCENES) }
        val ready = base.copy(summary = base.summary.ifBlank { SUMMARY })
        val writing = ready.copy(job = RecapModel.WRITING, jobNotice = null, runnerJobId = null, scriptRunId = RUN, progress = null, message = null)
        @Composable fun state(n: Int, content: @Composable ColumnScope.() -> Unit) =
            Column(Modifier.fillMaxWidth().testTag("story-state-$n"), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        @Composable fun options(state: RecapUiState, r: io.github.alalkipgen.autoflow.core.recap.RecapV2 = recap) =
            ScriptCard(model, state, r, studioOptions = true, pinnedRun = true)
        label("1 · Writing with Gemini → card pinned under the top bar · Write buttons hidden")
        state(1) { key(bars.intValue) { RecapForegroundCards(model, writing, glass = true) }; options(writing) }
        label("2 · Gemini limit reached · waits, then retries by itself")
        state(2) { key(bars.intValue) { RecapForegroundCards(model, writing.copy(scriptRunId = WAIT), glass = true) } }
        label("3 · Write script · confirm (first story)")
        state(3) { options(ready.copy(script = null, story = null)) }
        label("4 · Write script again · confirm (story exists: replaces it)")
        state(4) { options(ready) }
        label("5 · Write it myself · confirm (story exists)")
        state(5) { options(ready) }
        label("6 · Credit line edited → Save credit")
        state(6) { options(ready) }
        label("7 · Cannot write yet (no summary / subtitles)")
        state(7) { options(base.copy(summary = "", transcribe = base.transcribe.copy(lines = 0)),
            recap.copy(sources = recap.sources.copy(subtitles = null))) }
        label("8 · No scene picked")
        state(8) { options(ready, recap.copy(scenes = recap.scenes.map { it.copy(picked = false) })) }
    }

    /** Unfolds the folded cards, opens the confirms of 3 / 4 / 5 and types the credit line of 6 (focused: the cyan ring). */
    @OptIn(ExperimentalTestApi::class)
    fun ComposeUiTest.open(language: UiLanguage) {
        val title = My.breakSyllables(UiText.V3_STORY_OPTIONS.text(language))
        val folded = hasContentDescription(title) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            UiText.V3_COLLAPSED.text(language))
        while (onAllNodes(folded).fetchSemanticsNodes().isNotEmpty()) { onAllNodes(folded)[0].performSemanticsAction(SemanticsActions.OnClick); waitForIdle() }
        fun click(n: Int, key: UiText) = onNode(hasClickAction() and hasText(key.text(language)) and hasAnyAncestor(hasTestTag("story-state-$n")))
            .assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick).also { waitForIdle() }
        click(3, UiText.WRITE_SCRIPT); click(4, UiText.WRITE_SCRIPT); click(5, UiText.WRITE_MYSELF)
        onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("story-state-6"))).apply {
            performSemanticsAction(SemanticsActions.RequestFocus); performTextReplacement(CREDIT)
        }
        waitForIdle()
    }
}

/**
 * Group 2 D 21–22 (PR 11): the Story step's Writing options and states in the glass look — glass filter chips, glass credit
 * field + small Save credit, the confirms as amber callouts with Write / Start and Back, the blockers as Muted info rows,
 * the Script run as the glass job card, Blocks empty as the duotone box — and §2.1: while Blocks is empty the gate line
 * does not repeat "Write the story first"; every other blocker still shows. Every text fits a 360 dp phone, looking writes
 * nothing.
 */
@OptIn(ExperimentalTestApi::class)
class StoryStatesGlassTest {
    @AfterTest fun clearTiming() = StoryStates.clear()

    @Test fun storyStatesShowTheGlassLookAndWriteNothing() {
        for ((language, font) in listOf(UiLanguage.ENGLISH to 1f, UiLanguage.BURMESE to 2f)) runDesktopComposeUiTest(width = 360, height = 760) {
            ExportHarness().use { h ->
                val before = h.bytes()
                h.recap.refresh()
                val base = h.recap.state.value
                StoryStates.timing()
                setContent { CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp).testTag("states"),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StoryStates.Sheet(h.recap, base) { AfText(it, color = AutoFlowColors.PrimaryText, type = AfType.Caption) }
                    }
                } }
                waitForIdle()
                with(StoryStates) { open(language) }
                fun text(key: UiText, vararg args: Pair<String, Any?>) = key.text(language, *args)
                fun inState(n: Int) = hasAnyAncestor(hasTestTag("story-state-$n"))
                // 1–2: the Script run = the glass job card (Writing options tile, small danger Cancel with x), its live line.
                onAllNodesWithTag("recap-script-run").assertCountEquals(2)
                onAllNodes(hasTestTag("card-tile") and hasAnyAncestor(hasTestTag("recap-script-run")), useUnmergedTree = true).assertCountEquals(2)
                onAllNodes(hasTestTag("button-icon") and hasAnyAncestor(hasTestTag("recap-script-run")), useUnmergedTree = true).assertCountEquals(2)
                onNode(hasText(My.breakSyllables(text(UiText.JOB_WAITING_GEMINI) + " · 0:12"))).assertExists()
                onNode(hasText(My.breakSyllables(text(UiText.JOB_RATE_WAIT, "seconds" to 23)))).assertExists()
                // 1: while the script is written, Write script / Write it myself are hidden.
                for (key in listOf(UiText.WRITE_SCRIPT, UiText.WRITE_MYSELF)) onAllNodes(hasText(text(key)) and inState(1)).assertCountEquals(0)
                // 3–5: the confirms (amber callouts), the replace hint only when a story exists; primary + Back with icons.
                val picked = base.recap!!.picked.size.takeIf { it > 0 } ?: StoryStates.SCENES.size
                onNode(hasText(text(UiText.SCRIPT_GENERATE_CONFIRM, "size" to picked)) and inState(3)).assertExists()
                onNode(hasText(text(UiText.SCRIPT_GENERATE_CONFIRM, "size" to picked) + text(UiText.SCRIPT_REPLACE_HINT)) and inState(4)).assertExists()
                onNode(hasText(text(UiText.SCRIPT_MANUAL_CONFIRM)) and inState(5)).assertExists()
                for ((n, key) in listOf(3 to UiText.WRITE, 4 to UiText.WRITE, 5 to UiText.START)) for (button in listOf(key, UiText.BACK))
                    onAllNodes(hasTestTag("button-icon") and hasAnyAncestor(hasClickAction() and hasAnyDescendant(hasText(text(button))) and inState(n)),
                        useUnmergedTree = true).assertCountEquals(1)
                // 3: the glass filter chips say their state.
                onNode(hasText(text(UiText.STYLE_PLAIN)) and inState(3)).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "selected"))
                onNode(hasText(text(UiText.STYLE_COMMENTARY)) and inState(3)).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "not selected"))
                // 6: the edited credit line is focused and offers the small Save credit (check).
                onNode(hasSetTextAction() and inState(6)).assertIsFocused().assert(hasText(StoryStates.CREDIT))
                onAllNodes(hasTestTag("button-icon") and hasAnyAncestor(hasClickAction() and hasAnyDescendant(hasText(text(UiText.SAVE_CREDIT))) and inState(6)),
                    useUnmergedTree = true).assertCountEquals(1)
                onAllNodes(hasText(text(UiText.SAVE_CREDIT))).assertCountEquals(1)
                // 7: no summary / subtitles: the Muted info row, Write script disabled, Write it myself still works.
                onNode(hasText(My.breakSyllables(Blocker.NO_SCRIPT_SOURCE.text(language))) and inState(7)).assertExists()
                onNode(hasClickAction() and hasText(text(UiText.WRITE_SCRIPT)) and inState(7)).assertIsNotEnabled()
                onNode(hasClickAction() and hasText(text(UiText.WRITE_MYSELF)) and inState(7)).assertIsEnabled()
                // 8: no scene picked: only the info row.
                onNode(hasText(My.breakSyllables(Blocker.NO_PICKED_SCENES.text(language))) and inState(8)).assertExists()
                for (key in listOf(UiText.WRITE_SCRIPT, UiText.WRITE_MYSELF, UiText.STYLE)) onAllNodes(hasText(text(key)) and inState(8)).assertCountEquals(0)
                assertTrue(onAllNodes(hasText("⏳", substring = true)).fetchSemanticsNodes().isEmpty())
                assertEquals(emptyList(), textFitProblems(360, font), "${language.code} $font")
                assertEquals(before, h.bytes(), "looking must not write the project")
            }
        }
    }

    /**
     * §2.1: no story (12 picked scenes) = Blocks' duotone box says "Write the story first." and no gate line repeats it ("Write the script
     * first"); Approve stays disabled. With blocks, a blank block's gate line still shows as a Muted info row.
     */
    @Test fun gateLineSkipsOnlyTheRepeatedWriteFirst() {
        for ((language, font) in listOf(UiLanguage.ENGLISH to 1f, UiLanguage.BURMESE to 2f)) runDesktopComposeUiTest(width = 360, height = 760) {
            ExportHarness().use { h ->
                val project = (h.store.open(h.fixture.dir.path) as CoreResult.Ok).value
                val saved = (h.store.save(h.fixture.dir.path, project.copy(recap = project.recap!!.copy(story = null, storyApproved = false,
                    scenes = StoryStates.SCENES)))
                    as CoreResult.Ok).value
                h.current.set(OpenProject(h.fixture.dir.path, saved)); h.recap.refresh()
                val before = h.bytes()
                setContent { CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 360f, editor = true) {
                        StudioRecapScreen(h.recap, RecapStep.STORY, 360f, {}, RecapNavigation({}, {}, {}))
                    }
                } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("story-approve").fetchSemanticsNodes().isNotEmpty() }
                onNode(hasText(My.breakSyllables(UiText.WRITE_STORY_FIRST.text(language)))).performScrollTo().assertIsDisplayed()
                for (text in listOf(Blocker.NO_SCRIPT.text(language), My.breakSyllables(Blocker.NO_SCRIPT.text(language))))
                    onAllNodes(hasText(text)).assertCountEquals(0)
                onNodeWithTag("story-approve").assertIsNotEnabled()
                // The Writing options card comes first and is open (no story yet), Write script with its sparkle.
                onAllNodes(hasTestTag("button-icon") and hasAnyAncestor(hasClickAction() and hasAnyDescendant(hasText(UiText.WRITE_SCRIPT.text(language)))),
                    useUnmergedTree = true).assertCountEquals(1)
                assertEquals(emptyList(), textFitProblems(360, font), "${language.code} $font")
                assertEquals(before, h.bytes(), "looking must not write the project")
            }
        }
        runDesktopComposeUiTest(width = 360, height = 760) {
            ExportHarness().use { h ->
                h.recap.refresh()
                assertTrue(h.recap.saveSegment(0, ""))
                setContent { CompositionLocalProvider(LocalCompact provides true, LocalUiFontFamily provides BurmeseTestFont.family) {
                    StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 360f, editor = true) {
                        StudioRecapScreen(h.recap, RecapStep.STORY, 360f, {}, RecapNavigation({}, {}, {}))
                    }
                } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("story-approve").fetchSemanticsNodes().isNotEmpty() }
                onNode(hasText(Blocker.BLANK_SEGMENT.text())).assertExists()
                onNodeWithTag("story-approve").assertIsNotEnabled()
            }
        }
    }
}
