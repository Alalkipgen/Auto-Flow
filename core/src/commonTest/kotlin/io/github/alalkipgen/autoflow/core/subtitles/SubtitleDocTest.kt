package io.github.alalkipgen.autoflow.core.subtitles

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.recap.Srt
import io.github.alalkipgen.autoflow.core.recap.SrtCue
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.TestClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class SubtitleDocTest {
    private val doc = SubtitleOps.fromCues(listOf(
        SrtCue(4_000, 6_000, "Second"),
        SrtCue(1_000, 3_000, "First line\nsecond row"),
        SrtCue(7_000, 9_000, "Third"),
    ), SubtitleDoc.SOURCE_SRT, lang = "en")

    private fun Edit.doc() = assertIs<Edit.Done>(this).doc
    private fun Edit.refusal() = assertIs<Edit.Refused>(this).reason

    @Test
    fun specExampleRoundTripsWithEveryKey() {
        val json = """{ "schemaVersion": 1, "source": "track:2", "derivedFrom": "st_9c1e0b7a44d2", "lang": "en", "nextId": 1285,
          "cues": [ { "id": "c1", "startMs": 723100, "endMs": 725800, "text": "We have to leave tonight.",
                      "textMy": "ဒီည ထွက်သွားရမယ်။", "myFrom": "5be0c2d1", "myEdited": false,
                      "spokenMy": null, "speaker": null } ] }"""
        val d = CoreJson.decodeFromString(SubtitleDoc.serializer(), json)
        assertEquals(ArtifactKind.SUBTITLES, d.derivedFrom?.kind)
        assertEquals(Json.parseToJsonElement(json), Json.parseToJsonElement(CoreJson.encodeToString(SubtitleDoc.serializer(), d)))
        assertEquals("st", ArtifactKind.SUBTITLES.code)
        assertEquals(ArtifactKind.SUBTITLES, ArtifactId("st_0123456789ab").kind)
    }

    @Test
    fun aVersionIsAnImmutableStArtifact() {
        val store = ArtifactStore(InMemoryFileSystem(), "/p", TestClock())
        val bytes = CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray()
        val a = assertIs<CoreResult.Ok<io.github.alalkipgen.autoflow.core.model.Artifact>>(store.put(bytes, ArtifactKind.SUBTITLES, "json")).value
        assertEquals("st_", a.id.value.take(3))
        assertEquals(a.id, assertIs<CoreResult.Ok<io.github.alalkipgen.autoflow.core.model.Artifact>>(store.put(bytes, ArtifactKind.SUBTITLES, "json")).value.id)
        val back = assertIs<CoreResult.Ok<ByteArray>>(store.readBytes(a.id)).value
        assertEquals(doc, CoreJson.decodeFromString(SubtitleDoc.serializer(), back.decodeToString()))
    }

    @Test
    fun fromCuesNumbersInTimeOrder() {
        assertEquals(listOf("c1" to "First line\nsecond row", "c2" to "Second", "c3" to "Third"), doc.cues.map { it.id to it.text })
        assertEquals(4, doc.nextId)
    }

    @Test
    fun splitKeepsIdsAndGivesTheSecondHalfTheNextId() {
        val byBreak = SubtitleOps.split(doc, "c1", 2_000).doc()
        assertEquals(listOf("c1", "c4", "c2", "c3"), byBreak.cues.map { it.id })
        assertEquals(listOf(1_000L to 2_000L, 2_000L to 3_000L), byBreak.cues.take(2).map { it.startMs to it.endMs })
        assertEquals(listOf("First line", "second row"), byBreak.cues.take(2).map { it.text })
        assertEquals(5, byBreak.nextId)
        assertEquals("Sec" to "ond", SubtitleOps.split(doc, "c2", 5_000, textCut = 3).doc().cues.let { it[1].text to it[2].text })
        assertEquals(EditRefusal.SplitNeedsTextCut, SubtitleOps.split(doc, "c2", 5_000).refusal())
        assertEquals(EditRefusal.SplitOutside, SubtitleOps.split(doc, "c2", 6_000, textCut = 2).refusal())
        assertEquals(EditRefusal.NoSuchLine, SubtitleOps.split(doc, "c9", 5_000).refusal())
    }

    @Test
    fun splitBurmeseOnlyWhereTheOwnerSays() {
        val my = doc.copy(cues = doc.cues.map { if (it.id == "c2") it.copy(textMy = "ဒုတိယ စာကြောင်း", myFrom = myHash(it.text)) else it })
        val kept = SubtitleOps.split(my, "c2", 5_000, textCut = 3).doc().cues
        assertEquals("ဒုတိယ စာကြောင်း", kept[1].textMy)
        assertNull(kept[2].textMy)
        val cut = SubtitleOps.split(my, "c2", 5_000, textCut = 3, myCut = 5).doc().cues
        assertEquals("ဒုတိယ" to "စာကြောင်း", cut[1].textMy to cut[2].textMy)
    }

    @Test
    fun mergeJoinsWithALineBreakAndKeepsTheFirstId() {
        val merged = SubtitleOps.merge(doc, "c2").doc()
        assertEquals(listOf("c1", "c2"), merged.cues.map { it.id })
        assertEquals(Triple(4_000L, 9_000L, "Second\nThird"), merged.cues[1].let { Triple(it.startMs, it.endMs, it.text) })
        assertEquals(EditRefusal.NoNextLine, SubtitleOps.merge(doc, "c3").refusal())

        val fresh = doc.copy(cues = doc.cues.map { it.copy(textMy = "my-" + it.id, myFrom = myHash(it.text)) })
        val both = SubtitleOps.merge(fresh, "c2").doc().cues[1]
        assertEquals("my-c2\nmy-c3" to myHash("Second\nThird"), both.textMy to both.myFrom)
        val stale = SubtitleOps.merge(fresh.copy(cues = fresh.cues.map { if (it.id == "c3") it.copy(myFrom = "00000000") else it }), "c2").doc().cues[1]
        assertEquals(myHash("Second"), stale.myFrom) // ≠ hash of the merged text → flagged "Original changed"
    }

    @Test
    fun insertDeleteAndTimes() {
        val inserted = SubtitleOps.insertAfter(doc, "c2", lengthMs = 5_000).doc()
        assertEquals(listOf("c1", "c2", "c4", "c3"), inserted.cues.map { it.id })
        assertEquals(6_000L to 7_000L, inserted.cues[2].let { it.startMs to it.endMs }) // stops at the next line
        assertEquals(0L to 1_000L, SubtitleOps.insertAfter(doc, null).doc().cues.first().let { it.startMs to it.endMs })
        assertEquals(listOf("c1", "c3"), SubtitleOps.delete(doc, "c2").doc().cues.map { it.id })
        assertEquals(listOf("c1", "c3", "c2"), SubtitleOps.setTimes(doc, "c2", 9_500, 10_000).doc().cues.map { it.id })
        assertEquals(EditRefusal.BadTimes, SubtitleOps.setTimes(doc, "c2", 5_000, 5_000).refusal())
        assertEquals(EditRefusal.BadTimes, SubtitleOps.setTimes(doc, "c2", -1, 5_000).refusal())
    }

    @Test
    fun typedTextKeepsStampsHonest() {
        val my = SubtitleOps.setTextMy(doc, "c2", "ဒုတိယ").doc().cues[1]
        assertEquals(Triple("ဒုတိယ", myHash("Second"), true), Triple(my.textMy, my.myFrom, my.myEdited))
        val cleared = SubtitleOps.setTextMy(doc, "c2", "  ").doc().cues[1]
        assertEquals(Triple(null, null, false), Triple(cleared.textMy, cleared.myFrom, cleared.myEdited))
        assertEquals(doc, SubtitleOps.setText(doc, "c2", "Second").doc())
        assertEquals("Second!", SubtitleOps.setText(doc, "c2", "Second!").doc().cues[1].text)
        assertEquals(EditRefusal.NoSuchLine, SubtitleOps.setText(doc, "c9", "x").refusal())
    }

    @Test
    fun shiftAllOrFromALineButNeverBeforeZero() {
        assertEquals(listOf(1_500L, 4_500L, 7_500L), SubtitleOps.shift(doc, 500).doc().cues.map { it.startMs })
        assertEquals(listOf(1_000L, 3_000L, 6_000L), SubtitleOps.shift(doc, -1_000, fromId = "c2").doc().cues.map { it.startMs })
        assertEquals(EditRefusal.ShiftBeforeStart(-1_000), SubtitleOps.shift(doc, -1_001).refusal())
        assertEquals(listOf(0L, 3_000L, 6_000L), SubtitleOps.shift(doc, -1_000).doc().cues.map { it.startMs })
    }

    @Test
    fun toSrtNumbersFromOneAndPicksTheText() {
        val my = doc.copy(cues = doc.cues.map { if (it.id == "c1") it.copy(textMy = "ပထမ") else it })
        assertEquals(listOf("ပထမ", "Second", "Third"), SubtitleOps.toSrt(my, TextChoice.BURMESE).map { it.text })
        assertEquals("First line\nsecond row\nပထမ", SubtitleOps.toSrt(my, TextChoice.BOTH).first().text)
        val srt = Srt.write(SubtitleOps.toSrt(doc, TextChoice.ORIGINAL))
        assertEquals("1\n00:00:01,000 --> 00:00:03,000\nFirst line\nsecond row\n\n", srt.substringBefore("2\n"))
        assertEquals(SubtitleOps.toSrt(doc, TextChoice.ORIGINAL), (SrtImport.parse(srt) as SrtImportResult.Ok).cues)
    }
}
