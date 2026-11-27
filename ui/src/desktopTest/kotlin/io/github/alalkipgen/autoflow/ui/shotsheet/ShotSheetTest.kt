package io.github.alalkipgen.autoflow.ui.shotsheet

import io.github.alalkipgen.autoflow.ui.textFitProblems
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
        setContent { sheet(1100f)() }
        onNodeWithTag("project-three-panes").assertExists()
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 1 · 0:00")
        onNodeWithTag("shot-sheet-select-2").performClick()
        waitForIdle()
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 2 · 0:08")

        // 1 · Storyboard: approve this shot only.
        onNodeWithText(APPROVE).performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf(false, true, false, false), project.shots.map { it.approvals.storyboard })

        onNodeWithText("⚠ Compile the shot prompt first (အဆင့် 2)").performScrollTo().assertExists()
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
        onNodeWithText("📋 ${io.github.alalkipgen.autoflow.ui.i18n.My.COPY_PROMPT}").performScrollTo().performClick()
        waitForIdle()
        assertEquals(HandoffState.SENT_TO_FLOW, project.shots[1].handoff.state)
        assertTrue(copied.last().length > 40, "the Veo / Flow prompt: ${copied.last()}")
        onNodeWithText("Flow ဖွင့်မယ် ↗").performScrollTo().performClick()
        assertEquals("https://labs.google/fx/tools/flow", opened.last())
        picker.files = listOf("/in/v1.mp4")
        onNodeWithText("📂 ဗွီဒီယို ထည့်မယ်").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { project.shots[1].videos.size == 1 }
        onNodeWithText(CREDITS_QUESTION).performScrollTo().assertExists()
        picker.files = listOf("/in/v2.mp4")
        onNodeWithText("📂 ဗွီဒီယို ထည့်မယ်").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { project.shots[1].videos.size == 2 }

        // 5 · Videos: two versions, newest first; use the older one.
        onNodeWithText(io.github.alalkipgen.autoflow.ui.shots.versionsTitle(2)).performScrollTo().assertExists()
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
        assertEquals("Compile the shot prompt first (အဆင့် 2)", forSheet("Compile the shot prompt first (Shots screen)"))
        assertEquals("Import a reference image first (အဆင့် 3)", forSheet("Import a reference image first (Ref A/B on the References screen)"))
        assertEquals("No compiled prompt for shot 2 — အဆင့် 2 မှာ အရင် စုစည်းပါ",
            forSheet("No compiled prompt for shot 2 — compile it on the Shots screen first"))
        assertEquals("Approve the reference image first", forSheet("Approve the reference image first"))
    }

    @Test
    fun withoutAStoryboardItPointsToInput() = runDesktopComposeUiTest(width = 412, height = 800) {
        open(generate = false)
        var went = 0
        setContent { ShotSheetScreen(storyboard, jobs, shots, refs, handoff, { null }, 412f, onGoToInput = { went++ }) }
        onNodeWithText(NO_STORYBOARD_TEXT).assertExists()
        onNodeWithText("ထည့်သွင်းရန် သွားမယ်").performClick()
        assertEquals(1, went)
    }

    @Test
    fun anotherProjectShowsItsOwnShotsAndActionsStayThere() = runDesktopComposeUiTest(width = 412, height = 1200) {
        open()
        setContent { sheet(412f)() }
        onNodeWithTag("shot-sheet-chip-4").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 4 · 0:24")
        // Open a second, shorter project (2 shots): shot 4 does not exist there → its first shot.
        open("/p/Sun.autoflow", "Sun", durationMs = 16_000)
        waitForIdle()
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 1 · 0:00")
        onNodeWithTag("shot-sheet-chip-3").assertDoesNotExist()
        onNodeWithText(APPROVE).performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf(true, false), project.shots.map { it.approvals.storyboard })
        val rain = (store.open("/p/Rain.autoflow") as CoreResult.Ok).value
        assertFalse(rain.shots.any { it.approvals.storyboard }, "the first project is untouched")
    }

    @Test fun phoneFont100() = layout(412, 1f)
    @Test fun phoneFont130() = layout(412, 1.3f)
    @Test fun phoneFont200() = layout(412, 2f)
    @Test fun wideFont100() = layout(840, 1f)
    @Test fun wideFont200() = layout(840, 2f)

    private fun layout(width: Int, font: Float) = runDesktopComposeUiTest(width = width, height = 1000) {
        open()
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f, font), io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily provides io.github.alalkipgen.autoflow.ui.BurmeseTestFont.family) { Box { sheet(width.toFloat())() } } }
        onNodeWithTag(if (width >= 840) "project-three-panes" else "project-single-pane").assertExists()
        if (width < 840) {
            onNodeWithTag("shot-sheet-chip-2").performScrollTo().performClick()
        } else {
            onNodeWithTag("shot-sheet-select-2").performScrollTo().performClick()
        }
        waitForIdle()
        onNodeWithTag("shot-sheet-title").assertTextContains("Shot 2 · 0:08")
        val tags = listOf("shot-sheet-prev", "shot-sheet-next") + if (width < 840) listOf("shot-sheet-chip-1", "shot-sheet-all") else emptyList()
        for (tag in tags) {
            val node = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
            assertTrue(node.boundsInRoot.height >= 48f, "$tag must have a 48 dp touch target at font $font")
            assertTrue(node.boundsInRoot.left >= 0f && node.boundsInRoot.right <= width.toFloat(), "$tag inside the window at font $font")
        }
        for (title in listOf(SHEET_STORYBOARD, SHEET_PROMPT, SHEET_REFS, SHEET_FLOW, SHEET_VIDEOS)) {
            val node = onNodeWithText(title).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
            assertTrue(node.boundsInRoot.right <= width.toFloat() + 0.5f, "$title inside the window at font $font")
        }
        if (width < 840) {
            onNodeWithTag("shot-sheet-all").performScrollTo().performClick()
            onNodeWithText("0/4 approved").performScrollTo().assertIsDisplayed()
        } else {
            onNodeWithText("0/4 approved").performScrollTo().assertIsDisplayed()
        }
        assertEquals(emptyList<String>(), textFitProblems(width, font))
        val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/studio-layout").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "shotsheet-$width-font${(font * 100).toInt()}.png"))
    }
}
