package io.github.alalkipgen.autoflow.ui.projects

import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DashboardModelTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val index = ProjectIndex(fs, "/home/u/.autoflow/projects.json", StepClock())
    private val settings = SettingsStore(fs, "/home/u/.autoflow/settings.json")
    private val picker = FakePicker()
    private val current = CurrentProject()
    private val model = DashboardModel(fs, store, index, settings, picker, current)

    @Test
    fun newMusicVideoUsesDefaultsOpensAndIsListed() {
        settings.update { it.copy(defaultRatio = Ratio.R16_9, defaultClipMs = 5_000) }
        assertTrue(model.createMusicVideo("/videos/", "မိုး Song"))
        val open = current.state.value!!
        assertEquals("/videos/မိုး Song.autoflow", open.dir)
        assertTrue(fs.exists("/videos/မိုး Song.autoflow/project.json"))
        assertEquals(Mode.MUSIC_VIDEO, open.project.mode)
        assertEquals(Ratio.R16_9 to 5_000L, open.project.settings.ratio to open.project.settings.clipMs)
        assertEquals("/videos/", model.lastParent)
        val row = model.state.value.rows.single()
        assertEquals("မိုး Song", row.entry.name)
        assertFalse(row.missing)
        assertNull(model.state.value.message)
    }

    @Test
    fun createExplainsBadInput() {
        assertFalse(model.createMusicVideo("/videos", " "))
        assertEquals("Enter a project name", model.state.value.message)
        assertFalse(model.createMusicVideo("", "Song"))
        assertEquals("Choose where to save the project", model.state.value.message)
        fs.createDirectories("/videos/Song.autoflow")
        fs.writeDurably("/videos/Song.autoflow/notes.txt", byteArrayOf(1))
        assertFalse(model.createMusicVideo("/videos", "Song"))
        assertTrue(model.state.value.message!!.startsWith("A folder with this name already exists"))
        assertNull(current.state.value)
    }

    @Test
    fun openFolderAndMissingFolders() = runTest {
        store.create("/usb/Old.autoflow", "Old", Mode.MUSIC_VIDEO)
        picker.folder = "/usb/Old.autoflow"
        assertTrue(model.openFolder())
        assertEquals("Old", current.state.value!!.project.name)

        picker.folder = "/usb"
        assertFalse(model.openFolder())
        assertEquals("No Auto Flow project here (project.json is missing)", model.state.value.message)

        picker.folder = null // cancelled
        assertFalse(model.openFolder())

        fs.atomicMove("/usb/Old.autoflow/project.json", "/usb/elsewhere.json")
        model.refresh()
        assertTrue(model.state.value.rows.single().missing)
        model.remove("/usb/Old.autoflow")
        assertTrue(model.state.value.rows.isEmpty())
    }

    @Test
    fun corruptProjectMentionsTheBackup() {
        store.create("/p/A.autoflow", "A", Mode.MUSIC_VIDEO)
        store.save("/p/A.autoflow", store.open("/p/A.autoflow").getOrNull()!!) // second save leaves project.json.bak
        fs.writeDurably("/p/A.autoflow/project.json", "{ broken".encodeToByteArray())
        assertFalse(model.open("/p/A.autoflow"))
        assertTrue(model.state.value.message!!.endsWith("A backup (project.json.bak) exists."), model.state.value.message)
    }
}
