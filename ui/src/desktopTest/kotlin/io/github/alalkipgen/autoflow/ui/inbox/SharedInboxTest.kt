package io.github.alalkipgen.autoflow.ui.inbox

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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Share-sheet import → Inbox: shared files wait next to the projects, then move into the open project's inbox. */
class SharedInboxTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val hold = "/p/.shared-inbox"
    private val dir = "/p/Rain.autoflow"

    private fun share(name: String, bytes: ByteArray = byteArrayOf(1, 2)) {
        fs.createDirectories(hold)
        fs.writeDurably("$hold/$name", bytes)
    }

    private fun openProject() {
        val p = (store.create(dir, "Rain", Mode.MUSIC_VIDEO, ProjectSettings()) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
    }

    @Test
    fun aShareWithAProjectOpenGoesStraightIntoItsInbox() {
        openProject()
        fs.createDirectories("$dir/inbox")
        fs.writeDurably("$dir/inbox/clip.mp4", byteArrayOf(9))
        val inbox = SharedInbox(fs, session, hold)
        share("clip.mp4", byteArrayOf(5, 6))
        share(".still-copying.part")
        inbox.received()

        assertEquals(1, inbox.state.value.arrivals, "the shell opens Inbox")
        assertEquals(emptyList(), inbox.state.value.waiting)
        assertContentEquals(byteArrayOf(9), fs.read("$dir/inbox/clip.mp4"), "an inbox file is never overwritten")
        assertContentEquals(byteArrayOf(5, 6), fs.read("$dir/inbox/clip (2).mp4"))
        assertTrue(fs.exists("$hold/.still-copying.part"), "a copy still being written stays")
        assertEquals("1 file shared into this project's inbox. Attach each one to a shot.", inbox.state.value.message)
        val items = InboxModel(session, fs).refresh().items.map { it.name to it.kind }
        assertEquals(listOf("clip (2).mp4" to InboxItemKind.VIDEO, "clip.mp4" to InboxItemKind.VIDEO), items)
    }

    @Test
    fun withoutAProjectSharesWaitUntilMoveHere() {
        val inbox = SharedInbox(fs, session, hold)
        share("a.jpg")
        share("b.mp4")
        inbox.received(failed = 1)
        assertEquals(listOf("a.jpg", "b.mp4"), inbox.state.value.waiting)
        assertEquals(
            "2 files shared: open a project, then tap \"Move here\" here. 1 file could not be read from the other app.",
            inbox.state.value.message,
        )

        inbox.moveHere()
        assertEquals("Open a project first.", inbox.state.value.message)
        assertEquals(2, inbox.state.value.waiting.size)

        openProject()
        inbox.moveHere()
        assertEquals(emptyList(), inbox.state.value.waiting)
        assertEquals("2 files moved into this project's inbox.", inbox.state.value.message)
        assertTrue(fs.exists("$dir/inbox/a.jpg"))
        assertTrue(fs.exists("$dir/inbox/b.mp4"))
        assertFalse(fs.exists("$hold/a.jpg"))
    }

    @Test
    fun waitingFilesAreFoundAfterARestart() {
        share("old.png")
        assertEquals(listOf("old.png"), SharedInbox(fs, session, hold).state.value.waiting)
    }

    @Test
    fun names() {
        assertEquals("a.mp4", SharedInbox.freeName("a.mp4", setOf("b.mp4")))
        assertEquals("a (3).mp4", SharedInbox.freeName("a.mp4", setOf("a.mp4", "a (2).mp4")))
        assertEquals("README (2)", SharedInbox.freeName("README", setOf("README")))
        assertEquals("x_y.jpg", SharedInbox.safeName("x/y.jpg"))
        assertEquals("hidden.png", SharedInbox.safeName("..hidden.png"))
        assertEquals("shared", SharedInbox.safeName(" . "))
    }
}
