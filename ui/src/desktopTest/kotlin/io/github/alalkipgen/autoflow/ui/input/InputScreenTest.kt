package io.github.alalkipgen.autoflow.ui.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardGenerator
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation

@OptIn(ExperimentalTestApi::class)
class InputScreenTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val dir = "/p/Rain.autoflow"

    /** Unconfined: the fake AI never suspends, so a job finishes inside the click. */
    private val jobs = StoryboardJobs(StoryboardService(session, StoryboardGenerator(FakeTextProvider())), CoroutineScope(Dispatchers.Unconfined))

    private fun open() {
        val p = (store.create(dir, "Rain", Mode.MUSIC_VIDEO, ProjectSettings()) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
    }

    private fun onDisk(): Project = (store.open(dir) as CoreResult.Ok).value

    @Test
    fun withoutAProjectItPointsToTheDashboard() = runComposeUiTest {
        setContent { InputScreen(InputModel(session, jobs), jobs, onGenerated = {}) }
        onNodeWithText(NO_PROJECT_TEXT).assertExists()
    }

    @Test
    fun fillTheFormConfirmAndGenerate() = runComposeUiTest {
        open()
        var generated = 0
        setContent { InputScreen(InputModel(session, jobs), jobs, onGenerated = { generated++ }) }
        onNodeWithText("• Add the lyrics").assertExists()
        onNodeWithText(GENERATE_BUTTON).assertIsNotEnabled()

        onNodeWithContentDescription("Lyrics", substring = true).performTextInput("[Chorus]\nမိုးတွေရွာနေတယ်")
        onNodeWithContentDescription("Duration (m:ss)").performTextInput("0:30")
        onNodeWithText("Anime").performScrollTo().performClick()
        onNodeWithText("16:9").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("• Add the lyrics").assertDoesNotExist()
        assertEquals(Ratio.R16_9, onDisk().settings.ratio, "chips save at once")
        assertEquals("Anime", onDisk().settings.videoStyle)

        onNodeWithText(GENERATE_BUTTON).performScrollTo().assertIsEnabled().performClick()
        onNodeWithText("about 4 shots of 8s", substring = true).assertExists()
        onNodeWithText("Generate").performScrollTo().performClick()
        waitForIdle()
        assertEquals(1, generated)
        assertNotNull(onDisk().currentStoryboard)
        assertEquals(4, onDisk().shots.size)
    }

    @Test
    fun aRunningJobLocksTheFormAndCanBeCancelled() = runComposeUiTest {
        open()
        jobs.run("Regenerating shot 2") { awaitCancellation() }
        setContent { InputScreen(InputModel(session, jobs), jobs, onGenerated = {}) }
        onNodeWithText("Regenerating shot 2…").assertExists()
        onNodeWithText(GENERATE_BUTTON).assertDoesNotExist()
        onNodeWithText("16:9").performScrollTo().performClick()
        assertEquals(Ratio.R9_16, onDisk().settings.ratio, "locked")
        onNodeWithText("Cancel").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText(StoryboardJobs.CANCELLED.message).assertExists()
        onNodeWithText(GENERATE_BUTTON).assertExists()
    }

    @Test
    fun leavingTheScreenSavesTheDraft() = runComposeUiTest {
        open()
        var shown by mutableStateOf(true)
        setContent { if (shown) InputScreen(InputModel(session, jobs), jobs, onGenerated = {}) else AfText("elsewhere") }
        onNodeWithContentDescription("Mood", substring = true).performTextInput("nostalgic")
        assertEquals("", onDisk().settings.mood)
        shown = false
        waitForIdle()
        assertEquals("nostalgic", onDisk().settings.mood)
    }
}
