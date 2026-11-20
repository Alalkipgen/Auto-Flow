package io.github.alalkipgen.autoflow.ui.projects

import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.recap.RecapMigration
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Phase 7 projects in the index and Dashboard (D-77, D-78). */
class ToolProjectIndexTest {
    private val fs = InMemoryFileSystem()
    private val path = "/home/u/.autoflow/projects.json"
    private val newerRow = """{"path":"/p/dub.autoflow","name":"Dub","mode":"DUBBING_NEXT","ratio":"9:16",
        "acceptedShots":0,"totalShots":0,"lastOpenedAt":"2026-11-20T08:00:00Z","future":{"x":1}}"""

    private fun writeIndexWithNewerRow() {
        fs.createDirectories("/home/u/.autoflow")
        fs.writeDurably(path, """{"schemaVersion":1,"projects":[$newerRow],"removedPaths":[],"deletedProjects":{}}""".encodeToByteArray())
    }

    private fun rowsOnDisk() = Json.parseToJsonElement(fs.read(path).decodeToString()).jsonObject["projects"]!!.jsonArray

    @Test
    fun withoutUnknownRowsTheFileIsWrittenExactlyAsBefore() {
        val index = ProjectIndex(fs, path, StepClock())
        index.record("/p/a.autoflow", testProject("A"))
        val bytes = fs.read(path)
        ProjectIndex(fs, path, StepClock(5_000)).record("/p/a.autoflow", testProject("A"), opened = false)
        assertContentEquals(bytes, fs.read(path))
        assertFalse("newer" in bytes.decodeToString())
    }

    @Test
    fun unknownModeRowIsKeptVerbatimAndTheRestStaysReadable() {
        writeIndexWithNewerRow()
        val index = ProjectIndex(fs, path, StepClock())
        assertNull(index.problem)
        assertEquals(listOf(NewerProjectEntry("/p/dub.autoflow", "Dub")), index.newerEntries())
        index.record("/p/a.autoflow", testProject("A"))
        index.remove("/p/b.autoflow")
        assertEquals(listOf("A"), index.entries.value.map { it.name })
        assertEquals(Json.parseToJsonElement(newerRow), rowsOnDisk().last())

        val reopened = ProjectIndex(fs, path, StepClock())
        assertEquals(listOf("A"), reopened.entries.value.map { it.name })
        assertEquals(listOf("Dub"), reopened.newerEntries().map { it.name })
        reopened.remove("/p/dub.autoflow") // the owner can still hide it
        assertEquals(emptyList(), ProjectIndex(fs, path, StepClock()).newerEntries())
    }

    @Test
    fun subtitlesProjectIsListedOpenedAndRemovedLikeTheOthers() {
        val store = ProjectStore(fs)
        val index = ProjectIndex(fs, path, StepClock())
        val current = CurrentProject()
        val model = DashboardModel(fs, store, index, SettingsStore(fs, "/home/u/.autoflow/settings.json"), FakePicker(), current)
        store.create("/p/Clip.autoflow", "Clip", Mode.SUBTITLES).getOrNull()!!
        assertTrue(model.open("/p/Clip.autoflow"))
        assertEquals(Mode.SUBTITLES, current.state.value?.project?.mode)
        assertNull(model.state.value.message)
        val row = model.state.value.rows.single().entry
        assertEquals(Mode.SUBTITLES to "Clip", row.mode to row.name)
        assertEquals(UiText.V3_SUBTITLES, row.mode.label())
        assertFalse(row.mode.hasShots())
        model.remove("/p/Clip.autoflow")
        assertTrue(model.state.value.rows.isEmpty())
        assertTrue(fs.exists("/p/Clip.autoflow/project.json")) // removing from the list never deletes files
    }

    @Test
    fun newerAppProjectIsNeverReplacedFromAnOlderBackup() {
        val store = ProjectStore(fs)
        val model = DashboardModel(fs, store, ProjectIndex(fs, path, StepClock()), SettingsStore(fs, "/home/u/.autoflow/settings.json"),
            FakePicker(), CurrentProject())
        val dir = "/p/New.autoflow"
        store.create(dir, "New", Mode.MUSIC_VIDEO).getOrNull()!!
        val old = fs.read("$dir/project.json")
        fs.writeDurably("$dir/${RecapMigration.BACKUP_FILE}", old)
        val newer = old.decodeToString().replaceFirst("{", "{\n    \"voice\": {},").encodeToByteArray()
        fs.writeDurably("$dir/project.json", newer)
        assertFalse(model.open(dir))
        assertEquals(UiText.NEEDS_NEWER_APP.en, model.state.value.message)
        assertContentEquals(newer, fs.read("$dir/project.json"))
        assertFalse(fs.exists("$dir/${RecapMigration.DAMAGED_FILE}"))
    }
}
