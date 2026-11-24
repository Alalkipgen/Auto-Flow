package io.github.alalkipgen.autoflow.ui.shotsheet

import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.test.assertIsFocused
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
import androidx.compose.ui.test.assertTextEquals
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
import io.github.alalkipgen.autoflow.ui.storyboard.APPROVE
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

    @Test
    fun oneShotGoesFromStoryboardToAcceptedVideoOnItsSheet() = runDesktopComposeUiTest(width = 1100, height = 1400) {
        open()
        fs.createDirectories("/in")
        fs.writeDurably("/in/a.png", png(0xFF0000)); fs.writeDurably("/in/b.png", png(0x0000FF))
        fs.writeDurably("/in/v1.mp4", byteArrayOf(1, 2, 3)); fs.writeDurably("/in/v2.mp4", byteArrayOf(4, 5, 6))
        setContent { androidx.compose.runtime.CompositionLocalProvider(io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage provides io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.BURMESE) { sheet(1100f)() } }
        onNodeWithTag("project-three-panes").assertExists()
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 1 · 0:00")
        onNodeWithTag("shot-sheet-select-2").performClick()
        waitForIdle()
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 2 · 0:08")
        onNodeWithTag("shot-sheet-position").assertTextEquals("2 / 4")
        // One Copy prompt (sticky, existing handoff gate): disabled until the prompt passes the gate.
        onNodeWithTag("shot-sheet-copy-prompt").assertIsDisplayed().assertIsNotEnabled()
        onAllNodesWithText("📋 ${io.github.alalkipgen.autoflow.ui.i18n.My.COPY_PROMPT}").assertCountEquals(1)

        // 1 · Storyboard: approve this shot only.
        onNodeWithText(APPROVE).performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf(false, true, false, false), project.shots.map { it.approvals.storyboard })

        onNodeWithText("⚠ ${UiText.BLOCKER_NO_PROMPT.text(UiLanguage.BURMESE)}").performScrollTo().assertIsDisplayed()
        // 2 · Prompt: compile, approve.
        onNodeWithText("Prompt စုစည်းမယ်").performScrollTo().performClick()
        waitForIdle()
        assertNotNull(project.shots[1].promptIr)
        onNodeWithText("✓ Prompt ${io.github.alalkipgen.autoflow.ui.i18n.My.APPROVE}").performScrollTo().performClick()
        waitForIdle()
        assertTrue(project.shots[1].approvals.prompt)

        // 3 · Ref A / B: copy the image prompt, import two images, pick B, approve it.
        onNodeWithText("📋 ပုံ ${io.github.alalkipgen.autoflow.ui.i18n.My.COPY_PROMPT}").performScrollTo().performClick()
        assertTrue(copied.last().isNotBlank())
        picker.files = listOf("/in/a.png")
        onNodeWithTag("ref-import").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { project.shots[1].refImages.size == 1 }
        picker.files = listOf("/in/b.png")
        onNodeWithTag("ref-import").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { project.shots[1].refImages.size == 2 }
        val (refA, refB) = project.shots[1].refImages
        assertEquals(refA, project.shots[1].chosenRef, "the first import is chosen until the user picks")
        onNodeWithTag("ref-pick-A").assertTextContains("Ref A ✓")
        onNodeWithTag("ref-pick-B").performScrollTo().performClick()
        waitForIdle()
        assertEquals(refB, project.shots[1].chosenRef)
        onNodeWithTag("ref-approve").assertTextContains("✓ Ref B ${io.github.alalkipgen.autoflow.ui.i18n.My.APPROVE}").performScrollTo().performClick()
        waitForIdle()
        assertTrue(project.shots[1].approvals.ref)
        onNodeWithTag("ref-approve").assertTextContains("✓ Reference ${io.github.alalkipgen.autoflow.ui.i18n.My.APPROVED}")

        // 4 · Flow handoff: copy the Veo prompt, open Flow, import two videos.
        onNodeWithTag("shot-sheet-copy-prompt").assertIsDisplayed().assertIsEnabled()
            .assertTextContains("📋 ${io.github.alalkipgen.autoflow.ui.i18n.My.COPY_PROMPT}").performMouseInput { click() }
        waitForIdle()
        assertEquals(HandoffState.SENT_TO_FLOW, project.shots[1].handoff.state)
        assertTrue(copied.last().length > 40, "the Veo / Flow prompt: ${copied.last()}")
        onAllNodesWithText("Flow ဖွင့်မယ် ↗").assertCountEquals(1)
        onNodeWithTag("shot-sheet-open-flow").assertIsDisplayed().assertTextContains("Flow ဖွင့်မယ် ↗").performMouseInput { click() }
        assertEquals("https://labs.google/fx/tools/flow", opened.last())
        picker.files = listOf("/in/v1.mp4")
        onNodeWithText("📂 ဗွီဒီယို ထည့်မယ်").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { project.shots[1].videos.size == 1 }
        onNodeWithText(CREDITS_QUESTION).performScrollTo().assertExists()
        picker.files = listOf("/in/v2.mp4")
        onNodeWithText("📂 ဗွီဒီယို ထည့်မယ်").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { project.shots[1].videos.size == 2 }

        // 5 · Videos: two versions, newest first; use the older one.
        onNodeWithText(io.github.alalkipgen.autoflow.ui.shots.versionsTitle(2, UiLanguage.BURMESE)).performScrollTo().assertExists()
        onAllNodesWithText(USE_VERSION_BUTTON)[1].performScrollTo().performClick()
        waitForIdle()
        val s2 = project.shots[1]
        assertEquals(s2.videos[0], s2.acceptedVideo)
        assertEquals(HandoffState.ACCEPTED, s2.handoff.state)
        assertTrue(s2.approvals.video)

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
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 2 · 0:08") // the open shot stays open while filtered out
        onNodeWithTag("shot-filter-ALL").performScrollTo().performMouseInput { click() }
        waitForIdle()
        for (n in 1..4) onNodeWithTag("shot-sheet-select-$n").assertExists()
        assertContentEquals(savedBytes, fs.read("/p/Rain.autoflow/project.json"))

        // Previous / next.
        onNodeWithTag("shot-sheet-next").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 3 · 0:16")
        onNodeWithTag("shot-sheet-prev").performScrollTo().performClick()
        onNodeWithTag("shot-sheet-prev").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 1 · 0:00")
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

    @Test
    fun everyRowShowsItsStatusPillInWords() = runDesktopComposeUiTest(width = 1100, height = 1400) {
        open()
        setContent { androidx.compose.runtime.CompositionLocalProvider(io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage provides UiLanguage.BURMESE) { sheet(1100f)() } }
        val pills = io.github.alalkipgen.autoflow.core.workflow.ShotStatus.entries.sumOf {
            onAllNodesWithTag("shot-status-${it.name}", useUnmergedTree = true).fetchSemanticsNodes().size
        }
        assertEquals(4, pills, "one status pill per shot row")
        for (n in 1..4) onNodeWithTag("shot-number-$n", useUnmergedTree = true).assertExists()
        // Prompt ready is the new key in both languages (the classic badge said English-only "✓ Prompt"); no symbols in the words.
        assertEquals("Prompt ready", io.github.alalkipgen.autoflow.core.workflow.ShotStatus.PROMPT_READY.pillLabel())
        assertEquals("Prompt အသင့်", io.github.alalkipgen.autoflow.core.workflow.ShotStatus.PROMPT_READY.pillLabel(UiLanguage.BURMESE))
        for (status in io.github.alalkipgen.autoflow.core.workflow.ShotStatus.entries) for (language in UiLanguage.entries) {
            val label = status.pillLabel(language)
            assertTrue(label.none { it in "✓⚠●○↗⤓↻✕" }, "$status $language: $label")
        }
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
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 4 · 0:24")
        // Open a second, shorter project (2 shots): the narrow view returns to that project's own shot list.
        open("/p/Sun.autoflow", "Sun", durationMs = 16_000)
        waitForIdle()
        onNodeWithTag("shot-sheet-list").assertExists()
        onNodeWithTag("shot-sheet-select-3").assertDoesNotExist()
        onNodeWithTag("shot-sheet-select-1").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 1 · 0:00")
        onNodeWithText(APPROVE).performScrollTo().performClick()
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
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 3 · 0:16")
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
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 1 · 0:00")
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
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 2 · 0:08")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shotsDir, "09-shotsheet-detail-$cell.png"))
        assertProjectPanesAndStickyActions(width, "shot-sheet-copy-prompt", "shot-sheet-open-flow")
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
        onNodeWithText(scene).performScrollTo().assertIsDisplayed()
        for (title in listOf(UiText.SHEET_STORYBOARD, UiText.SHEET_PROMPT, UiText.SHEET_REFS, UiText.SHEET_FLOW, UiText.SHEET_VIDEOS).map { it.text(language) }) {
            val node = onNodeWithText(title).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
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
