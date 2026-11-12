package io.github.alalkipgen.autoflow.ui.shots

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Approvals
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.Character
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.model.ShotSpec
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.workflow.Stale
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Q-10 / D-29: a changed character description marks the prompts that use it ⚠ Stale; Recompile clears it. */
class PromptStaleTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val model = ShotsModel(session)

    private fun spec(n: Int, subject: String, scene: String = "") = ShotSpec(
        shot = n, sceneMy = scene, subjectEn = subject, actionEn = "walks", settingEn = "street", cameraEn = "close-up",
        moodEn = "sad", lightingEn = "neon",
    )

    private fun putSpec(s: ShotSpec) =
        (session.artifacts()!!.put(CoreJson.encodeToString(ShotSpec.serializer(), s).encodeToByteArray(), ArtifactKind.SHOT_SPEC, "json", s.shot) as CoreResult.Ok).value.id

    private fun open() {
        val p = (store.create("/p/Rain.autoflow", "Rain", Mode.MUSIC_VIDEO, ProjectSettings()) as CoreResult.Ok).value
        current.set(OpenProject("/p/Rain.autoflow", p))
        val ok = Approvals(storyboard = true)
        session.save(
            p.copy(
                characters = listOf(Character("CHAR1", "မမ", "young woman, red raincoat"), Character("CHAR2", "", "tall man")),
                shots = listOf(
                    Shot(1, 0, 8_000, spec = putSpec(spec(1, "{{CHAR1}} alone")), approvals = ok),
                    Shot(2, 8_000, 8_000, spec = putSpec(spec(2, "an empty street")), approvals = ok),
                ),
            ),
        )
        assertNull(model.compilePrompt(1))
        assertNull(model.compilePrompt(2))
    }

    private fun stale(n: Int): Boolean {
        val p = session.open!!.project
        return Stale.prompt(p, p.shots.first { it.number == n }, session.lookup())
    }

    private fun setCharacters(transform: (List<Character>) -> List<Character>) {
        val p = session.open!!.project
        session.save(p.copy(characters = transform(p.characters)))
    }

    @Test
    fun changingADescriptionStalesOnlyTheShotsThatUseIt() {
        open()
        assertFalse(stale(1))
        setCharacters { cs -> cs.map { if (it.token == "CHAR1") it.copy(descriptionEn = "young woman, blue raincoat") else it } }
        assertTrue(stale(1))
        assertFalse(stale(2), "shot 2 has no character")

        assertNull(model.compilePrompt(1))
        assertFalse(stale(1), "recompiled")
        model.select(1)
        assertTrue("blue raincoat" in model.current().detail!!.promptText!!)
    }

    @Test
    fun otherCharactersAndBurmeseNamesDoNotStale() {
        open()
        setCharacters { cs -> cs.map { if (it.token == "CHAR2") it.copy(descriptionEn = "short man") else it.copy(nameMy = "အသစ်") } }
        assertFalse(stale(1))
    }

    @Test
    fun aSpecChangeThatGivesTheSamePromptIsClearedByRecompile() {
        open()
        val p = session.open!!.project
        val newSpec = putSpec(spec(2, "an empty street", scene = "လမ်းလွတ်"))
        session.save(p.copy(shots = p.shots.map { if (it.number == 2) it.copy(spec = newSpec) else it }))
        assertTrue(stale(2), "the Burmese scene text changed, so the spec id changed")
        val before = session.open!!.project.shots[1].promptIr
        assertNull(model.compilePrompt(2))
        assertEquals(before, session.open!!.project.shots[1].promptIr, "same prompt bytes → same artifact")
        assertFalse(stale(2), "D-29: its inputs were refreshed")
    }
}
