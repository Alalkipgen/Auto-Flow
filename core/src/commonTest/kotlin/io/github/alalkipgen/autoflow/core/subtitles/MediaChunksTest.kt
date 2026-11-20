package io.github.alalkipgen.autoflow.core.subtitles

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.core.recap.TranscriptLine
import io.github.alalkipgen.autoflow.core.recap.TranscriptRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S-5 (spec §9 "Chunk cut points"): chunk plan, item keys, joining overlapping chunks; the new job kind on disk. */
class MediaChunksTest {
    private val max = 300_000L
    private val window = 30_000L
    private val overlap = 2_000L

    private fun plan(duration: Long, vararg pauses: Pair<Long, Long>) =
        MediaChunks.plan(duration, pauses.map { MediaChunks.Pause(it.first, it.second) }, max, window, overlap)

    @Test fun shortVideoIsOneChunkAndEmptyIsNone() {
        assertEquals(listOf(MediaChunk(0, 120_000)), plan(120_000))
        assertEquals(listOf(MediaChunk(0, 300_000)), plan(300_000))
        assertEquals(emptyList(), plan(0))
    }

    @Test fun cutsAtTheLastPauseBeforeEachMarkWithinTheWindow() {
        // 12 min: pauses at 4:40 (in the window) and 4:50 (later, chosen); 2nd mark 9:50 has a pause at 9:35.
        val chunks = plan(720_000, 280_000L to 281_000L, 290_000L to 290_800L, 575_000L to 575_400L)
        assertEquals(listOf(MediaChunk(0, 290_400), MediaChunk(290_400, 575_200), MediaChunk(575_200, 720_000)), chunks)
        assertTrue(chunks.all { it.durationMs <= max })
        // Contiguous, no overlap when cut at a pause.
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
    }

    @Test fun aPauseSpanningTheMarkCutsAtTheMark() {
        assertEquals(listOf(MediaChunk(0, 300_000), MediaChunk(300_000, 400_000)), plan(400_000, 298_000L to 310_000L))
    }

    @Test fun noPauseInTheWindowIsAHardCutWithOverlap() {
        // Pause too early (before mark − window) and after the mark are ignored.
        val chunks = plan(650_000, 100_000L to 101_000L, 305_000L to 306_000L)
        assertEquals(listOf(MediaChunk(0, 300_000), MediaChunk(298_000, 598_000), MediaChunk(596_000, 650_000)), chunks)
        assertTrue(chunks.all { it.durationMs <= max })
    }

    @Test fun itemKeysRoundTripAndBadKeysAreRefused() {
        val c = MediaChunk(298_000, 598_000)
        assertEquals("k298000-598000", c.item)
        assertEquals("k298000.json", c.file)
        assertEquals(c, MediaChunk.parse(c.item))
        listOf("298000-598000", "k12", "k5-5", "k9-3", "kx-4", "k1-2-3").forEach { assertNull(MediaChunk.parse(it), it) }
    }

    @Test fun mergeKeepsLinesInTheirChunkAndDropsBlanks() {
        val a = MediaChunk(0, 10_000); val b = MediaChunk(10_000, 20_000)
        val merged = MediaChunks.merge(listOf(
            b to listOf(TranscriptLine(12_000, 14_000, " second ")),
            a to listOf(TranscriptLine(1_000, 3_000, "first"), TranscriptLine(4_000, 5_000, "  "), TranscriptLine(9_000, 12_500, "edge")),
        ))
        assertEquals(listOf(TranscriptLine(1_000, 3_000, "first"), TranscriptLine(9_000, 10_000, "edge"), TranscriptLine(12_000, 14_000, "second")), merged)
    }

    @Test fun overlapHearsAWordOnceKeepingTheWholerLine() {
        val a = MediaChunk(0, 300_000); val b = MediaChunk(298_000, 400_000)
        val merged = MediaChunks.merge(listOf(
            a to listOf(TranscriptLine(290_000, 295_000, "We have"), TranscriptLine(298_500, 300_000, "to le")),
            b to listOf(TranscriptLine(298_200, 301_000, "to leave tonight."), TranscriptLine(303_000, 305_000, "Now.")),
        ))
        assertEquals(listOf(
            TranscriptLine(290_000, 295_000, "We have"),
            TranscriptLine(298_200, 301_000, "to leave tonight."),
            TranscriptLine(303_000, 305_000, "Now."),
        ), merged)
        // A shorter repeat in the next chunk is dropped; an end past the next start is moved back.
        val again = MediaChunks.merge(listOf(
            a to listOf(TranscriptLine(298_100, 299_900, "to leave tonight")),
            b to listOf(TranscriptLine(298_300, 299_000, "leave"), TranscriptLine(299_500, 302_000, "Go.")),
        ))
        assertEquals(listOf(TranscriptLine(298_100, 299_500, "to leave tonight"), TranscriptLine(299_500, 302_000, "Go.")), again)
    }

    @Test fun mediaTranscribeJobRoundTripsAndAnOlderAppSeesAnUnknownKindAsDamaged() {
        val fs = InMemoryFileSystem()
        val store = AiJobStore(fs, "/jobs")
        val job = AiJob("mt-p-1", AiJobKind.MEDIA_TRANSCRIBE, "/p/a.autoflow", listOf("k0-300000", "k300000-420000"))
        store.save(job)
        assertEquals(job, store.load(job.id))
        assertTrue(fs.read("/jobs/mt-p-1.json").decodeToString().contains("\"MEDIA_TRANSCRIBE\""))
        // A kind this build does not know (as an older build sees MEDIA_TRANSCRIBE): kept untouched, not loaded.
        val alien = fs.read("/jobs/mt-p-1.json").decodeToString().replace("\"MEDIA_TRANSCRIBE\"", "\"FUTURE_KIND\"").encodeToByteArray()
        fs.writeDurably("/jobs/x.json", alien)
        assertIs<AiJobStore.Read.Damaged>(store.read("x"))
        assertEquals(listOf(job), store.loadAll())
        assertEquals(alien.toList(), fs.read("/jobs/x.json").toList())
        val request = MediaTranscribeRequest("9-60000", "my", listOf(TranscriptRange(0, 300_000), TranscriptRange(298_000, 420_000)))
        val back = CoreJson.decodeFromString(MediaTranscribeRequest.serializer(), CoreJson.encodeToString(MediaTranscribeRequest.serializer(), request))
        assertEquals(listOf(MediaChunk(0, 300_000), MediaChunk(298_000, 420_000)), back.parts)
    }
}
