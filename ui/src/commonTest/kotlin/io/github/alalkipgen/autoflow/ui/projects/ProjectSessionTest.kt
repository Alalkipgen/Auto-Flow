package io.github.alalkipgen.autoflow.ui.projects

import io.github.alalkipgen.autoflow.core.CoreError
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Audit F03 / F04: saves go to the project they were made from, and never over a newer save. */
class ProjectSessionTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)

    private fun <T> CoreResult<T>.ok(): T = assertIs<CoreResult.Ok<T>>(this).value
    private fun CoreResult<*>.err(): CoreError = assertIs<CoreResult.Err>(this).error

    private fun open(dir: String, name: String): Project {
        val p = store.create(dir, name, Mode.MOVIE_RECAP).ok()
        current.set(OpenProject(dir, p))
        return p
    }

    @Test
    fun aCopyOfAnotherProjectIsNeverSavedIntoTheOpenOne() {
        val a = open("/a", "A")
        open("/b", "B")
        val bBefore = fs.read("/b/project.json")
        assertEquals(CoreError.InvalidInput(ProjectSession.OTHER_PROJECT), session.save(a.copy(name = "A edited")).err())
        assertTrue(bBefore.contentEquals(fs.read("/b/project.json")))
        assertEquals("A", store.open("/a").ok().name)
    }

    @Test
    fun aStaleCopyIsAConflictAndTheNewerSaveStays() {
        val a = open("/a", "A")
        session.save(a.copy(name = "newer")).ok()
        assertEquals(CoreError.Conflict("/a/project.json"), session.save(a.copy(name = "stale")).err())
        assertEquals("newer", store.open("/a").ok().name)
        assertEquals("newer", current.state.value!!.project.name, "the screen keeps the saved version")
    }

    @Test
    fun updateOfAFolderThatIsNotOpenChangesOnlyThatFolder() {
        val a = open("/a", "A")
        val b = open("/b", "B")
        val updated = session.update("/a", a.id) { it.copy(name = "A from a late result") }.ok()
        assertEquals("A from a late result", store.open("/a").ok().name)
        assertEquals(updated, store.open("/a").ok())
        assertEquals(OpenProject("/b", b), current.state.value, "the open project is not replaced by another folder")
        assertEquals("B", store.open("/b").ok().name)
    }

    @Test
    fun updateRefusesAFolderNowHoldingAnotherProject() {
        val a = open("/a", "A")
        fs.writeDurably("/a/project.json", fs.read(run { store.create("/c", "C", Mode.MOVIE_RECAP); "/c/project.json" }))
        val before = fs.read("/a/project.json")
        assertEquals(CoreError.InvalidInput(ProjectSession.OTHER_PROJECT), session.update("/a", a.id) { it.copy(name = "x") }.err())
        assertTrue(before.contentEquals(fs.read("/a/project.json")))
    }

    @Test
    fun updateOfTheOpenFolderRefreshesTheOpenProject() {
        val a = open("/a", "A")
        val updated = session.update("/a", a.id) { it.copy(name = "A2") }.ok()
        assertEquals(OpenProject("/a", updated), current.state.value)
    }
}
