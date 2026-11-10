package io.github.alalkipgen.autoflow.ui.input

import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardGenerator
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.model.StoryboardMode
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.workflow.Blocker
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class InputModelTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val dir = "/p/Rain.autoflow"

    private fun TestScope.model(open: Boolean = true): Pair<InputModel, StoryboardJobs> {
        if (open) {
            val p = (store.create(dir, "Rain", Mode.MUSIC_VIDEO, ProjectSettings()) as CoreResult.Ok).value
            current.set(OpenProject(dir, p))
        }
        val jobs = StoryboardJobs(StoryboardService(session, StoryboardGenerator(FakeTextProvider())), backgroundScope)
        return InputModel(session, jobs) to jobs
    }

    private fun onDisk(): Project = (store.open(dir) as CoreResult.Ok).value

    @Test
    fun withoutAProjectNothingIsSaved() = runTest {
        val (m, _) = model(open = false)
        assertNull(m.state.value.projectName)
        assertFalse(m.save())
        assertFalse(m.generate {})
    }

    @Test
    fun textIsADraftUntilSavedAndChipsSaveAtOnce() = runTest {
        val (m, _) = model()
        assertEquals(listOf(Blocker.EMPTY_LYRICS, Blocker.NO_DURATION), m.state.value.blockers)

        m.setLyrics("[Chorus]\nမိုးတွေရွာနေတယ်")
        m.setDuration("3:25")
        m.setMood("nostalgic")
        assertEquals(205_000, m.state.value.settings.durationMs)
        assertEquals(emptyList(), m.state.value.blockers)
        assertEquals(0, onDisk().settings.durationMs, "typing does not write project.json")

        m.setRatio(Ratio.R16_9)
        m.setMode(StoryboardMode.PERFORMANCE)
        val saved = onDisk().settings
        assertEquals(Ratio.R16_9, saved.ratio)
        assertEquals(StoryboardMode.PERFORMANCE, saved.storyboardMode)
        assertEquals(205_000, saved.durationMs)
        assertEquals("nostalgic", saved.mood)
        assertEquals("[Chorus]\nမိုးတွေရွာနေတယ်", session.readLyrics())

        m.setDuration("3:5")
        assertEquals(listOf(Blocker.NO_DURATION), m.state.value.blockers)
        assertFalse(m.generate {}, "blocked")
    }

    @Test
    fun generateSavesRunsTheJobAndRefreshes() = runTest {
        val (m, jobs) = model()
        m.setLyrics("မိုး")
        m.setDuration("0:30")
        var opened = false
        assertTrue(m.generate { opened = true })
        assertTrue(jobs.busy)
        m.setLyrics("ignored while the job runs")
        assertEquals("မိုး", m.state.value.lyrics)
        runCurrent()
        assertTrue(opened)
        assertTrue(m.state.value.hasStoryboard)
        assertNotNull(onDisk().currentStoryboard)
        assertEquals(30_000, onDisk().settings.durationMs)
    }

    @Test
    fun aCancelledJobKeepsTheForm() = runTest {
        val (m, jobs) = model()
        m.setLyrics("မိုး")
        m.setDuration("30")
        jobs.run("slow") { awaitCancellation() }
        assertFalse(m.generate {}, "one job at a time")
        jobs.cancel()
        runCurrent()
        assertEquals("မိုး", m.state.value.lyrics)
    }

    @Test
    fun durationParsing() {
        assertEquals(205_000, InputModel.parseDuration("3:25"))
        assertEquals(205_000, InputModel.parseDuration(" 205 "))
        assertEquals(600_000, InputModel.parseDuration("10:00"))
        assertNull(InputModel.parseDuration("3:5"))
        assertNull(InputModel.parseDuration("3:60"))
        assertNull(InputModel.parseDuration("0"))
        assertNull(InputModel.parseDuration("abc"))
        assertEquals("3:25", InputModel.formatDuration(205_000))
        assertEquals("0:08", InputModel.formatDuration(8_000))
    }
}
