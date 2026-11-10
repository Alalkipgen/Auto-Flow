package io.github.alalkipgen.autoflow.ui.shell

import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardGenerator
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.workflow.PipelineStep
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class ShellModelTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val service = StoryboardService(session, StoryboardGenerator(FakeTextProvider()))
    private val model = ShellModel(session)

    private fun open() {
        val dir = "/p/Rain.autoflow"
        val p = (store.create(dir, "Rain", Mode.MUSIC_VIDEO, ProjectSettings(durationMs = 30_000, clipMs = 8_000)) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
        session.writeLyrics("မိုးတွေရွာနေတယ်")
    }

    @Test
    fun withoutAProjectEveryPipelineScreenIsLocked() {
        val s = model.current()
        assertEquals(ShellModel.SCREEN_STEPS.keys, s.locks.keys)
        assertTrue(s.locks.values.all { it == ShellModel.NO_PROJECT_LOCK })
        assertTrue(s.steps.isEmpty())
    }

    @Test
    fun aNewProjectOpensInputAndStoryboardOnly() = runTest {
        open()
        val s = model.state.first()
        assertEquals(setOf("Shots", "References", "Flow Handoff", "Timeline", "Export"), s.locks.keys)
        assertEquals("Generate the storyboard on Input first", s.locks["Shots"])
        assertEquals("Compile a shot's prompt on Shots first", s.locks["References"])
        assertEquals(PipelineStep.INPUT, s.steps.single { it.current }.step)
        assertEquals(listOf("Input", "Storyboard", "Prompts", "References", "Flow Video", "Assembly"), s.steps.map { it.label })
    }

    @Test
    fun theStoryboardUnlocksShotsAndApprovingMovesTheStepper() = runTest {
        open()
        service.generateAll()
        var s = model.state.first()
        assertEquals(setOf("References", "Flow Handoff", "Timeline", "Export"), s.locks.keys)
        assertTrue(s.steps.first { it.step == PipelineStep.INPUT }.done)
        assertEquals(PipelineStep.STORYBOARD, s.steps.single { it.current }.step)

        service.approveAll()
        s = model.state.first()
        assertTrue(s.steps.first { it.step == PipelineStep.STORYBOARD }.done)
        assertEquals(PipelineStep.PROMPTS, s.steps.single { it.current }.step)
    }
}
