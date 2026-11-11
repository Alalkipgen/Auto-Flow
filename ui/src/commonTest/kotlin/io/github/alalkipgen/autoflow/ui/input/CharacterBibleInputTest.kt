package io.github.alalkipgen.autoflow.ui.input

import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardGenerator
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Character
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** Input → Characters (Character Bible): rows are a draft, saved cleaned into project.characters. */
class CharacterBibleInputTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val dir = "/p/Rain.autoflow"

    private fun TestScope.model(): InputModel {
        val p = (store.create(dir, "Rain", Mode.MUSIC_VIDEO, ProjectSettings()) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
        val jobs = StoryboardJobs(StoryboardService(session, StoryboardGenerator(FakeTextProvider())), backgroundScope)
        return InputModel(session, jobs)
    }

    private fun onDisk(): Project = (store.open(dir) as CoreResult.Ok).value

    @Test
    fun charactersAreADraftUntilSavedThenCleaned() = runTest {
        val m = model()
        m.addCharacter()
        m.addCharacter()
        assertEquals(listOf("CHAR1", "CHAR2"), m.state.value.characters.map { it.token })
        m.setCharacterToken(0, "hero boy ")
        assertEquals("HERO_BOY_", m.state.value.characters[0].token, "typing keeps a trailing _ so the next word can follow")
        m.setCharacterName(0, " မောင်မောင် ")
        m.setCharacterDescription(0, " a young man in a grey sweater ")
        assertEquals(listOf("{{CHAR2}} has no English description: shots that use it will not compile."), m.state.value.characterProblems)
        assertTrue(onDisk().characters.isEmpty(), "typing does not write project.json")

        assertTrue(m.save())
        assertEquals(
            listOf(Character("HERO_BOY", "မောင်မောင်", "a young man in a grey sweater"), Character("CHAR2")),
            onDisk().characters,
        )

        m.removeCharacter(1)
        assertEquals(listOf("HERO_BOY"), onDisk().characters.map { it.token }, "remove saves at once")
        assertEquals(emptyList(), m.state.value.characterProblems)
    }

    @Test
    fun savedCharactersComeBackWhenTheScreenOpens() = runTest {
        val m = model()
        m.addCharacter()
        m.setCharacterDescription(0, "an old fisherman")
        m.save()
        val again = InputModel(session, StoryboardJobs(StoryboardService(session, StoryboardGenerator(FakeTextProvider())), backgroundScope))
        assertEquals(listOf(Character("CHAR1", "", "an old fisherman")), again.state.value.characters)
    }
}
