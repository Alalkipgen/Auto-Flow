package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.SceneSpan
import io.github.alalkipgen.autoflow.ai.TranscribeProvider
import io.github.alalkipgen.autoflow.ai.Transcript as AiTranscript
import io.github.alalkipgen.autoflow.ai.TranscriptSegment
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.core.recap.RpmPacer
import io.github.alalkipgen.autoflow.core.recap.Transcript
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.MediaChunk
import io.github.alalkipgen.autoflow.core.subtitles.MediaChunks
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.ui.jobs.DesktopAiJobRunner
import io.github.alalkipgen.autoflow.ui.jobs.RecapJobProcessor
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.recap.SceneAudioCutter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * S-5a with a fake Gemini and a fake sound cutter: the chunk plan (pause cut, hard cut + overlap), a MEDIA_TRANSCRIBE
 * job of the Subtitles project folder, one request per chunk with only that chunk's sound, lines in source time, and
 * at the end a `tx` + a new `st` after the current version. Busy pauses and Resume sends only the chunks left; a new
 * source video is refused; nothing is sent without Gemini.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoTranscriberTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), CurrentProject())
    private val jobs = AiJobStore(fs, "/files/ai-jobs")
    private val dir = "/p/Film.autoflow"
    private val video = SourceVideo("source/movie.mp4", 9, 1_500_000, 1920, 800, 25.0)

    /** Every request: its scenes, the sound it carried, its language hint. */
    private val sent = mutableListOf<Triple<List<SceneSpan>, String, String?>>()
    private val answers = mutableMapOf<Int, AiResult<List<List<TranscriptSegment>>>>()
    private val provider = object : TranscribeProvider {
        override suspend fun transcribe(audio: ByteArray, lang: String, mime: String, lines: List<String>): AiResult<AiTranscript> = error("not used")
        override suspend fun transcribeScenes(audio: ByteArray, mime: String, scenes: List<SceneSpan>, lang: String?): AiResult<List<List<TranscriptSegment>>> {
            sent += Triple(scenes, audio.decodeToString(), lang)
            val n = sent.size
            return answers[n] ?: AiResult.Ok(listOf(listOf(TranscriptSegment(1_000, 2_500, "line of request $n"))), Usage("gemini-test"))
        }
    }
    private val cutter = SceneAudioCutter { movie, chunk, out, _ ->
        fs.writeDurably(out, "$movie|${chunk.ranges.joinToString(",") { "${it.inMs}-${it.outMs}" }}".encodeToByteArray()); null
    }
    /** One pause at 9:50 (in the window before the 10-minute mark), none before the next mark. */
    private val pauses: suspend (String, String) -> List<MediaChunks.Pause>? = { _, _ -> listOf(MediaChunks.Pause(590_000, 591_000)) }

    private val old: ArtifactId
    init {
        val p = (store.create(dir, "Film", Mode.SUBTITLES) as CoreResult.Ok).value
        val doc = SubtitleDoc(source = SubtitleDoc.SOURCE_SRT, nextId = 2, cues = listOf(SubtitleCue("c1", 1_000, 2_000, "Old line")))
        old = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(), ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
        store.save(dir, p.copy(subtitles = SubtitlesTool(video = video, current = old)))
    }

    private fun transcriber(gemini: TranscribeProvider? = provider) = VideoTranscriber(session, fs, null, gemini, cutter = cutter, pauses = pauses)

    private fun TestScope.runner(t: VideoTranscriber): Pair<CoroutineScope, DesktopAiJobRunner> {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        return scope to DesktopAiJobRunner(jobs, scope, RecapJobProcessor({ null }, { t }), pacer = { RpmPacer(1_000) })
    }

    private fun tool(): SubtitlesTool = (store.open(dir) as CoreResult.Ok).value.subtitles!!
    private fun doc(id: ArtifactId): SubtitleDoc =
        CoreJson.decodeFromString(SubtitleDoc.serializer(), (ArtifactStore(fs, dir).readBytes(id) as CoreResult.Ok).value.decodeToString())

    private val chunks = listOf(MediaChunk(0, 590_500), MediaChunk(590_500, 1_190_500), MediaChunk(1_188_500, 1_500_000))

    @Test fun planCutsAtThePauseThenHardCutsWithOverlap() = runTest {
        val plan = transcriber().plan(dir, video)
        assertEquals(chunks, plan.chunks)
        assertEquals(3, plan.requests)
        assertEquals(1_502_000, plan.audioMs, "the 2-second overlap is sent twice")
        assertTrue(sent.isEmpty(), "planning sends nothing")
    }

    @Test fun theJobSendsEachChunkOnceAndMakesANewVersionAfterTheCurrentOne() = runTest {
        val t = transcriber()
        val (scope, runner) = runner(t)
        val id = t.enqueue(runner, dir, t.plan(dir, video), "my")
        advanceUntilIdle()
        val job = jobs.load(id)!!
        assertEquals(AiJobKind.MEDIA_TRANSCRIBE, job.kind)
        assertEquals(dir, job.project)
        assertEquals(AiJobState.DONE, job.state, job.error)
        assertEquals(chunks.map { "$dir/source/movie.mp4|${it.startMs}-${it.endMs}" }, sent.map { it.second }, "only each chunk's sound")
        assertTrue(sent.all { it.first == listOf(SceneSpan(0, it.first.single().endMs)) && it.third == "my" })
        assertEquals(listOf(590_500L, 600_000L, 311_500L), sent.map { it.first.single().endMs })

        val now = tool().current!!
        assertTrue(now != old)
        val st = doc(now)
        assertEquals(old, st.derivedFrom, "the new version comes after the current one (Undo-able, nothing overwritten)")
        assertTrue(st.source.startsWith("tx:"), st.source)
        assertEquals(listOf(1_000L to 2_500L, 591_500L to 593_000L, 1_189_500L to 1_191_000L), st.cues.map { it.startMs to it.endMs }, "source time")
        assertEquals(listOf("line of request 1", "line of request 2", "line of request 3"), st.cues.map { it.text })
        val tx = CoreJson.decodeFromString(Transcript.serializer(),
            (ArtifactStore(fs, dir).readBytes(ArtifactId(st.source.removePrefix("tx:"))) as CoreResult.Ok).value.decodeToString())
        assertEquals(Transcript.SOURCE_GEMINI_MEDIA, tx.source)
        assertEquals("my", tx.lang)
        assertEquals("Old line", doc(old).cues.single().text, "the old version is untouched")
        // Running the last item again (e.g. a restart after it finished) makes no second version.
        t.runItem(dir, id, chunks.last().item)
        assertEquals(now, tool().current)
        assertEquals(3, sent.size)
        scope.cancel()
    }

    @Test fun busyPausesAndResumeSendsOnlyTheChunksLeftEvenAfterARestart() = runTest {
        answers[2] = AiResult.Failed("http_503", "overloaded")
        val t = transcriber()
        val (scope, runner) = runner(t)
        val id = t.enqueue(runner, dir, t.plan(dir, video), VideoTranscriber.AUTO)
        advanceUntilIdle()
        assertEquals(AiJobState.PAUSED, jobs.load(id)!!.state)
        assertTrue(sent.all { it.third == null }, "auto = no language hint")
        assertTrue(t.isItemDone(dir, id, chunks[0].item))
        assertFalse(t.isItemDone(dir, id, chunks[1].item))
        assertEquals(old, tool().current, "nothing changes before every chunk is in")
        scope.cancel()

        // A new app (new transcriber and runner) resumes: chunk 1 is not sent again.
        val again = transcriber()
        val (scope2, runner2) = runner(again)
        runner2.enqueue(jobs.load(id)!!)
        advanceUntilIdle()
        assertEquals(AiJobState.DONE, jobs.load(id)!!.state)
        assertEquals(listOf(chunks[0], chunks[1], chunks[1], chunks[2]).map { "$dir/source/movie.mp4|${it.startMs}-${it.endMs}" }, sent.map { it.second })
        assertEquals(3, doc(tool().current!!).cues.size)
        assertEquals(Transcript.LANG_UNKNOWN, CoreJson.decodeFromString(Transcript.serializer(),
            (ArtifactStore(fs, dir).readBytes(ArtifactId(doc(tool().current!!).source.removePrefix("tx:"))) as CoreResult.Ok).value.decodeToString()).lang)
        scope2.cancel()
    }

    @Test fun aNewSourceVideoOrNoGeminiFailsWithoutAChange() = runTest {
        val t = transcriber()
        val (scope, runner) = runner(t)
        val id = t.enqueue(runner, dir, VideoTranscriber.Plan(chunks.take(1)), "en")
        // The source is replaced before the job runs.
        val p = (store.open(dir) as CoreResult.Ok).value
        store.save(dir, p.copy(subtitles = p.subtitles!!.copy(video = video.copy(sizeBytes = 10))))
        advanceUntilIdle()
        val failed = jobs.load(id)!!
        assertEquals(AiJobState.FAILED, failed.state)
        assertEquals(VideoTranscriber.VIDEO_CHANGED, failed.error)
        assertTrue(sent.isEmpty())
        assertEquals(old, tool().current)
        scope.cancel()

        val none = transcriber(gemini = null)
        assertFalse(none.available)
        val (scope2, runner2) = runner(none)
        val id2 = none.enqueue(runner2, dir, VideoTranscriber.Plan(chunks.take(1)), "en")
        advanceUntilIdle()
        assertEquals(VideoTranscriber.NO_GEMINI, assertNotNull(jobs.load(id2)).error)
        assertTrue(sent.isEmpty())
        scope2.cancel()
    }
}
