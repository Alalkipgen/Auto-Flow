package io.github.alalkipgen.autoflow.ui.storyboard

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardGenerator
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.workflow.Blocker
import io.github.alalkipgen.autoflow.core.workflow.Stale
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement

/** Delegates to the fake, but [failing] makes every call fail like a 503 that outlasted the retries. */
private class SwitchableText(private val fake: TextProvider = FakeTextProvider()) : TextProvider {
    var failing = false
    override suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> =
        if (failing) AiResult.Failed("http_503", "Model overloaded") else fake.json(prompt, schema, system)
}

class StoryboardServiceTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val text = SwitchableText()
    private val service = StoryboardService(session, StoryboardGenerator(text))
    private val dir = "/p/Rain.autoflow"

    private fun open(settings: ProjectSettings = ProjectSettings(durationMs = 30_000, clipMs = 8_000)): Project {
        val p = (store.create(dir, "Rain", Mode.MUSIC_VIDEO, settings) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
        return p
    }

    private val project get() = current.state.value!!.project

    @Test
    fun generateAllWritesArtifactsShotsAndAVersion() = runTest {
        open()
        assertEquals(StoryboardResult.Blocked(listOf(Blocker.EMPTY_LYRICS)), service.generateAll())
        assertIs<CoreResult.Ok<Unit>>(session.writeLyrics("မိုးတွေရွာနေတယ်\nမင်းကိုသတိရတယ်"))

        assertEquals(StoryboardResult.Done, service.generateAll())
        val p = project
        assertEquals(listOf(0L, 8_000L, 16_000L, 24_000L), p.shots.map { it.startMs }, "30 s at 8 s; 6 s remainder is its own shot")
        assertNotNull(p.analysis)
        assertEquals(listOf(p.currentStoryboard!!), p.storyboards)
        p.shots.forEach { shot ->
            val spec = assertNotNull(service.spec(shot))
            assertEquals(shot.number, spec.shot)
            assertTrue(spec.sceneMy.isNotBlank())
            assertFalse(shot.approvals.storyboard)
            assertFalse(Stale.spec(p, shot, session.lookup()))
        }
        assertEquals(p, (store.open(dir) as CoreResult.Ok).value, "saved to disk")
    }

    @Test
    fun approvalsRegenerateAndEditFollowD21() = runTest {
        open()
        session.writeLyrics("မိုး")
        service.generateAll()
        assertEquals(StoryboardResult.Done, service.approveAll())
        assertTrue(project.shots.all { it.approvals.storyboard })

        val before = project.shots[1].spec
        assertEquals(StoryboardResult.Done, service.regenerateShot(2))
        assertNotEquals(before, project.shots[1].spec)
        assertEquals(listOf(true, false, true, true), project.shots.map { it.approvals.storyboard })

        val edited = service.spec(project.shots[0])!!.copy(sceneMy = "ကော်ဖီဆိုင်ထဲမှာ")
        assertEquals(StoryboardResult.Done, service.editSpec(1, edited))
        assertEquals("ကော်ဖီဆိုင်ထဲမှာ", service.spec(project.shots[0])!!.sceneMy)
        assertFalse(project.shots[0].approvals.storyboard)

        assertEquals(StoryboardResult.Done, service.regenerateShot(1, keepScene = true))
        assertEquals("ကော်ဖီဆိုင်ထဲမှာ", service.spec(project.shots[0])!!.sceneMy, "the user's scene is kept")

        assertEquals(StoryboardResult.Done, service.approve(1))
        assertTrue(project.shots[0].approvals.storyboard)
        assertEquals(StoryboardResult.Done, service.approve(1, approved = false))
        assertFalse(project.shots[0].approvals.storyboard)
    }

    @Test
    fun failuresLeaveTheProjectUnchangedAndOldVersionsCanBeRestored() = runTest {
        open()
        session.writeLyrics("မိုး")
        service.generateAll()
        val v1 = project
        text.failing = true
        assertEquals(StoryboardResult.Failed("Model overloaded"), service.generateAll())
        assertEquals(v1, project)
        text.failing = false

        session.save(project.copy(settings = project.settings.copy(durationMs = 16_000)))
        assertEquals(StoryboardResult.Done, service.generateAll())
        assertEquals(2, project.shots.size)
        assertEquals(2, project.storyboards.size)

        assertEquals(StoryboardResult.Done, service.switchVersion(v1.currentStoryboard!!))
        assertEquals(v1.currentStoryboard, project.currentStoryboard)
        assertEquals(v1.shots.map { it.spec }, project.shots.map { it.spec }, "same spec ids again (content-addressed)")
        assertIs<StoryboardResult.Failed>(service.regenerateShot(9))
    }
}
