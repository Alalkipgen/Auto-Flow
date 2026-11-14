package io.github.alalkipgen.autoflow.ui.projects

import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.RecapMigration
import io.github.alalkipgen.autoflow.core.recap.RecapStyle
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

    @Test
    fun projectsHomeTakesNewProjectsAndAdoptsFoldersFoundThere() {
        val home = "/ext/files/projects"
        val phone = DashboardModel(fs, store, index, settings, picker, current, projectsHome = home)
        assertEquals(home, phone.lastParent)
        assertTrue(phone.createMusicVideo(phone.lastParent!!, "Rain"))
        assertEquals("$home/Rain.autoflow", current.state.value!!.dir)
        assertNull(settings.state.value.projectsDir) // the phone never asks, so nothing to remember

        // A restored folder, a stray file and a broken folder: only the restored project joins the list.
        store.create("$home/Restored.autoflow", "Restored", Mode.MUSIC_VIDEO)
        fs.writeDurably("$home/notes.txt", byteArrayOf(1))
        fs.createDirectories("$home/Empty.autoflow")
        phone.refresh()
        assertEquals(listOf("Restored", "Rain"), phone.state.value.rows.map { it.entry.name })
        assertNull(phone.state.value.message)
        phone.refresh() // already listed: no duplicates
        assertEquals(2, phone.state.value.rows.size)
    }

    @Test
    fun withoutAHomeNothingIsAdopted() {
        store.create("/ext/files/projects/Restored.autoflow", "Restored", Mode.MUSIC_VIDEO)
        model.refresh()
        assertTrue(model.state.value.rows.isEmpty())
        assertNull(model.projectsHome)
    }

    @Test
    fun quickRecapGetsAReadableFreeName() {
        settings.update { it.copy(projectsDir = "/videos") }
        assertTrue(model.createQuickRecap(180_000, RecapStyle.PLAIN))
        assertEquals("Quick Recap", current.state.value!!.project.name)
        assertTrue(model.createQuickRecap(180_000, RecapStyle.PLAIN))
        assertEquals("Quick Recap 2", current.state.value!!.project.name)
        assertEquals("/videos/Quick Recap 2.autoflow", current.state.value!!.dir)
    }

    @Test
    fun openRestoresV1BackupOfAnEarlyMigratedProject() {
        assertTrue(model.createMovieRecap("/videos", "Ngam", rightsConfirmed = true))
        val dir = current.state.value!!.dir
        val v1 = fs.read("$dir/project.json")
        RecapMigration.backup(fs, dir)
        fs.writeDurably("$dir/project.json", """{"schemaVersion":2,"recap":{"sources":{}}}""".encodeToByteArray())
        assertTrue(model.open(dir))
        assertEquals("Ngam", current.state.value!!.project.name)
        assertEquals(v1.decodeToString(), fs.read("$dir/project.json").decodeToString())
        assertTrue(fs.exists("$dir/${RecapMigration.V2_COPY_FILE}"))
    }

    @Test
    fun openLeavesV1ProjectsAlone() {
        assertTrue(model.createMovieRecap("/videos", "Ngam", rightsConfirmed = true))
        val dir = current.state.value!!.dir
        val before = fs.read("$dir/project.json").decodeToString()
        assertTrue(model.open(dir))
        assertEquals(before, fs.read("$dir/project.json").decodeToString())
        assertFalse(fs.exists("$dir/${RecapMigration.BACKUP_FILE}"))
    }
}
