package io.github.alalkipgen.autoflow.core.store

import io.github.alalkipgen.autoflow.core.CoreError
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.hash.computeHash
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.GlobalStyle
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.readTestResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectStoreTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs, TestClock(), newId = { "id-1" })
    private val dir = "/p/Midnight Rain.autoflow"

    private fun <T> CoreResult<T>.ok(): T = assertIs<CoreResult.Ok<T>>(this).value
    private fun CoreResult<*>.err(): CoreError = assertIs<CoreResult.Err>(this).error
    private fun text(path: String) = fs.read(path).decodeToString()

    @Test
    fun createMakesFolderLayoutAndManifest() {
        val p = store.create(dir, " Midnight Rain ", Mode.MUSIC_VIDEO).ok()
        assertEquals("Midnight Rain", p.name)
        assertEquals("id-1", p.id)
        ProjectStore.SUBFOLDERS.forEach { assertTrue(fs.exists("$dir/$it"), it) }
        assertEquals(p, store.open(dir).ok())
        assertEquals(GlobalStyle().computeHash(), p.globalStyle.hash)
    }

    @Test
    fun createRefusesNonEmptyFolderAndBlankName() {
        store.create(dir, "A", Mode.MUSIC_VIDEO).ok()
        assertIs<CoreError.AlreadyExists>(store.create(dir, "B", Mode.MUSIC_VIDEO).err())
        assertIs<CoreError.InvalidInput>(store.create("/q", "  ", Mode.MUSIC_VIDEO).err())
    }

    @Test
    fun saveKeepsPreviousVersionAsBak() {
        val v1 = store.create(dir, "A", Mode.MUSIC_VIDEO).ok()
        val v1Text = text("$dir/project.json")
        val v2 = store.save(dir, v1.copy(name = "B", globalStyle = GlobalStyle(visual = "film"))).ok()
        assertEquals("B", store.open(dir).ok().name)
        assertEquals(v1Text, text("$dir/project.json.bak"))
        assertEquals(v1, store.openBackup(dir).ok())
        assertTrue(v2.updatedAt > v1.updatedAt)
        assertEquals(GlobalStyle(visual = "film").computeHash(), v2.globalStyle.hash)
    }

    @Test
    fun crashBeforeRenameLeavesOldManifestReadable() {
        val v1 = store.create(dir, "A", Mode.MUSIC_VIDEO).ok()
        for (failing in listOf("write" to "$dir/project.json.tmp", "move" to "$dir/project.json.tmp")) {
            fs.failWhen = { op, path -> op == failing.first && path == failing.second }
            assertIs<CoreError.Io>(store.save(dir, v1.copy(name = "lost")).err())
            fs.failWhen = { _, _ -> false }
            assertEquals("A", store.open(dir).ok().name, "after failing ${failing.first}")
        }
    }

    @Test
    fun corruptManifestIsReportedNotOverwritten() {
        val v1 = store.create(dir, "A", Mode.MUSIC_VIDEO).ok()
        store.save(dir, v1.copy(name = "B")).ok()
        fs.writeDurably("$dir/project.json", "{ not json".encodeToByteArray())
        val err = assertIs<CoreError.Corrupt>(store.open(dir).err())
        assertTrue(err.backupAvailable)
        assertEquals("{ not json", text("$dir/project.json"))
        assertEquals("A", store.openBackup(dir).ok().name)
    }

    @Test
    fun unknownKeyIsCorruptAndNewerSchemaIsRefused() {
        fs.createDirectories(dir)
        val fixture = readTestResource("fixtures/project-v1.json")
        fs.writeDurably("$dir/project.json", fixture.replace("\"timeline\": null", "\"timeline\": null, \"extra\": 1").encodeToByteArray())
        assertIs<CoreError.Corrupt>(store.open(dir).err())
        fs.writeDurably("$dir/project.json", fixture.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2").encodeToByteArray())
        assertEquals(CoreError.UnsupportedSchema("$dir/project.json", 2, Project.SCHEMA_VERSION), store.open(dir).err())
    }

    @Test
    fun openMissingIsNotFound() {
        assertIs<CoreError.NotFound>(store.open("/nowhere").err())
    }

    @Test
    fun savedFileIsTheCoreJsonOfTheReturnedProject() {
        val p = store.create(dir, "A", Mode.MOVIE_RECAP).ok()
        assertEquals(CoreJson.encodeToString(p), text("$dir/project.json"))
    }
}
