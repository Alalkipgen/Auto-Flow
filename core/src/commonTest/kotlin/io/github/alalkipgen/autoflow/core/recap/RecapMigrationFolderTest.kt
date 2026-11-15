package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.readTestResource
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Migration and recovery on a real project folder with the true v1 fixture (audit F02 / F13): nothing is committed
 * before every referenced file was read, the v1 backup is exact, and a second open changes nothing.
 */
class RecapMigrationFolderTest {
    private val fs = InMemoryFileSystem().apply { createDirectories("/p/artifacts") }
    private val dir = "/p"
    private val main = "/p/project.json"
    private val backup = "/p/${RecapMigration.BACKUP_FILE}"
    private val v1 = Json.parseToJsonElement(readTestResource("migration/recap-v1.json")).jsonObject
    private val script = RecapScript(
        source = RecapScript.SOURCE_GEMINI,
        style = RecapStyle.COMMENTARY,
        segments = listOf(RecapSegment(0, 4200, "ညမှာ ရထား ထွက်တယ်။"), RecapSegment(4200, 9000, "သူ မနိုးဘူး။")),
    )
    private val artifacts = ArtifactStore(fs, dir)

    private fun put(bytes: ByteArray, kind: ArtifactKind = ArtifactKind.RECAP_SCRIPT, ext: String = "json"): ArtifactId =
        (artifacts.put(bytes, kind, ext) as CoreResult.Ok).value.id

    /** Writes the v1 fixture with its script stored as a real `rs` artifact; [edit] changes the recap first. */
    private fun writeV1(scriptId: ArtifactId? = put(CoreJson.encodeToString(RecapScript.serializer(), script).encodeToByteArray()), edit: MutableMap<String, JsonElement>.() -> Unit = {}): ByteArray {
        val recap = v1["recap"]!!.jsonObject.toMutableMap()
        if (scriptId == null) recap.remove("script") else recap["script"] = JsonPrimitive(scriptId.value)
        recap.edit()
        val bytes = JsonObject(v1.toMutableMap().apply { put("recap", JsonObject(recap)) }).toString().encodeToByteArray()
        fs.writeDurably(main, bytes)
        return bytes
    }

    private fun openMain(): Project = (ProjectStore(fs).open(dir) as CoreResult.Ok).value

    private fun storyOf(p: Project): RecapStory =
        CoreJson.decodeFromString(RecapStory.serializer(), (artifacts.readBytes(p.recap!!.story!!) as CoreResult.Ok).value.decodeToString())

    private fun artifactFiles() = fs.list("/p/artifacts").sorted()

    @Test
    fun validMigrationBacksUpKeepsApprovalMappingAndIsIdempotent() {
        val orphanAudio = put(ByteArray(64) { 1 }, ArtifactKind.AUDIO, "wav")
        val before = writeV1 {
            this["takes"] = Json.parseToJsonElement(
                """[{"segment":0,"audio":"au_0123456789ab","source":"TTS","durationMs":3900,"textHash":"abcdefabcdef"},
                    {"segment":1,"audio":"au_ba9876543210","source":"RECORDED","durationMs":4100,"textHash":"123456123456"},
                    {"segment":7,"audio":"${orphanAudio.value}","source":"TTS","durationMs":2000,"textHash":"000000000000"}]""",
            )
        }
        assertTrue(RecapMigration.migrateFolder(fs, dir, "gemini-3.8-flash-tts"))
        assertContentEquals(before, fs.read(backup), "the backup is the exact original v1 file")
        val p = openMain()
        assertEquals(2, p.schemaVersion)
        val recap = p.recap!!
        assertTrue(recap.storyApproved)
        val story = storyOf(p)
        assertEquals(story.blocks.map { it.id }, recap.takes.map { it.block }, "takes land on the blocks of their segments")
        assertEquals(listOf("abcdefabcdef", "123456123456"), recap.takes.map { it.spokenHash })
        assertTrue(artifacts.exists(orphanAudio), "the orphan take (segment 7) is dropped from the list, its audio stays")

        val once = fs.read(main)
        val filesOnce = artifactFiles()
        assertFalse(RecapMigration.migrateFolder(fs, dir, "gemini-3.8-flash-tts"), "second open: nothing to do")
        assertContentEquals(once, fs.read(main))
        assertContentEquals(before, fs.read(backup))
        assertEquals(filesOnce, artifactFiles())
    }

    @Test
    fun migrationPreservesSavedV1Work() {
        writeV1 {
            this["subtitleTrack"] = JsonPrimitive(3)
            this["transcript"] = JsonPrimitive("tx_111111111111")
            this["fit"] = buildJsonObject { put("0", "FREEZE"); put("1", "SPEED"); put("7", "NEXT_RANGE") }
            this["captionStyle"] = buildJsonObject { put("colour", "#FF00FF"); put("box", true) }
            this["publishKit"] = buildJsonObject { put("title", "Saved title"); put("description", "Saved description") }
            this["register"] = JsonPrimitive("WRITTEN")
            this["quick"] = buildJsonObject { put("stage", "REVIEW"); put("targetMs", 60_000) }
            this["quality"] = JsonPrimitive("1080")
            this["visual"] = buildJsonObject { put("background", "COLOUR"); put("backgroundColour", "#112233") }
        }
        RecapMigration.migrateFolder(fs, dir, null)
        val recap = openMain().recap!!
        val blocks = storyOf(openMain()).blocks
        assertEquals(3, recap.sources.subtitleTrack)
        assertEquals(ArtifactId("tx_111111111111"), recap.sources.transcript)
        assertEquals("source/subtitles.srt", recap.sources.subtitles)
        assertEquals(mapOf(blocks[0].id to "FREEZE", blocks[1].id to "SPEED"), recap.fit, "segment index → block id; no block = dropped")
        assertEquals("#FF00FF", recap.captionStyle.colour)
        assertTrue(recap.captionStyle.box)
        assertEquals(PublishKit(title = "Saved title", description = "Saved description"), recap.publishKit)
        assertEquals(RecapRegister.WRITTEN, storyOf(openMain()).register)
        assertEquals(QuickRecap(stage = QuickRecapStage.REVIEW, targetMs = 60_000), CoreJson.decodeFromJsonElement(QuickRecap.serializer(), recap.quick!!))
        assertEquals(ExportQuality.P1080, recap.quality)
        assertEquals(FrameBackground.COLOUR to "#112233", recap.visual.background to recap.visual.backgroundColour)
        assertEquals(listOf(Look.ZOOM), recap.looks)
        assertEquals(0.35, recap.audio.movieVolume)
    }

    @Test
    fun migrationMustNotCommitWhenReferencedScriptIsMissing() {
        val before = writeV1(scriptId = ArtifactId("rs_0123456789ab")) // the fixture's id; no such artifact
        val files = artifactFiles()
        val e = assertFailsWith<MigrationException> { RecapMigration.migrateFolder(fs, dir, "gemini-3.8-flash-tts") }
        assertTrue("rs_0123456789ab" in e.message!!, e.message)
        assertContentEquals(before, fs.read(main))
        assertFalse(fs.exists(backup), "no backup either: nothing was started")
        assertEquals(files, artifactFiles(), "no story artifact written")
    }

    @Test
    fun migrationMustNotCommitWhenScriptJsonIsCorrupted() {
        val broken = put("""{"source":"gemini","segments":[{"startMs":0,"en""".encodeToByteArray())
        val before = writeV1(scriptId = broken)
        val files = artifactFiles()
        val e = assertFailsWith<MigrationException> { RecapMigration.migrateFolder(fs, dir, null) }
        assertTrue("damaged" in e.message!!, e.message)
        assertContentEquals(before, fs.read(main))
        assertFalse(fs.exists(backup))
        assertEquals(files, artifactFiles())

        // A damaged recap itself (not decodable as v1) is refused the same way.
        val bad = writeV1 { this["takes"] = JsonPrimitive("not a list") }
        assertFailsWith<MigrationException> { RecapMigration.migrateFolder(fs, dir, null) }
        assertContentEquals(bad, fs.read(main))
    }

    @Test
    fun absentScriptIsOptionalAndMigratesWithoutAStory() {
        writeV1(scriptId = null)
        assertTrue(RecapMigration.migrateFolder(fs, dir, null))
        val recap = openMain().recap!!
        assertNull(recap.story)
        assertFalse(recap.storyApproved)
        assertTrue(recap.takes.isEmpty())
    }

    @Test
    fun corruptedMainWithRealV1BackupMustOpen() {
        val before = writeV1()
        fs.writeDurably(backup, before)
        fs.writeDurably(main, "{truncated".encodeToByteArray())
        val r = assertIs<RecapMigration.Recovery.Recovered>(RecapMigration.recoverFromBackup(fs, dir, "gemini-3.8-flash-tts"))
        assertEquals(RecapMigration.DAMAGED_FILE, r.kept)
        assertEquals("{truncated", fs.read("/p/${RecapMigration.DAMAGED_FILE}").decodeToString())
        assertContentEquals(before, fs.read(backup), "the only good backup is untouched")
        val p = openMain()
        assertEquals(r.project, p)
        assertTrue(p.recap!!.storyApproved)
        assertEquals(storyOf(p).blocks.map { it.id }, p.recap!!.takes.map { it.block })
        val once = fs.read(main)
        assertFalse(RecapMigration.migrateFolder(fs, dir, "gemini-3.8-flash-tts"))
        assertContentEquals(once, fs.read(main), "second open is byte-stable")

        // Damaged again: the first kept copy is not overwritten.
        fs.writeDurably(main, "{again".encodeToByteArray())
        assertIs<RecapMigration.Recovery.Recovered>(RecapMigration.recoverFromBackup(fs, dir, "gemini-3.8-flash-tts"))
        assertEquals("{truncated", fs.read("/p/${RecapMigration.DAMAGED_FILE}").decodeToString())
        assertEquals("{again", fs.read("/p/project.damaged-2.json").decodeToString())
    }

    @Test
    fun missingMainWithRealV1BackupMustOpen() {
        val before = writeV1()
        fs.atomicMove(main, backup)
        val r = assertIs<RecapMigration.Recovery.Recovered>(RecapMigration.recoverFromBackup(fs, dir, null))
        assertNull(r.kept)
        val p = openMain()
        assertEquals(2, p.schemaVersion)
        assertEquals(2, p.recap!!.takes.size)
        assertTrue(p.recap!!.storyApproved)
        assertContentEquals(before, fs.read(backup))
    }

    @Test
    fun bothInvalidLeavesEvidenceAndSaysWhy() {
        fs.writeDurably(main, "{main".encodeToByteArray())
        fs.writeDurably(backup, "{backup".encodeToByteArray())
        val r = assertIs<RecapMigration.Recovery.Failed>(RecapMigration.recoverFromBackup(fs, dir, null))
        assertTrue("damaged" in r.reason, r.reason)
        assertEquals("{main", fs.read(main).decodeToString())
        assertEquals("{backup", fs.read(backup).decodeToString())
        assertFalse(fs.exists("/p/${RecapMigration.DAMAGED_FILE}"))

        // A v1 backup whose script is gone cannot be recovered losslessly either.
        val v1Bytes = writeV1(scriptId = ArtifactId("rs_0123456789ab"))
        fs.writeDurably(backup, v1Bytes)
        fs.writeDurably(main, "{main".encodeToByteArray())
        val missing = assertIs<RecapMigration.Recovery.Failed>(RecapMigration.recoverFromBackup(fs, dir, null))
        assertTrue("rs_0123456789ab" in missing.reason, missing.reason)
        assertEquals("{main", fs.read(main).decodeToString())
        assertContentEquals(v1Bytes, fs.read(backup))

        assertEquals(RecapMigration.Recovery.NoBackup, RecapMigration.recoverFromBackup(InMemoryFileSystem().apply { createDirectories("/q") }, "/q", null))
    }
}
