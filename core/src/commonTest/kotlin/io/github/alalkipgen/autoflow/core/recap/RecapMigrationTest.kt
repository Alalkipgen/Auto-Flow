package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.readTestResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Migration 1 → 2 (studio-v2 §2, D-36). */
class RecapMigrationTest {
    private val v1 = Json.parseToJsonElement(readTestResource("migration/recap-v1.json")).jsonObject
    private val script = RecapScript(
        source = RecapScript.SOURCE_GEMINI,
        style = RecapStyle.COMMENTARY,
        segments = listOf(RecapSegment(0, 4200, "ညမှာ ရထား ထွက်တယ်။"), RecapSegment(4200, 9000, "သူ မနိုးဘူး။")),
    )
    private val storyId = ArtifactId("rs_feedfacecafe")

    private fun run(project: JsonObject = v1, written: MutableList<RecapStory> = mutableListOf()): JsonObject =
        RecapMigration.migrate(
            project,
            readScript = { if (it == ArtifactId("rs_0123456789ab")) script else null },
            writeStory = { written += it; storyId },
            burmeseTtsModel = "gemini-3.8-flash-tts",
        )

    private fun recapOf(project: JsonObject) = CoreJson.decodeFromJsonElement(RecapV2.serializer(), project["recap"]!!)

    @Test
    fun scriptBecomesOneBlockPerSegment() {
        val written = mutableListOf<RecapStory>()
        val r = recapOf(run(written = written))
        val story = written.single()
        assertEquals(2, story.schemaVersion)
        assertEquals(RecapStyle.COMMENTARY, story.style)
        assertEquals(listOf(listOf(RecapClip(0, 4200)), listOf(RecapClip(4200, 9000))), story.blocks.map { it.clips })
        assertEquals(script.segments.map { it.textMy }, story.blocks.map { it.textMy })
        assertTrue(story.blocks.all { it.speakMy == null })
        assertEquals(listOf(migratedBlockId(0, 0, 4200), migratedBlockId(1, 4200, 9000)), story.blocks.map { it.id })
        assertTrue(story.blocks.all { Regex("b_[0-9a-f]{8}").matches(it.id) })
        assertEquals(storyId, r.story)
    }

    @Test
    fun takesLandOnTheRightBlockAndKeepTheirHash() {
        val r = recapOf(run())
        val ids = RecapMigration.storyFrom(script).blocks.map { it.id }
        assertEquals(listOf(ids[0], ids[1]), r.takes.map { it.block }) // segment 7 has no block: dropped
        assertEquals(listOf("abcdefabcdef", "123456123456"), r.takes.map { it.spokenHash })
        assertEquals(listOf(TakeSource.TTS, TakeSource.RECORDED), r.takes.map { it.source })
        assertTrue(r.takes.all { it.model == null && it.voice == null && it.provider == null }) // voice unknown
    }

    @Test
    fun approvalTtsLockAndSettingsAreKept() {
        val r = recapOf(run())
        assertTrue(r.storyApproved)
        assertEquals(VoiceLock("gemini", "gemini-3.8-flash-tts", "Kore", "my"), r.voiceLock)
        assertEquals("source/subtitles.srt", r.sources.subtitles)
        assertEquals(0.35, r.audio.movieVolume)
        assertFalse(r.captions.burn)
        assertEquals(listOf(Look.ZOOM), r.looks)
        assertEquals(3, r.scenes.size)
        assertEquals(720, r.movie!!.height)
        assertEquals(2, run()["schemaVersion"]!!.jsonPrimitive.int)
    }

    @Test
    fun otherProjectKeysPassThrough() {
        val out = run()
        for (key in listOf("id", "name", "mode", "createdAt", "updatedAt")) assertEquals(v1[key], out[key])
    }

    @Test
    fun recordedProjectGetsNoLock() {
        val recap = v1["recap"]!!.jsonObject.toMutableMap()
        recap["voiceSource"] = Json.parseToJsonElement("\"RECORDED\"")
        val project = JsonObject(v1.toMutableMap().apply { put("recap", JsonObject(recap)) })
        assertNull(recapOf(run(project)).voiceLock)
    }

    @Test
    fun missingScriptGivesNoStoryAndNoApproval() {
        val out = RecapMigration.migrate(v1, readScript = { null }, writeStory = { error("not called") }, burmeseTtsModel = null)
        val r = recapOf(out)
        assertNull(r.story)
        assertFalse(r.storyApproved)
        assertTrue(r.takes.isEmpty())
        assertNull(r.voiceLock) // no Burmese TTS model known
    }

    @Test
    fun runningOnV2ChangesNothing() {
        val once = run()
        val written = mutableListOf<RecapStory>()
        assertSame(once, run(once, written))
        assertTrue(written.isEmpty())
    }

    @Test
    fun backupIsWrittenOnceAndNeverOverwritten() {
        val fs = InMemoryFileSystem()
        fs.createDirectories("/p")
        fs.writeDurably("/p/project.json", "v1".encodeToByteArray())
        RecapMigration.backup(fs, "/p")
        assertEquals("v1", fs.read("/p/${RecapMigration.BACKUP_FILE}").decodeToString())
        fs.writeDurably("/p/project.json", "v2".encodeToByteArray())
        RecapMigration.backup(fs, "/p")
        assertEquals("v1", fs.read("/p/${RecapMigration.BACKUP_FILE}").decodeToString())
        assertFalse(fs.exists("/p/${RecapMigration.BACKUP_FILE}.tmp"))
    }
}
