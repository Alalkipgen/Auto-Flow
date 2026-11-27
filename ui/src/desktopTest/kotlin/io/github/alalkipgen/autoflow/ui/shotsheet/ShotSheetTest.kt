package io.github.alalkipgen.autoflow.ui.shotsheet

import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.onAllNodesWithTag
import io.github.alalkipgen.autoflow.ui.shots.pillLabel
import androidx.compose.ui.test.click
import kotlin.test.assertContentEquals
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.recap.assertProjectPanesAndStickyActions
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardGenerator
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.HandoffState
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Pass
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.handoff.CREDITS_QUESTION
import io.github.alalkipgen.autoflow.ui.handoff.FlowHandoffModel
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.references.ReferencesModel
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.shots.ShotsModel
import io.github.alalkipgen.autoflow.ui.shots.USE_VERSION_BUTTON
import io.github.alalkipgen.autoflow.ui.shots.PREVIEW_VERSION_BUTTON
import io.github.alalkipgen.autoflow.ui.storyboard.NO_STORYBOARD_TEXT
import io.github.alalkipgen.autoflow.ui.storyboard.JobState
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardModel
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardService
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 5B-12 Music Video shot sheet: one sheet per shot does the work of the Storyboard / Shots / References / Flow
 * Handoff tabs through their own models — every step of one shot ends up in the project file, the other shots stay
 * untouched, a project switch shows the other project's shots, and the layout holds at font scale 1.0 / 1.3 / 2.0.
 */
@OptIn(ExperimentalTestApi::class)
class ShotSheetTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val service = StoryboardService(session, StoryboardGenerator(FakeTextProvider()))
    private val jobs = StoryboardJobs(service, CoroutineScope(Dispatchers.Unconfined))
    private val storyboard = StoryboardModel(session, service, jobs)
    private val shots = ShotsModel(session)
    private val picker = FakePicker()
    private val copied = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private val refs = ReferencesModel(session, { copied += it; PlatformResult.Ok(Unit) }, { opened += it; PlatformResult.Ok(Unit) }, picker)
    private val handoff = FlowHandoffModel(session, { copied += it; PlatformResult.Ok(Unit) }, { opened += it; PlatformResult.Ok(Unit) }, picker,
        nowMs = { 1_794_477_600_000L })

    private fun open(dir: String = "/p/Rain.autoflow", name: String = "Rain", durationMs: Long = 30_000, generate: Boolean = true) {
        val p = (store.create(dir, name, Mode.MUSIC_VIDEO, ProjectSettings(durationMs = durationMs, clipMs = 8_000)) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
        session.writeLyrics("မိုးတွေရွာနေတယ်")
        if (generate) runBlocking { service.generateAll() }
    }

    private val project get() = current.state.value!!.project

    private fun projectFiles(): Map<String, List<Byte>> {
        val result = linkedMapOf<String, List<Byte>>()
        fun visit(path: String) {
            val children = fs.list(path)
            if (children.isNotEmpty()) children.forEach { visit("$path/$it") }
            else runCatching { fs.read(path).toList() }.getOrNull()?.let { result[path] = it }
        }
        visit(current.state.value!!.dir)
        return result
    }
    private fun png(rgb: Int): ByteArray = ByteArrayOutputStream().also { out ->
        ImageIO.write(BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB).apply { for (x in 0 until 16) for (y in 0 until 9) setRGB(x, y, rgb) }, "png", out)
    }.toByteArray()

    private fun sheet(width: Float) = @androidx.compose.runtime.Composable {
        ShotSheetScreen(storyboard, jobs, shots, refs, handoff, readBytes = { runCatching { fs.read(it) }.getOrNull() },
            windowWidthDp = width, onGoToInput = {})
    }

    /** 🆕-15: the step cards `sheet-step-n` and the navigator dots `shot-sheet-dot-n` show exactly the [done] steps. */
    private fun androidx.compose.ui.test.ComposeUiTest.assertSteps(vararg done: Int) {
        waitForIdle()
        for (n in 1..5) {
            val want = n in done
            onNodeWithTag("sheet-step-$n").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(StepDone, want))
            onNodeWithTag("shot-sheet-dot-$n").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(StepDone, want))
        }
    }

    @Test
    fun oneShotGoesFromStoryboardToAcceptedVideoOnItsSheet() = runDesktopComposeUiTest(width = 1100, height = 1400) {
        open()
        fs.createDirectories("/in")
        fs.writeDurably("/in/a.png", png(0xFF0000)); fs.writeDurably("/in/b.png", png(0x0000FF))
        fs.writeDurably("/in/v1.mp4", byteArrayOf(1, 2, 3)); fs.writeDurably("/in/v2.mp4", byteArrayOf(4, 5, 6))
        setContent { androidx.compose.runtime.CompositionLocalProvider(io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage provides io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.BURMESE) { sheet(1100f)() } }
        onNodeWithTag("project-three-panes").assertExists()
        onNodeWithTag("shot-sheet-title").assertTextEquals("Shot 1 · 0:00–0:08")
        onNodeWithTag("shot-sheet-select-2").performClick()
        waitForIdle()
        onNodeWithTag("shot-sheet-title").assertTextEquals("Shot 2 · 0:08–0:16")
        onNodeWithTag("shot-sheet-position").assertTextEquals("2 / 4")
        assertSteps() // 🆕-15: nothing done yet
        // One Copy prompt (sticky, existing handoff gate): disabled until the prompt passes the gate.
        onNodeWithTag("shot-sheet-copy-prompt").assertIsDisplayed().assertIsNotEnabled()
        onAllNodesWithText(io.github.alalkipgen.autoflow.ui.i18n.My.COPY_PROMPT).assertCountEquals(1)

        // 1 · Storyboard: approve this shot only.
        onNodeWithTag("sheet-story-approve").assertTextContains(io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(io.github.alalkipgen.autoflow.ui.i18n.My.APPROVE)).performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf(false, true, false, false), project.shots.map { it.approvals.storyboard })
        assertSteps(1)

        onNodeWithTag("sheet-ref-locked").performScrollTo().assertIsDisplayed()
        textIn("sheet-ref-locked", io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(UiText.BLOCKER_NO_PROMPT.text(UiLanguage.BURMESE))).assertExists()
        // 2 · Prompt: compile, approve.
        onNodeWithTag("sheet-prompt-compile").assertTextContains(io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables("Prompt စုစည်းမယ်")).performScrollTo().performClick()
        waitForIdle()
        assertNotNull(project.shots[1].promptIr)
        onNodeWithTag("sheet-prompt-approve").assertTextContains(io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(UiText.V3_APPROVE_PROMPT.text(UiLanguage.BURMESE))).performScrollTo().performClick()
        waitForIdle()
        assertTrue(project.shots[1].approvals.prompt)
        assertSteps(1, 2)

        // 3 · Ref A / B: copy the image prompt, import two images, pick B, approve it.
        onNodeWithTag("ref-copy-prompt").assertTextContains(io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(UiText.COPY_IMAGE_PROMPT.text(UiLanguage.BURMESE)))
            .performScrollTo().performClick()
        assertTrue(copied.last().isNotBlank())
        picker.files = listOf("/in/a.png")
        onNodeWithTag("ref-import").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { project.shots[1].refImages.size == 1 }
        picker.files = listOf("/in/b.png")
        onNodeWithTag("ref-import").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { project.shots[1].refImages.size == 2 }
        val (refA, refB) = project.shots[1].refImages
        assertEquals(refA, project.shots[1].chosenRef, "the first import is chosen until the user picks")
        onNodeWithTag("ref-pick-A").assertTextContains("Ref A").assertIsSelected()
        onNodeWithTag("ref-pick-B").assertIsNotSelected()
        onNodeWithTag("ref-pick-B").performScrollTo().performClick()
        waitForIdle()
        assertEquals(refB, project.shots[1].chosenRef)
        onNodeWithTag("ref-pick-B").assertIsSelected()
        onNodeWithTag("ref-approve").assertTextContains(io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(UiText.V3_REF_APPROVE_NAMED.text(UiLanguage.BURMESE, "ref" to "Ref B")))
            .performScrollTo().performClick()
        waitForIdle()
        assertTrue(project.shots[1].approvals.ref)
        textIn("ref-approved", io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(UiText.REF_APPROVED.text(UiLanguage.BURMESE))).assertExists()
        // The same toggle as before: Undo approval (tag kept) clears it; approve again.
        onNodeWithTag("ref-approve").assertTextContains(io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(UiText.UNDO_APPROVAL.text(UiLanguage.BURMESE))).performScrollTo().performClick()
        waitForIdle()
        assertFalse(project.shots[1].approvals.ref)
        onNodeWithTag("ref-approved").assertDoesNotExist()
        onNodeWithTag("ref-approve").performScrollTo().performClick()
        waitForIdle()
        assertTrue(project.shots[1].approvals.ref)
        assertSteps(1, 2, 3)

        // 4 · Flow handoff: Draft (Fast) / Final (Quality) segments = the same setPass; In batch sheet = the same tick.
        onNodeWithTag("sheet-flow-prompt").assertExists()
        onNodeWithTag("sheet-flow-pass-DRAFT").assertIsSelected()
        onNodeWithTag("sheet-flow-pass-FINAL").performScrollTo().performClick()
        waitForIdle()
        assertEquals(Pass.FINAL, project.shots[1].handoff.pass)
        onNodeWithTag("sheet-flow-pass-FINAL").assertIsSelected()
        onNodeWithTag("sheet-flow-pass-DRAFT").performScrollTo().performClick()
        waitForIdle()
        assertEquals(Pass.DRAFT, project.shots[1].handoff.pass)
        assertTrue(handoff.current().rows[1].inBatch)
        onNodeWithTag("sheet-flow-batch").performScrollTo().performClick()
        waitForIdle()
        assertFalse(handoff.current().rows[1].inBatch)
        onNodeWithTag("sheet-flow-batch").performScrollTo().performClick()
        waitForIdle()
        assertTrue(handoff.current().rows[1].inBatch)
        // Copy the Veo prompt, open Flow, import two videos.
        onNodeWithTag("shot-sheet-copy-prompt").assertIsDisplayed().assertIsEnabled()
            .assertTextContains(io.github.alalkipgen.autoflow.ui.i18n.My.COPY_PROMPT).performMouseInput { click() }
        waitForIdle()
        assertEquals(HandoffState.SENT_TO_FLOW, project.shots[1].handoff.state)
        assertTrue(copied.last().length > 40, "the Veo / Flow prompt: ${copied.last()}")
        onAllNodesWithText("Flow ဖွင့်မယ်").assertCountEquals(1)
        onNodeWithTag("shot-sheet-open-flow").assertIsDisplayed().assertTextContains("Flow ဖွင့်မယ်").performMouseInput { click() }
        assertEquals("https://labs.google/fx/tools/flow", opened.last())
        picker.files = listOf("/in/v1.mp4")
        onNodeWithTag("sheet-flow-import").assertTextContains(io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables("ဗွီဒီယို ထည့်မယ်")).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { project.shots[1].videos.size == 1 }
        onNodeWithTag("sheet-credit-question").performScrollTo().assertExists()
        textIn("sheet-credit-question", io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(CREDITS_QUESTION)).assertExists()
        picker.files = listOf("/in/v2.mp4")
        onNodeWithTag("sheet-flow-import").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { project.shots[1].videos.size == 2 }
        assertSteps(1, 2, 3, 4)
        onNodeWithTag("sheet-flow-state-IMPORTED").assertExists()
        // The credit question: "20 · Fast" (the Draft pass's suggestion) is the same logCredits; Skip the same skipCredits.
        onNodeWithTag("credit-fast").assertTextContains("20 · Fast")
        onNodeWithTag("credit-quality").assertTextContains("100 · Quality")
        onNodeWithTag("credit-fast").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf(20), project.flowCredits.manualLog.filter { it.shot == 2 }.map { it.credits })
        onNodeWithTag("credit-skip").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("sheet-credit-question").assertDoesNotExist()
        onNodeWithTag("sheet-flow-credits-logged").assertExists()
        assertEquals(listOf(20), project.flowCredits.manualLog.filter { it.shot == 2 }.map { it.credits }, "Skip logs nothing")

        // 5 · Videos (Group 3 PR 8): two versions, newest first; the newest is previewed (no Preview, Use = primary).
        val my = io.github.alalkipgen.autoflow.ui.i18n.My
        onNodeWithTag("sheet-videos-empty").assertDoesNotExist()
        onNodeWithTag("sheet-videos-title").performScrollTo()
            .assertTextEquals(my.breakSyllables(io.github.alalkipgen.autoflow.ui.shots.versionsTitle(2, UiLanguage.BURMESE)))
        onNodeWithTag("sheet-video-player").assertExists()
        assertTrue(onNodeWithTag("sheet-version-2").fetchSemanticsNode().positionInRoot.y < onNodeWithTag("sheet-version-1").fetchSemanticsNode().positionInRoot.y, "newest first")
        onNodeWithTag("sheet-version-preview-2").assertDoesNotExist()
        onNodeWithTag("sheet-version-use-2").assertTextContains(my.breakSyllables(USE_VERSION_BUTTON))
        textIn("sheet-version-pass-1", my.breakSyllables(UiText.PASS_DRAFT.text(UiLanguage.BURMESE))).assertExists()
        onNodeWithTag("sheet-draft-hint").assertDoesNotExist()
        // Preview v1 only moves the player (saves nothing); then use it.
        onNodeWithTag("sheet-version-preview-1").assertTextContains(my.breakSyllables(PREVIEW_VERSION_BUTTON)).performScrollTo().performClick()
        waitForIdle()
        assertTrue(project.shots[1].acceptedVideo == null, "preview accepts nothing")
        onNodeWithTag("sheet-version-preview-1").assertDoesNotExist()
        onNodeWithTag("sheet-version-preview-2").assertExists()
        onNodeWithTag("sheet-version-use-1").performScrollTo().performClick()
        waitForIdle()
        val s2 = project.shots[1]
        assertEquals(s2.videos[0], s2.acceptedVideo)
        assertEquals(HandoffState.ACCEPTED, s2.handoff.state)
        assertTrue(s2.approvals.video)
        assertSteps(1, 2, 3, 4, 5)
        onNodeWithTag("sheet-version-accepted-1").assertExists()
        onNodeWithTag("sheet-version-accepted-2").assertDoesNotExist()
        onNodeWithTag("sheet-version-use-1").assertDoesNotExist()
        onNodeWithTag("sheet-version-use-2").assertExists()
        // The accepted video is a Draft: the amber lightning callout.
        onNodeWithTag("sheet-draft-hint").performScrollTo()
        textIn("sheet-draft-hint", my.breakSyllables(UiText.DRAFT_ACCEPTED_HINT.text(UiLanguage.BURMESE))).assertExists()
        // Group 3 E: ≥ 840 dp = list · sheet · media pane (the sheet in the middle); the pane shows this shot's chosen ref and
        // accepted video at its width (a tap = the Flow tiles' open / play), the keyboard hint under it.
        val sheetPane = onNodeWithTag("project-editor").fetchSemanticsNode().boundsInRoot
        val mediaPane = onNodeWithTag("project-preview").fetchSemanticsNode().boundsInRoot
        assertTrue(onNodeWithTag("project-list").fetchSemanticsNode().boundsInRoot.right <= sheetPane.left && sheetPane.right <= mediaPane.left)
        assertTrue(sheetPane.width > mediaPane.width, "the sheet has the wider share: $sheetPane / $mediaPane")
        onNodeWithTag("sheet-media-title").assertTextEquals(my.breakSyllables(UiText.SHEET_MEDIA_SHOT.text(UiLanguage.BURMESE, "number" to 2)))
        textIn("sheet-media-ref", my.breakSyllables(UiText.REF_IMAGE.text(UiLanguage.BURMESE))).assertExists()
        textIn("sheet-media-video", my.breakSyllables(UiText.VIDEO_ACCEPTED.text(UiLanguage.BURMESE))).assertExists()
        onNodeWithTag("sheet-media-ref-frame").assertHasClickAction()
            .assertContentDescriptionEquals(UiText.REFERENCE_SHOT_DESCRIPTION.text(UiLanguage.BURMESE, "number" to 2))
        onNodeWithTag("sheet-media-video-frame").assertHasClickAction()
            .assertContentDescriptionEquals(UiText.VIDEO_SHOT_DESCRIPTION.text(UiLanguage.BURMESE, "number" to 2))
        onNodeWithTag("shortcuts-hint").assertTextEquals(my.breakSyllables(UiText.V3_MV_SHORTCUTS_HINT.text(UiLanguage.BURMESE)))

        // Saved in the project file; the other shots were not touched.
        val saved = (store.open("/p/Rain.autoflow") as CoreResult.Ok).value
        assertEquals(project.shots, saved.shots)
        for (i in listOf(0, 2, 3)) {
            val s = saved.shots[i]
            assertTrue(s.promptIr == null && s.refImages.isEmpty() && s.videos.isEmpty() && !s.approvals.storyboard, "shot ${s.number}: $s")
        }

        // Filters: real badge counts; browsing them never writes the project.
        val savedBytes = fs.read("/p/Rain.autoflow/project.json")
        val mm = UiLanguage.BURMESE
        onNodeWithTag("shot-sheet-summary").assertTextEquals(
            io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(UiText.V3_MV_SUMMARY.text(mm, "n" to 4, "accepted" to 1)))
        onNodeWithTag("shot-filter-ACCEPTED").performScrollTo().assertTextEquals(
            io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(UiText.V3_MV_ACCEPTED_COUNT.text(mm, "n" to 1))).performMouseInput { click() }
        waitForIdle()
        onNodeWithTag("shot-sheet-select-2").assertExists()
        for (n in listOf(1, 3, 4)) onNodeWithTag("shot-sheet-select-$n").assertDoesNotExist()
        onNodeWithTag("shot-filter-OPEN").performScrollTo().assertTextEquals(
            io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(UiText.V3_MV_OPEN_COUNT.text(mm, "n" to 3))).performMouseInput { click() }
        waitForIdle()
        onNodeWithTag("shot-sheet-select-2").assertDoesNotExist()
        for (n in listOf(1, 3, 4)) onNodeWithTag("shot-sheet-select-$n").assertExists()
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 2 · 0:08", substring = true) // the open shot stays open while filtered out
        onNodeWithTag("shot-filter-ALL").performScrollTo().performMouseInput { click() }
        waitForIdle()
        for (n in 1..4) onNodeWithTag("shot-sheet-select-$n").assertExists()
        assertContentEquals(savedBytes, fs.read("/p/Rain.autoflow/project.json"))

        // Previous / next.
        onNodeWithTag("shot-sheet-next").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 3 · 0:16", substring = true)
        onNodeWithTag("shot-sheet-prev").performScrollTo().performClick()
        onNodeWithTag("shot-sheet-prev").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 1 · 0:00", substring = true)
    }

    @Test
    fun hintsNameTheSheetStepNotTheOldTab() {
        assertEquals("Compile the shot prompt first (step 2)", forSheet("Compile the shot prompt first (Shots screen)"))
        assertEquals("Compile the shot prompt first (အဆင့် 2)", forSheet("Compile the shot prompt first (Shots screen)", UiLanguage.BURMESE))
        assertEquals("Import a reference image first (အဆင့် 3)", forSheet("Import a reference image first (Ref A/B on the References screen)", UiLanguage.BURMESE))
        assertEquals("No compiled prompt for shot 2 — အဆင့် 2 မှာ အရင် စုစည်းပါ",
            forSheet("No compiled prompt for shot 2 — compile it on the Shots screen first", UiLanguage.BURMESE))
        assertEquals("Approve the reference image first", forSheet("Approve the reference image first"))
        assertEquals("Owner စာသား {n}", forSheet("Owner စာသား {n}", UiLanguage.BURMESE))
    }

    @Test
    fun regenerateAllAsksFirstInTheGlassDialog() = runDesktopComposeUiTest(width = 1100, height = 1400) {
        open()
        val versions = storyboard.current().versions.size
        val path = "/p/Rain.autoflow/project.json"
        val bytes = fs.read(path)
        setContent { sheet(1100f)() }
        val regenerate = UiText.REGENERATE_ALL.text(UiLanguage.ENGLISH)
        onNodeWithTag("all-shots-storyboard").performScrollTo().assertIsDisplayed() // the wide list opens All shots
        onNodeWithText(regenerate).performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("confirm-regenerate-all").assertExists()
        onNodeWithTag("confirm-regenerate-all-back").performClick()
        waitForIdle()
        onNodeWithTag("confirm-regenerate-all").assertDoesNotExist()
        assertEquals(versions, storyboard.current().versions.size, "Back makes no new storyboard version")
        assertEquals(JobState(), jobs.state.value, "Back starts no Gemini run")
        assertContentEquals(bytes, fs.read(path), "Back never writes the project")
        onNodeWithText(regenerate).performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("confirm-regenerate-all-ok").performClick()
        // The same regenerateAll() as before: one Gemini storyboard run on the jobs (the fake gives the same storyboard back).
        waitUntil(timeoutMillis = 10_000) { jobs.state.value.running == null && jobs.state.value.last != null }
        onNodeWithTag("confirm-regenerate-all").assertDoesNotExist()
    }

    /**
     * Group 3 D inner 1–2 (PR 6): the Storyboard and Prompt cards on the sheet — Approve / Undo approval, Compile, Approve
     * prompt / Undo approval; New take and Recompile ask first in glass dialogs (🆕-5) whose Back / Cancel change nothing.
     */
    @Test
    fun storyboardAndPromptCardsAskFirstInGlassDialogs() = runDesktopComposeUiTest(width = 1100, height = 1400) {
        open()
        val path = "/p/Rain.autoflow/project.json"
        setContent { sheet(1100f)() }
        onNodeWithTag("shot-sheet-select-2").performClick()
        waitForIdle()
        // Before the storyboard is approved the prompt is behind the workflow gate: an amber lock, no compile button.
        onNodeWithTag("sheet-prompt-locked").performScrollTo().assertIsDisplayed()
        onNodeWithTag("sheet-prompt-compile").assertDoesNotExist()
        onNodeWithTag("sheet-story-approve").assertTextContains("Approve").performScrollTo().performClick()
        waitForIdle()
        assertTrue(project.shots[1].approvals.storyboard)
        onNodeWithTag("sheet-story-approved").assertExists()
        onNodeWithTag("sheet-story-unapprove").assertTextContains("Undo approval").performScrollTo().performClick()
        waitForIdle()
        assertFalse(project.shots[1].approvals.storyboard, "Undo approval is the same approve(false) as before")
        onNodeWithTag("sheet-story-approve").performScrollTo().performClick()
        waitForIdle()

        // New take: Back changes nothing; Regenerate is the same regenerate() as before (one Gemini run on the jobs).
        val spec = project.shots[1].spec
        onNodeWithTag("sheet-story-regenerate").assertTextContains("Regenerate").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("confirm-new-take").assertExists()
        onNodeWithText("New take for shot 2?").assertExists()
        onNodeWithTag("new-take-keep-scene").assertExists()
        var bytes = fs.read(path)
        onNodeWithTag("confirm-new-take-back").performClick()
        waitForIdle()
        onNodeWithTag("confirm-new-take").assertDoesNotExist()
        assertEquals(JobState(), jobs.state.value, "Back starts no Gemini run")
        assertContentEquals(bytes, fs.read(path), "Back never writes the project")
        assertEquals(spec, project.shots[1].spec)

        // Prompt: compile, approve, undo, approve; Recompile asks first.
        onNodeWithTag("sheet-prompt-compile").assertTextContains("Compile prompt").performScrollTo().performClick()
        waitForIdle()
        val prompt = assertNotNull(project.shots[1].promptIr)
        onNodeWithTag("sheet-prompt-text").assertExists()
        onNodeWithTag("sheet-prompt-approve").assertTextContains("Approve prompt").performScrollTo().performClick()
        waitForIdle()
        assertTrue(project.shots[1].approvals.prompt)
        onNodeWithTag("sheet-prompt-approved").assertExists()
        onNodeWithTag("sheet-prompt-unapprove").performScrollTo().performClick()
        waitForIdle()
        assertFalse(project.shots[1].approvals.prompt, "Undo approval is the same approvePrompt(false) as the old Approved button")
        onNodeWithTag("sheet-prompt-approve").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("sheet-prompt-recompile").assertTextContains("Recompile").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("confirm-recompile").assertExists()
        onNodeWithText("Recompile this prompt?").assertExists()
        bytes = fs.read(path)
        onNodeWithTag("confirm-recompile-cancel").performClick()
        waitForIdle()
        onNodeWithTag("confirm-recompile").assertDoesNotExist()
        assertContentEquals(bytes, fs.read(path), "Cancel never writes the project")
        assertEquals(prompt, project.shots[1].promptIr)
        onNodeWithTag("sheet-prompt-recompile").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("confirm-recompile-ok").performClick()
        waitForIdle()
        onNodeWithTag("confirm-recompile").assertDoesNotExist()
        assertNotNull(project.shots[1].promptIr)

        onNodeWithTag("sheet-story-regenerate").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("confirm-new-take-ok").performClick()
        waitUntil(timeoutMillis = 10_000) { jobs.state.value.running == null && jobs.state.value.last != null }
        onNodeWithTag("confirm-new-take").assertDoesNotExist()
        assertFalse(project.shots[1].approvals.storyboard, "a new take needs approval again")
    }

    @Test
    fun everyRowShowsItsStatusPillInWords() = runDesktopComposeUiTest(width = 1100, height = 1400) {
        open()
        setContent { androidx.compose.runtime.CompositionLocalProvider(io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage provides UiLanguage.BURMESE) { sheet(1100f)() } }
        fun pills(within: String) = io.github.alalkipgen.autoflow.core.workflow.ShotStatus.entries.sumOf {
            onAllNodes(androidx.compose.ui.test.hasTestTag("shot-status-${it.name}") and androidx.compose.ui.test.hasAnyAncestor(
                androidx.compose.ui.test.hasTestTag(within)), useUnmergedTree = true).fetchSemanticsNodes().size
        }
        for (n in 1..4) assertEquals(1, pills("shot-sheet-select-$n"), "one status pill on shot row $n")
        assertEquals(1, pills("shot-sheet-navigator"), "the open shot's navigator shows the same pill (Group 3 D 2)")
        for (n in 1..4) onNodeWithTag("shot-number-$n", useUnmergedTree = true).assertExists()
        // Prompt ready is the new key in both languages (the classic badge said English-only "✓ Prompt"); no symbols in the words.
        assertEquals("Prompt ready", io.github.alalkipgen.autoflow.core.workflow.ShotStatus.PROMPT_READY.pillLabel())
        assertEquals("Prompt အသင့်", io.github.alalkipgen.autoflow.core.workflow.ShotStatus.PROMPT_READY.pillLabel(UiLanguage.BURMESE))
        for (status in io.github.alalkipgen.autoflow.core.workflow.ShotStatus.entries) for (language in UiLanguage.entries) {
            val label = status.pillLabel(language)
            assertTrue(label.none { it in "✓⚠●○↗⤓↻✕" }, "$status $language: $label")
        }
    }

    /** Group 3 §8-2 (sample 06): Snap cuts to beat says what it moved, under the switch, in the UI language. */
    @Test
    fun snapToBeatSummaryIsAGreenCalloutInTheUiLanguage() = runDesktopComposeUiTest(width = 412, height = 1600) {
        open()
        fs.createDirectories("/c")
        fs.writeDurably("/c/s.mp3", byteArrayOf(1, 2, 3))
        session.importSong("/c/s.mp3")
        session.writeBeats(io.github.alalkipgen.autoflow.core.workflow.SongBeats(session.songSha256()!!, 119.6, listOf(7_800, 16_100, 24_400)))
        setContent { CompositionLocalProvider(io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage provides UiLanguage.BURMESE) { sheet(412f)() } }
        onNodeWithTag("shot-sheet-all").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("snap-to-beat").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { storyboard.snapped.value != null }
        waitForIdle()
        val summary = assertNotNull(storyboard.snapped.value)
        assertEquals(3, summary.total)
        onNode(androidx.compose.ui.test.hasText(io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(summary.text(UiLanguage.BURMESE)))
            and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("snap-done"))).performScrollTo().assertIsDisplayed()
        onNodeWithText("Cuts snapped", substring = true).assertDoesNotExist()
    }

    @Test
    fun withoutAStoryboardItPointsToInput() = runDesktopComposeUiTest(width = 412, height = 800) {
        open(generate = false)
        var went = 0
        setContent { androidx.compose.runtime.CompositionLocalProvider(io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage provides io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.BURMESE) { ShotSheetScreen(storyboard, jobs, shots, refs, handoff, { null }, 412f, onGoToInput = { went++ }) } }
        onNodeWithTag("shot-sheet-empty").assertExists()
        onNodeWithText(io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(UiText.STORYBOARD_EMPTY.text(UiLanguage.BURMESE))).assertExists()
        onNodeWithText("ထည့်သွင်းရန် သွားမယ်").performClick()
        assertEquals(1, went)
    }

    @Test
    fun anotherProjectShowsItsOwnShotsAndActionsStayThere() = runDesktopComposeUiTest(width = 412, height = 1200) {
        open()
        setContent { androidx.compose.runtime.CompositionLocalProvider(io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage provides io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.BURMESE) { sheet(412f)() } }
        onNodeWithTag("shot-sheet-select-4").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 4 · 0:24", substring = true)
        // Open a second, shorter project (2 shots): the narrow view returns to that project's own shot list.
        open("/p/Sun.autoflow", "Sun", durationMs = 16_000)
        waitForIdle()
        onNodeWithTag("shot-sheet-list").assertExists()
        onNodeWithTag("shot-sheet-select-3").assertDoesNotExist()
        onNodeWithTag("shot-sheet-select-1").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 1 · 0:00", substring = true)
        onNodeWithTag("sheet-story-approve").assertTextContains(io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(io.github.alalkipgen.autoflow.ui.i18n.My.APPROVE)).performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf(true, false), project.shots.map { it.approvals.storyboard })
        val rain = (store.open("/p/Rain.autoflow") as CoreResult.Ok).value
        assertFalse(rain.shots.any { it.approvals.storyboard }, "the first project is untouched")
    }

    @Test
    fun narrowBackClosesTheShotBeforeLeavingAndKeepsTheSelection() = runDesktopComposeUiTest(width = 412, height = 900) {
        open()
        val projectPath = current.state.value!!.dir + "/project.json"
        val originalBytes = fs.read(projectPath)
        val originalFiles = projectFiles()
        var back: (() -> Unit)? = null
        setContent {
            ShotSheetScreen(storyboard, jobs, shots, refs, handoff, readBytes = { null }, windowWidthDp = 412f, onGoToInput = {},
                handleBack = { enabled, onBack -> back = if (enabled) onBack else null })
        }
        onNodeWithTag("shot-sheet-list").assertExists()
        onNodeWithTag("sticky-action-bar").assertDoesNotExist()
        assertEquals(null, back, "the list leaves through the shell's own Back")
        onNodeWithTag("shot-sheet-select-3").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        waitForIdle()
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 3 · 0:16", substring = true)
        onNodeWithTag("shot-sheet-copy-prompt").assertIsDisplayed()
        val platformBack = assertNotNull(back, "an open shot registers Back")
        runOnIdle { platformBack() }
        waitForIdle()
        onNodeWithTag("shot-sheet-list").assertExists()
        onNodeWithTag("shot-sheet").assertDoesNotExist()
        assertEquals(null, back)
        assertEquals(3, shots.current().selected, "Back keeps the selected shot")
        // The visible back button follows the same rule.
        onNodeWithTag("shot-sheet-select-2").performScrollTo().performMouseInput { click() }
        waitForIdle()
        onNodeWithTag("shot-sheet-back").assertIsDisplayed().performMouseInput { click() }
        waitForIdle()
        onNodeWithTag("shot-sheet-list").assertExists()
        assertContentEquals(originalBytes, fs.read(projectPath), "browsing shots never saves")
        assertEquals(originalFiles, projectFiles(), "Back/browse leaves every project file byte-identical")
    }

    @Test fun keyboardArrowsRespectShotBoundariesAndBrowseNeverWrites() = runDesktopComposeUiTest(width = 840, height = 760) {
        open()
        val before = fs.read("/p/Rain.autoflow/project.json")
        setContent { sheet(840f)() }
        val host = onNodeWithTag("project-shortcuts").assertIsFocused()
        host.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionLeft) }
        assertEquals(1, shots.current().selected)
        repeat(6) { host.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) } }
        assertEquals(4, shots.current().selected)
        onNodeWithTag("shot-sheet-next").assertDoesNotExist()
        host.performKeyInput { keyDown(androidx.compose.ui.input.key.Key.CtrlLeft); pressKey(androidx.compose.ui.input.key.Key.DirectionLeft); keyUp(androidx.compose.ui.input.key.Key.CtrlLeft) }
        assertEquals(4, shots.current().selected)
        repeat(6) { host.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionLeft) } }
        assertEquals(1, shots.current().selected)
        onNodeWithTag("shot-sheet-prev").assertDoesNotExist()
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 1 · 0:00", substring = true)
        assertContentEquals(before, fs.read("/p/Rain.autoflow/project.json"))
    }

    @Test fun phone360MyFont200Fallback() = ShotSheetTest().layoutCase(360, 2f, UiLanguage.BURMESE, bundled = false)
    @Test fun desktop1440() { for (language in UiLanguage.entries) ShotSheetTest().layoutCase(1440, 1f, language, bundled = true, height = 900) }
    @Test fun phoneFont100() = layout(412, 1f)
    @Test fun phoneFont130() = layout(412, 1.3f)
    @Test fun phoneFont200() = layout(412, 2f)
    @Test fun wideFont100() = layout(840, 1f)
    @Test fun wideFont130() = layout(840, 1.3f)
    @Test fun wideFont200() = layout(840, 2f)

    private fun layout(width: Int, font: Float) {
        for (language in UiLanguage.entries) for (bundled in listOf(true, false)) {
            // Each matrix cell gets its own real models/filesystem; no cross-cell project reuse.
            ShotSheetTest().layoutCase(width, font, language, bundled)
        }
    }

    private fun layoutCase(width: Int, font: Float, language: UiLanguage, bundled: Boolean, height: Int = 760) = runDesktopComposeUiTest(width = width, height = height) {
        open()
        val projectPath = current.state.value!!.dir + "/project.json"
        val originalBytes = fs.read(projectPath)
        val originalFiles = projectFiles()
        setContent { CompositionLocalProvider(io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage provides language, LocalDensity provides Density(1f, font), io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily provides if (bundled) io.github.alalkipgen.autoflow.ui.BurmeseTestFont.family else FontFamily.Default) { Box { sheet(width.toFloat())() } } }
        onNodeWithTag(if (width >= 840) "project-three-panes" else "project-single-pane").assertExists()
        val shotsDir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "music-video").apply { mkdirs() }
        val cell = "$width-${language.code}-${"%.1f".format(java.util.Locale.ROOT, font)}-$bundled"
        for (tag in ShotFilter.entries.map { "shot-filter-${it.name}" } + "shot-sheet-select-1") {
            val node = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
            assertTrue(node.boundsInRoot.height >= 48f && node.boundsInRoot.right <= width.toFloat() + 0.5f, "$tag 48 dp and inside at font $font")
        }
        if (width < 840) {
            onNodeWithTag("sticky-action-bar").assertDoesNotExist()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shotsDir, "09-shotsheet-list-$cell.png"))
        }
        onNodeWithTag("shot-sheet-select-2").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        waitForIdle()
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 2 · 0:08", substring = true)
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shotsDir, "09-shotsheet-detail-$cell.png"))
        assertProjectPanesAndStickyActions(width, "shot-sheet-copy-prompt", "shot-sheet-open-flow", editorInMiddle = true)
        val bar = onNodeWithTag("sticky-action-bar").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        for (tag in listOf("shot-sheet-copy-prompt", "shot-sheet-open-flow")) {
            val node = onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            assertTrue(node.boundsInRoot.height >= 48f && node.boundsInRoot.right <= width.toFloat() + 0.5f, "$tag 48 dp and inside at font $font")
        }
        val tags = listOf("shot-sheet-prev", "shot-sheet-next") + if (width < 840) listOf("shot-sheet-back") else emptyList()
        for (tag in tags) {
            val node = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
            assertTrue(node.boundsInRoot.height >= 48f, "$tag must have a 48 dp touch target at font $font")
            assertTrue(node.boundsInRoot.left >= 0f && node.boundsInRoot.right <= width.toFloat(), "$tag inside the window at font $font")
            assertTrue(node.boundsInRoot.bottom <= bar.top + 0.5f, "$tag is not covered by the sticky bar at font $font")
        }
        val scene = storyboard.current().cards.first { it.number == 2 }.spec!!.sceneMy
        onNode(androidx.compose.ui.test.hasText(io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(scene)) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("sheet-step-1"))).performScrollTo().assertIsDisplayed()
        // The step cards' titles (no "N · " — the badge shows N; 🆕-15), each inside its own card `sheet-step-n`.
        for ((k, title) in listOf(UiText.SHEET_STORYBOARD, UiText.SHEET_PROMPT, UiText.SHEET_REFS, UiText.SHEET_FLOW, UiText.SHEET_VIDEOS)
            .map { io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(it.text(language)) }.withIndex()) {
            val node = onNode(androidx.compose.ui.test.hasText(title) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("sheet-step-${k + 1}")))
                .performScrollTo().assertIsDisplayed().fetchSemanticsNode()
            assertTrue(node.boundsInRoot.right <= width.toFloat() + 0.5f, "$title inside the window at font $font")
            assertTrue(node.boundsInRoot.bottom <= bar.top + 0.5f, "$title is not covered by the sticky bar at font $font")
        }
        if (width < 840) {
            onNodeWithTag("shot-sheet-back").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            waitForIdle()
            onNodeWithTag("shot-sheet-all").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithText(UiText.STORYBOARD_APPROVED_COUNT.text(language, "approvedCount" to 0, "size" to 4)).performScrollTo().assertIsDisplayed()
        } else {
            onNodeWithText(UiText.STORYBOARD_APPROVED_COUNT.text(language, "approvedCount" to 0, "size" to 4)).performScrollTo().assertIsDisplayed()
        }
        assertEquals(emptyList<String>(), textFitProblems(width, font))
        assertContentEquals(originalBytes, fs.read(projectPath), "UI language/layout/navigation never saves project content")
        assertEquals(originalFiles, projectFiles(), "opening and browsing never changes any artifact or project file")
        val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/studio-layout").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "shotsheet-$width-font${(font * 100).toInt()}-${language.code}-${if (bundled) "bundled" else "fallback"}.png"))
    }
}

/** The text node [text] inside the node tagged [tag] (callouts, pills and cards do not merge their text). */
@OptIn(ExperimentalTestApi::class)
internal fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.textIn(tag: String, text: String) =
    onNode(androidx.compose.ui.test.hasText(text, substring = true) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(tag)))
