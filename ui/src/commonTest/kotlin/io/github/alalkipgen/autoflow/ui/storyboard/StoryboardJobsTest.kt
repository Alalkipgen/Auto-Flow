package io.github.alalkipgen.autoflow.ui.storyboard

import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardGenerator
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class StoryboardJobsTest {
    private fun TestScope.jobs(): StoryboardJobs {
        val fs = InMemoryFileSystem()
        val session = ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, "/h/projects.json"), CurrentProject())
        return StoryboardJobs(StoryboardService(session, StoryboardGenerator(FakeTextProvider())), backgroundScope)
    }

    @Test
    fun runsOneJobAtATimeAndReportsItsResult() = runTest {
        val jobs = jobs()
        var done: StoryboardResult? = null
        assertTrue(jobs.run("A", onDone = { done = it }) { StoryboardResult.Done })
        assertEquals(JobState(running = "A"), jobs.state.value)
        assertFalse(jobs.run("B") { StoryboardResult.Done }, "a second job waits for the first")
        runCurrent()
        assertEquals(StoryboardResult.Done, done)
        assertEquals(JobState(last = StoryboardResult.Done), jobs.state.value)
        assertFalse(jobs.busy)
        jobs.clearLast()
        assertEquals(JobState(), jobs.state.value)
    }

    @Test
    fun cancelAndCrashesEndTheJobWithAMessage() = runTest {
        val jobs = jobs()
        var done = false
        jobs.run("slow", onDone = { done = true }) { awaitCancellation() }
        runCurrent()
        jobs.cancel()
        runCurrent()
        assertEquals(JobState(last = StoryboardJobs.CANCELLED), jobs.state.value)
        assertFalse(done, "a cancelled job does not call onDone")

        jobs.run("never started") { awaitCancellation() }
        jobs.cancel()
        runCurrent()
        assertEquals(JobState(last = StoryboardJobs.CANCELLED), jobs.state.value, "cancelled before it ran")

        jobs.run("crash") { error("disk full") }
        runCurrent()
        assertEquals(JobState(last = StoryboardResult.Failed("disk full")), jobs.state.value)
    }
}
