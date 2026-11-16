package io.github.alalkipgen.autoflow.ui.projects

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.QuickRecap
import io.github.alalkipgen.autoflow.core.recap.RecapMigration
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.RecapScript
import io.github.alalkipgen.autoflow.core.recap.RecapSegment
import io.github.alalkipgen.autoflow.core.recap.RecapStory
import io.github.alalkipgen.autoflow.core.recap.RecapStyle
import io.github.alalkipgen.autoflow.core.recap.Scenes
import io.github.alalkipgen.autoflow.core.recap.toV1
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.recap.fitFooter
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    /** Audit F15: the chosen 1 min reaches the recap itself, is saved, survives a reopen and drives the consumers. */
    @Test
    fun quickRecapMustApplyChosenDurationToActualRecap() {
        settings.update { it.copy(projectsDir = "/videos") }
        assertTrue(model.createQuickRecap(60_000, RecapStyle.PLAIN))
        val dir = current.state.value!!.dir
        val recap = current.state.value!!.project.recap!!
        assertEquals(60_000L, recap.targetMs)
        assertEquals(60_000L, CoreJson.decodeFromJsonElement(QuickRecap.serializer(), recap.quick!!).targetMs)

        val onDisk = (store.open(dir) as CoreResult.Ok).value.recap!!
        assertEquals(60_000L, onDisk.targetMs, "saved in project.json")
        current.set(null)
        assertTrue(model.open(dir))
        val reopened = current.state.value!!.project.recap!!
        assertEquals(60_000L, reopened.targetMs, "after a reopen")

        // The consumers: the fit-check footer and the Scenes header's off-target rule (v1 adapter).
        assertEquals("Recap 0:00 (ပစ်မှတ် 1:00) · 0 ခုအနက် 0 ခု အသံပါ", fitFooter(reopened, emptyList(), io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.BURMESE))
        val v1 = reopened.toV1().copy(scenes = listOf(RecapScene(0, 60_000, picked = true)))
        assertFalse(Scenes.offTarget(v1), "60 s picked is on a 60 s target")
        assertTrue(Scenes.offTarget(v1.copy(scenes = listOf(RecapScene(0, 300_000, picked = true)))), "5 min picked is off it")
    }

    @Test
    fun openRecoversFromACurrentFormatBackupAndKeepsTheDamagedFile() {
        assertTrue(model.createMovieRecap("/videos", "Ngam", rightsConfirmed = true))
        val dir = current.state.value!!.dir
        val validJson = fs.read("$dir/project.json").decodeToString()
        // A backup in the current format (not v1: the true-v1 cases are below); project.json is not a valid project.
        fs.writeDurably("$dir/${RecapMigration.BACKUP_FILE}", validJson.encodeToByteArray())
        fs.writeDurably("$dir/project.json", """{"schemaVersion":2,"recap":{"sources":{}}}""".encodeToByteArray())
        assertTrue(model.open(dir))
        assertEquals("Ngam", current.state.value!!.project.name)
        assertEquals(validJson, fs.read("$dir/project.json").decodeToString())
        assertEquals("""{"schemaVersion":2,"recap":{"sources":{}}}""", fs.read("$dir/${RecapMigration.DAMAGED_FILE}").decodeToString())
        assertTrue(RecapMigration.DAMAGED_FILE in model.state.value.message!!)
    }

    // ---- true v1 projects (audit F02 / F13) ----

    /** A real v1 Movie Recap project.json (as core's migration/recap-v1.json) whose script is a stored `rs` artifact. */
    private fun writeV1(dir: String, withScript: Boolean = true): ByteArray {
        fs.createDirectories("$dir/artifacts")
        val script = RecapScript(source = RecapScript.SOURCE_GEMINI, style = RecapStyle.COMMENTARY, segments = listOf(RecapSegment(0, 4200, "ညမှာ ရထား ထွက်တယ်။"), RecapSegment(4200, 9000, "သူ မနိုးဘူး။")))
        val id = if (withScript) (ArtifactStore(fs, dir).put(CoreJson.encodeToString(RecapScript.serializer(), script).encodeToByteArray(), ArtifactKind.RECAP_SCRIPT, "json") as CoreResult.Ok).value.id.value
        else "rs_0123456789ab"
        val bytes = V1_PROJECT.replace("rs_0123456789ab", id).encodeToByteArray()
        fs.writeDurably("$dir/project.json", bytes)
        return bytes
    }

    private fun assertMigratedRecap(dir: String) {
        val recap = current.state.value!!.project.recap!!
        assertEquals(dir, current.state.value!!.dir)
        assertTrue(recap.storyApproved)
        val story = CoreJson.decodeFromString(RecapStory.serializer(), (ArtifactStore(fs, dir).readBytes(recap.story!!) as CoreResult.Ok).value.decodeToString())
        assertEquals(story.blocks.map { it.id }, recap.takes.map { it.block })
        assertEquals(listOf("abcdefabcdef", "123456123456"), recap.takes.map { it.spokenHash })
    }

    @Test
    fun openMigratesARealV1ProjectOnceAndKeepsAnExactBackup() {
        val dir = "/videos/Night.autoflow"
        val before = writeV1(dir)
        assertTrue(model.open(dir))
        assertMigratedRecap(dir)
        assertContentEquals(before, fs.read("$dir/${RecapMigration.BACKUP_FILE}"))
        val once = fs.read("$dir/project.json")
        assertTrue(model.open(dir))
        assertContentEquals(once, fs.read("$dir/project.json"), "second open is byte-identical")
    }

    @Test
    fun openRefusesAV1ProjectWhoseScriptIsMissingAndChangesNothing() {
        val dir = "/videos/Night.autoflow"
        val before = writeV1(dir, withScript = false)
        assertFalse(model.open(dir))
        assertNull(current.state.value)
        val message = model.state.value.message!!
        assertTrue("rs_0123456789ab" in message && "not changed" in message, message)
        assertContentEquals(before, fs.read("$dir/project.json"))
        assertFalse(fs.exists("$dir/${RecapMigration.BACKUP_FILE}"))
    }

    @Test
    fun corruptedMainWithRealV1BackupMustOpen() {
        val dir = "/videos/Night.autoflow"
        val before = writeV1(dir)
        fs.writeDurably("$dir/${RecapMigration.BACKUP_FILE}", before)
        fs.writeDurably("$dir/project.json", "{truncated".encodeToByteArray())
        assertTrue(model.open(dir), model.state.value.message)
        assertMigratedRecap(dir)
        assertEquals("{truncated", fs.read("$dir/${RecapMigration.DAMAGED_FILE}").decodeToString())
        assertContentEquals(before, fs.read("$dir/${RecapMigration.BACKUP_FILE}"))
        val once = fs.read("$dir/project.json")
        assertTrue(model.open(dir))
        assertContentEquals(once, fs.read("$dir/project.json"))
    }

    @Test
    fun missingMainWithRealV1BackupMustOpen() {
        val dir = "/videos/Night.autoflow"
        writeV1(dir)
        fs.atomicMove("$dir/project.json", "$dir/${RecapMigration.BACKUP_FILE}")
        assertTrue(model.open(dir), model.state.value.message)
        assertMigratedRecap(dir)
        assertFalse(fs.exists("$dir/${RecapMigration.DAMAGED_FILE}"))
    }

    @Test
    fun bothMainAndBackupInvalidFailsAndLeavesBothFiles() {
        val dir = "/videos/Night.autoflow"
        fs.createDirectories(dir)
        fs.writeDurably("$dir/project.json", "{main".encodeToByteArray())
        fs.writeDurably("$dir/${RecapMigration.BACKUP_FILE}", "{backup".encodeToByteArray())
        assertFalse(model.open(dir))
        assertTrue("nothing was changed" in model.state.value.message!!, model.state.value.message)
        assertEquals("{main", fs.read("$dir/project.json").decodeToString())
        assertEquals("{backup", fs.read("$dir/${RecapMigration.BACKUP_FILE}").decodeToString())
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

    private companion object {
        /** core/src/commonTest/resources/migration/recap-v1.json (UI tests cannot read core's test resources). */
        const val V1_PROJECT = """{
  "schemaVersion": 1, "id": "6d1e3b2a-9c4f-4e8a-b7d5-1f2a3c4b5d6e", "name": "Night Train", "mode": "MOVIE_RECAP",
  "createdAt": "2026-11-10T08:00:00Z", "updatedAt": "2026-11-12T09:30:00Z",
  "recap": {
    "rightsConfirmed": true,
    "movie": { "file": "source/movie.mp4", "sizeBytes": 734003200, "durationMs": 6912000, "width": 1280, "height": 720, "hasAudio": true },
    "targetMs": 300000,
    "scenes": [ { "startMs": 0, "endMs": 4200, "picked": true }, { "startMs": 4200, "endMs": 9000, "picked": true },
                { "startMs": 9000, "endMs": 12000, "picked": false } ],
    "sceneThreshold": 10, "subtitles": "source/subtitles.srt", "style": "COMMENTARY",
    "script": "rs_0123456789ab", "scriptApproved": true, "voiceSource": "TTS", "ttsVoice": "Kore",
    "takes": [ { "segment": 0, "audio": "au_0123456789ab", "source": "TTS", "durationMs": 3900, "textHash": "abcdefabcdef" },
               { "segment": 1, "audio": "au_ba9876543210", "source": "RECORDED", "durationMs": 4100, "textHash": "123456123456" },
               { "segment": 7, "audio": "au_cccccccccccc", "source": "TTS", "durationMs": 2000, "textHash": "000000000000" } ],
    "looks": ["ZOOM"], "movieVolume": 0.35, "burnCaptions": false
  }
}"""
    }
}
