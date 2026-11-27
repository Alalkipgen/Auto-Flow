package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.TtsCapabilities
import io.github.alalkipgen.autoflow.ai.TtsProvider
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.BlockTake
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.LexiconEntry
import io.github.alalkipgen.autoflow.core.recap.RecapAudio
import io.github.alalkipgen.autoflow.core.recap.RecapBlock
import io.github.alalkipgen.autoflow.core.recap.RecapClip
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.RecapStory
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.SpokenText
import io.github.alalkipgen.autoflow.core.recap.TakeSource
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.VoiceRecorder
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Audit F03 / F06 / F10: a voice take goes to the project and block it was made for, even when the user opens
 * another project before the answer comes; new takes are added to the block's history; TTS speaks the speak-as text
 * with the lexicon and the take's hash, provenance and staleness come from exactly that text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecapVoiceTargetTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val now = Instant.parse("2026-11-15T10:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = this@RecapVoiceTargetTest.now
    }

    /** A mono 8 kHz 16-bit WAV of [ms] milliseconds (different lengths give different bytes). */
    private fun wav(ms: Int = 1000): ByteArray {
        val samples = 8 * ms
        return java.io.ByteArrayOutputStream().apply {
            fun i(v: Int) { write(v and 255); write(v shr 8 and 255); write(v shr 16 and 255); write(v shr 24 and 255) }
            fun s(v: Int) { write(v and 255); write(v shr 8 and 255) }
            write("RIFF".toByteArray()); i(36 + samples * 2); write("WAVE".toByteArray())
            write("fmt ".toByteArray()); i(16); s(1); s(1); i(8000); i(16000); s(2); s(16)
            write("data".toByteArray()); i(samples * 2); write(ByteArray(samples * 2))
        }.toByteArray()
    }

    private class Call(val text: String, val lockedModel: String?)

    private fun tts(block: suspend (Call) -> AiResult<ByteArray>) = object : TtsProvider {
        override val capabilities = TtsCapabilities(burmese = true)
        override suspend fun speak(text: String, voice: VoiceRef, lang: String, style: String?, lockedModel: String?) =
            block(Call(text, lockedModel))
    }

    private fun model(scope: CoroutineScope, provider: TtsProvider? = null, lexicon: Lexicon = Lexicon.EMPTY, recorder: VoiceRecorder? = null) =
        RecapModel(session, fs, null, null, scope, tts = provider, recorder = recorder, lexicon = lexicon, clock = clock)

    private fun putStory(dir: String, story: RecapStory): ArtifactId =
        (ArtifactStore(fs, dir).put(CoreJson.encodeToString(RecapStory.serializer(), story).encodeToByteArray(), ArtifactKind.RECAP_SCRIPT, "json") as CoreResult.Ok).value.id

    /** Creates project [dir] with one approved block, saves it and opens it (as the Dashboard does: from disk). */
    private fun create(dir: String, title: String, spoken: String? = null, takes: List<BlockTake> = emptyList()): RecapBlock {
        val p = (store.create(dir, title, Mode.MOVIE_RECAP) as CoreResult.Ok).value
        val block = RecapBlock("${title}_block", listOf(RecapClip(0, 4200)), "$title written", spoken)
        val recap = RecapV2(
            rightsConfirmed = true, movie = RecapMovie("source/movie.mp4", 1, 20_000, 320, 180),
            scenes = listOf(RecapScene(0, 4200, true)), story = putStory(dir, RecapStory(source = "manual", blocks = listOf(block))),
            storyApproved = true, takes = takes, audio = RecapAudio(polish = false, loudness = false),
        )
        store.save(dir, p.copy(recap = recap))
        open(dir)
        return block
    }

    private fun open(dir: String) = current.set(OpenProject(dir, (store.open(dir) as CoreResult.Ok).value))

    private fun disk(dir: String): RecapV2 = (store.open(dir) as CoreResult.Ok).value.recap!!

    private fun audioArtifacts(dir: String) = fs.list("$dir/artifacts").filter { it.endsWith(".meta.json") }
        .map { CoreJson.parseToJsonElement(fs.read("$dir/artifacts/$it").decodeToString()).toString() }
        .filter { "\"AUDIO\"" in it || "\"audio\"" in it }

    @Test
    fun asyncTtsResultMustNotBeWrittenIntoAnotherProject() = runTest {
        val a = create("/a", "A")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val m = model(this, tts { c -> sent += c.text; entered.complete(Unit); release.await(); AiResult.Ok(wav(), Usage("configured-model")) })
        val job = m.speak(0)!!
        runCurrent()
        entered.await()
        val b = create("/b", "B") // the user opens another project while A's request is out
        m.refresh()
        assertNull(m.state.value.voiceRun)
        assertEquals("/a", m.foregroundVoiceRuns.value.single().dir)
        release.complete(Unit)
        job.join()
        assertTrue(m.foregroundVoiceRuns.value.isEmpty())

        assertEquals(listOf("A written"), sent)
        val bDisk = disk("/b")
        assertTrue(bDisk.takes.isEmpty(), "B got a take: ${bDisk.takes}")
        assertTrue(bDisk.chosenTakes.isEmpty())
        assertTrue(audioArtifacts("/b").isEmpty(), "no audio artifact in B")
        assertTrue(current.state.value!!.project.recap!!.takes.isEmpty(), "the open project (B) is untouched")
        assertEquals("/b", current.state.value!!.dir)
        assertNull(m.state.value.takeFiles[0])

        val take = disk("/a").takes.single()
        assertEquals(a.id, take.block)
        assertEquals(SpokenText.hash(a), take.spokenHash)
        assertEquals(take.audio, disk("/a").chosenTakes[a.id])
        val meta = (ArtifactStore(fs, "/a").get(take.audio) as CoreResult.Ok).value
        assertEquals(a.id, meta.inputs["block"])
        assertEquals(SpokenText.hash(a), meta.inputs["textHash"])
        assertTrue(fs.exists("/a/artifacts/${meta.file}"))
        assertTrue(b.id != take.block)
    }

    @Test
    fun aResultForABlockRemovedDuringTheRequestIsDropped() = runTest {
        create("/a", "A")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val m = model(this, tts { entered.complete(Unit); release.await(); AiResult.Ok(wav()) })
        val job = m.speak(0)!!
        runCurrent()
        entered.await()
        // The script is rewritten meanwhile: the block is replaced by a new one (another id, another text).
        val replaced = RecapStory(source = "manual", blocks = listOf(RecapBlock("A_new", listOf(RecapClip(0, 4200)), "new text")))
        val p = current.state.value!!.project
        session.save(p.copy(recap = p.recap!!.copy(story = putStory("/a", replaced))))
        release.complete(Unit)
        job.join()

        assertTrue(disk("/a").takes.isEmpty(), "a take of a block that is gone")
        assertTrue(audioArtifacts("/a").isEmpty(), "no orphan audio either")
        assertEquals(RecapModel.BLOCK_GONE, m.state.value.message)
    }

    @Test
    fun aTakeOfTextEditedDuringTheRequestIsKeptButStale() = runTest {
        val a = create("/a", "A")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val m = model(this, tts { entered.complete(Unit); release.await(); AiResult.Ok(wav()) })
        val job = m.speak(0)!!
        runCurrent()
        entered.await()
        val edited = a.copy(textMy = "A edited")
        val p = current.state.value!!.project
        session.save(p.copy(recap = p.recap!!.copy(story = putStory("/a", RecapStory(source = "manual", blocks = listOf(edited))))))
        release.complete(Unit)
        job.join()

        val take = disk("/a").takes.single()
        assertEquals(SpokenText.hash(a), take.spokenHash, "the hash of what was spoken, not of the new text")
        assertTrue(SpokenText.isStale(take, edited))
    }

    @Test
    fun recordingStoppedAfterOpeningAnotherProjectGoesToItsOwnProject() = runTest {
        val rec = object : VoiceRecorder {
            override val extension = "wav"
            var path: String? = null
            override suspend fun start(path: String): PlatformResult<Unit> { this.path = path; return PlatformResult.Ok(Unit) }
            override suspend fun stop(): PlatformResult<Long> { fs.writeDurably(path!!, wav(1500)); return PlatformResult.Ok(1500) }
            override fun cancel() {}
        }
        val a = create("/a", "A")
        val m = model(this, recorder = rec)
        m.startRecording(0)!!.join()
        assertTrue(rec.path!!.startsWith("/a/.cache/"))
        create("/b", "B")
        m.refresh()
        m.stopRecording()!!.join()

        assertTrue(disk("/b").takes.isEmpty())
        val take = disk("/a").takes.single()
        assertEquals(a.id, take.block)
        assertEquals(TakeSource.RECORDED, take.source)
        assertNull(take.provider, "a recording has no provider; none is invented")
        assertNull(take.model)
        assertNull(take.voice)
        assertEquals(now, take.createdAt)
    }

    @Test
    fun speakingAgainMustPreserveTakeHistory() = runTest {
        val a = RecapBlock("A_block", listOf(RecapClip(0, 4200)), "A written")
        val old = BlockTake(a.id, ArtifactId("au_111111111111"), TakeSource.RECORDED, durationMs = 1000, spokenHash = SpokenText.hash(a))
        create("/a", "A", takes = listOf(old))
        var length = 900
        val m = model(this, tts { AiResult.Ok(wav(length)) })
        m.speak(0)!!.join()
        length = 1100
        m.speak(0)!!.join()

        val takes = disk("/a").takes
        assertEquals(3, takes.size, "the recording and both generations: $takes")
        assertEquals(old, takes.first(), "the old take is kept as it was (legacy provenance stays unknown)")
        assertEquals(takes.last().audio, disk("/a").chosenTakes[a.id], "the newest generation is the chosen take")
        assertEquals(takes.last(), disk("/a").takeFor(a.id))
        assertTrue(disk("/a").chosenTakes.values.all { id -> takes.any { it.audio == id } }, "chosen takes point at real takes")
        assertEquals(store.open("/a"), CoreResult.Ok(current.state.value!!.project), "what the screen shows is what is on disk")

        m.speak(0)!!.join() // the same audio again: one take, not a duplicate record
        assertEquals(3, disk("/a").takes.size)
    }

    @Test
    fun speakAsTextMustBeUsed() = runTest {
        val a = create("/a", "A", spoken = "A pronunciation")
        val lexicon = Lexicon(listOf(LexiconEntry("pronunciation", "pro-nun-see-ay-shun")))
        val sent = mutableListOf<String>()
        val m = model(this, tts { c -> sent += c.text; AiResult.Ok(wav()) }, lexicon)
        m.speak(0)!!.join()

        assertEquals(listOf("A pro-nun-see-ay-shun"), sent, "speak-as text with the lexicon, not the written text")
        val take = disk("/a").takes.single()
        assertEquals(SpokenText.hash(a, lexicon), take.spokenHash, "the hash of exactly the spoken text")
        assertFalse(SpokenText.isStale(take, a, lexicon))
        assertTrue(SpokenText.isStale(take, a.copy(speakMy = null), lexicon), "dropping the override makes it stale")
    }

    @Test
    fun staleTakeMustNotBeSkippedBySpeakAll() = runTest {
        val a = RecapBlock("A_block", listOf(RecapClip(0, 4200)), "A written")
        val stale = BlockTake(a.id, ArtifactId("au_111111111111"), TakeSource.TTS, durationMs = 1000, spokenHash = "old text hash")
        create("/a", "A", takes = listOf(stale))
        var calls = 0
        val m = model(this, tts { calls++; AiResult.Ok(wav()) })
        assertNull(m.speakAll())
        assertEquals(1, m.state.value.speakAllEta?.count, "the stale scene is scheduled: ${m.state.value.message}")
        m.speakAll(confirmed = true)!!.join()
        assertEquals(1, calls)
        val takes = disk("/a").takes
        assertEquals(2, takes.size, "the stale take stays in the history")
        assertFalse(SpokenText.isStale(disk("/a").takeFor(a.id)!!, a))
        assertNull(m.speakAll())
        assertEquals("Every scene already has its voice.", m.state.value.message)
    }

    @Test
    fun firstTtsTakeMustSetVoiceLockAndProvenance() = runTest {
        val a = create("/a", "A")
        val locks = mutableListOf<String?>()
        val m = model(this, tts { c -> locks += c.lockedModel; AiResult.Ok(wav(), Usage("configured-model")) })
        m.setTtsVoice("Charon") // a voice chip lock: no model yet
        m.speak(0)!!.join()
        m.speak(0)!!.join()

        assertEquals(listOf(null, "configured-model"), locks, "no model \"\" is sent; the first answer locks the model")
        val r = disk("/a")
        assertEquals(VoiceLock("gemini", "configured-model", "Charon", "my"), r.voiceLock)
        val take = r.takeFor(a.id)!!
        assertEquals("gemini", take.provider)
        assertEquals("configured-model", take.model)
        assertEquals("Charon", take.voice)
        assertEquals(now, take.createdAt)
        val meta = (ArtifactStore(fs, "/a").get(take.audio) as CoreResult.Ok).value
        assertEquals("gemini", meta.provider)
        assertEquals("configured-model", meta.model)
        assertNotNull(m.state.value.takeFiles[0])
    }
}
