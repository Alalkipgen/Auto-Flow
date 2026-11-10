package io.github.alalkipgen.autoflow.ui.storyboard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardGenerator
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class StoryboardScreenTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val service = StoryboardService(session, StoryboardGenerator(FakeTextProvider()))

    /** Unconfined: the fake AI never suspends, so a job finishes inside the click. */
    private val jobs = StoryboardJobs(service, CoroutineScope(Dispatchers.Unconfined))
    private val model = StoryboardModel(session, service, jobs)

    private fun open(generate: Boolean = true) {
        val dir = "/p/Rain.autoflow"
        val p = (store.create(dir, "Rain", Mode.MUSIC_VIDEO, ProjectSettings(durationMs = 30_000, clipMs = 8_000)) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
        session.writeLyrics("မိုးတွေရွာနေတယ်")
        if (generate) runBlocking { service.generateAll() }
    }

    private val shots get() = current.state.value!!.project.shots

    @Test
    fun withoutAStoryboardItPointsToInput() = runComposeUiTest {
        open(generate = false)
        var went = 0
        setContent { StoryboardScreen(model, jobs, onGoToInput = { went++ }) }
        onNodeWithText(NO_STORYBOARD_TEXT).assertExists()
        onNodeWithText("Go to Input").performClick()
        assertEquals(1, went)
    }

    @Test
    fun approveEditAndApproveAll() = runComposeUiTest {
        open()
        setContent { StoryboardScreen(model, jobs, onGoToInput = {}) }
        onNodeWithText("0/4 approved").assertExists()
        onNodeWithText("0:24–0:30").assertExists()
        onAllNodesWithText(APPROVE)[0].performClick()
        waitForIdle()
        onNodeWithText("1/4 approved").assertExists()
        assertTrue(shots[0].approvals.storyboard)

        onAllNodesWithText(EDIT)[1].performScrollTo().performClick()
        waitForIdle()
        onNodeWithContentDescription("Burmese scene").performTextClearance()
        onNodeWithContentDescription("Burmese scene").performTextInput("ကော်ဖီဆိုင်ထဲမှာ")
        onNodeWithText("Save").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("ကော်ဖီဆိုင်ထဲမှာ").assertExists()
        onNodeWithText("v1 · edited").assertExists()

        onNodeWithText("Approve all").performClick()
        waitForIdle()
        onNodeWithText("4/4 approved").assertExists()
        onNodeWithText("Approve all").assertIsNotEnabled()
        onAllNodesWithText("Undo approval")[3].performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("3/4 approved").assertExists()
    }

    @Test
    fun regenerateAShotAndAllBehindConfirms() = runComposeUiTest {
        open()
        model.approveAll()
        setContent { StoryboardScreen(model, jobs, onGoToInput = {}) }
        val before = shots[1].spec
        onAllNodesWithText(REGENERATE)[1].performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Uses 1 AI request", substring = true).assertExists()
        onNodeWithText("Regenerate").performScrollTo().performClick()
        waitForIdle()
        assertTrue(before != shots[1].spec)
        assertEquals(listOf(true, false, true, true), shots.map { it.approvals.storyboard })
        onNodeWithText("3/4 approved").assertExists()

        onNodeWithText("↻ Regenerate all").performClick()
        waitForIdle()
        onNodeWithText("v1 stays in the version list", substring = true).assertExists()
        onNodeWithText("Back").performClick()
        waitForIdle()
        onNodeWithText("v1 stays in the version list", substring = true).assertDoesNotExist()
    }

    @Test
    fun aRunningJobShowsCancelAndLocksTheCards() = runComposeUiTest {
        open()
        jobs.run(StoryboardModel.regenerateLabel(2)) { awaitCancellation() }
        setContent { StoryboardScreen(model, jobs, onGoToInput = {}) }
        onNodeWithText("Regenerating shot #02…").assertExists()
        onAllNodesWithText(APPROVE)[0].assertIsNotEnabled()
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        onNodeWithText(StoryboardJobs.CANCELLED.message).assertExists()
        assertFalse(jobs.busy)
    }
}
