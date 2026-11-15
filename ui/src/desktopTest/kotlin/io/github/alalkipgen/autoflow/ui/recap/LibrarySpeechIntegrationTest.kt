package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ai.*
import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.library.Library
import io.github.alalkipgen.autoflow.core.library.LibraryStore
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.audio.PcmWav
import io.github.alalkipgen.autoflow.media.render.*
import io.github.alalkipgen.autoflow.ui.di.uiModule
import io.github.alalkipgen.autoflow.ui.export.ExportModel
import io.github.alalkipgen.autoflow.ui.export.RecapFrames
import io.github.alalkipgen.autoflow.ui.fakePlatformModule
import io.github.alalkipgen.autoflow.ui.jobs.DesktopAiJobRunner
import io.github.alalkipgen.autoflow.ui.jobs.RecapJobProcessor
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.*

/** Real uiModule -> LibraryStore -> RecapModel/RecapJobProcessor -> frames/ExportModel; only API/OS ports are fake. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySpeechIntegrationTest {
    private class Speech : TtsProvider {
        override val capabilities = TtsCapabilities(burmese = true)
        val sent = mutableListOf<String>()
        val models = mutableListOf<String?>()
        var waitFor: CompletableDeferred<Unit>? = null
        override suspend fun speak(text: String, voice: VoiceRef, lang: String, style: String?, lockedModel: String?): AiResult<ByteArray> {
            sent += text; models += lockedModel
            val n = sent.size
            waitFor?.await()
            return AiResult.Ok(PcmWav.encode(8000, FloatArray(8_000 + n * 80) { n / 100f }), Usage(model = "configured-default"))
        }
    }
    private class Queue : RenderQueue {
        val submitted = mutableListOf<RenderJob>()
        override fun enqueue(job: RenderJob): String { submitted += job; return submitted.size.toString() }
        override fun observe(id: String) = flowOf<RenderState>(RenderState.Done("test.mp4", 1, 3700, null))
        override fun cancel(id: String) = Unit
    }
    private fun <T> CoreResult<T>.ok(): T = assertIs<CoreResult.Ok<T>>(this).value

    private fun create(store: ProjectStore, fs: InMemoryFileSystem, dir: String): Project {
        val project = store.create(dir, dir.substringAfterLast('/'), Mode.MOVIE_RECAP).ok()
        val story = RecapStory(source = "manual", blocks = listOf(
            RecapBlock("first", listOf(RecapClip(0, 1000), RecapClip(2000, 3200)), "written first"),
            RecapBlock("second", listOf(RecapClip(4000, 5500)), "written second"),
        ))
        val rs = ArtifactStore(fs, dir).put(CoreJson.encodeToString(RecapStory.serializer(), story).encodeToByteArray(), ArtifactKind.RECAP_SCRIPT, "json").ok().id
        fs.writeDurably("$dir/source/movie.mp4", byteArrayOf(1))
        return store.save(dir, project.copy(recap = RecapV2(
            rightsConfirmed = true, movie = RecapMovie("source/movie.mp4", 1, 10_000, 320, 180),
            story = rs, storyApproved = true, layers = listOf(Layer("border", LayerType.BORDER)),
            audio = RecapAudio(polish = false, loudness = false),
        ))).ok()
    }

    @Test fun savedLibraryEditsReachSingleAndQueuedSpeechStalenessPreviewAndExport() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fs = InMemoryFileSystem(); val speech = Speech(); val queue = Queue()
        val app = koinApplication { modules(fakePlatformModule(fs), uiModule, aiModule, module {
            single<TtsProvider> { speech }
            single<RenderQueue> { queue }
            single<AiJobRunner> {
                val koin = getKoin()
                DesktopAiJobRunner(AiJobStore(fs, "/jobs"), backgroundScope,
                    RecapJobProcessor { koin.get<RecapModel>() }, { RpmPacer(1000) })
            }
        }) }
        try {
            val k = app.koin
            val lib = k.get<LibraryStore>()
            lib.save(Library(lexicon = listOf(LexiconEntry("written", "spoken"))))
            val project = create(k.get(), fs, "/P")
            k.get<CurrentProject>().set(OpenProject("/P", project))
            val model = k.get<RecapModel>() // Never construct RecapModel with a test-only Lexicon.
            model.speak(0)!!.join()
            assertEquals(listOf("spoken first"), speech.sent, "saved Library replacement must reach single-block TTS")
            assertNull(speech.models.single(), "provider resolves the configured default before the first voice lock")
            assertEquals("configured-default", k.get<ProjectSession>().open!!.project.recap!!.voiceLock!!.model)
            assertNotNull(model.state.value.currentTake(0), "same effective text at read time")
            model.speakAll(confirmed = true)!!.join()
            assertEquals(listOf("spoken first", "spoken second"), speech.sent)
            assertEquals(AiJobState.DONE, k.get<AiJobRunner>().jobs.value.single().state, "real persisted runner, not a mocked scheduling seam")
            for ((i, b) in model.state.value.story!!.blocks.withIndex()) {
                assertEquals(RecapRules.textHash(speech.sent[i]), model.state.value.recap!!.takeFor(b.id)!!.spokenHash)
            }

            lib.save(lib.load().copy(lexicon = listOf(LexiconEntry("written", "updated"))))
            runCurrent()
            assertTrue(model.state.value.isStale(0), "Library edit must invalidate affected old takes without restart")
            assertTrue(model.state.value.isStale(1))
            assertFalse(model.isJobItemDone("/P", "first"), "job reconciliation must not skip an affected take")
            val export = k.get<ExportModel>()
            export.refresh()
            assertFalse(export.state.value.canRender, "preview/export must not accept old pronunciation")
            model.speakAll()
            assertEquals(2, model.state.value.speakAllEta!!.count)
            model.speakAll(confirmed = true)!!.join()
            assertEquals(listOf("spoken first", "spoken second", "updated first", "updated second"), speech.sent)
            assertTrue(k.get<AiJobRunner>().jobs.value.all { it.state == AiJobState.DONE })
            val recap = model.state.value.recap!!
            assertEquals(4, recap.takes.size, "old takes remain history")
            model.state.value.story!!.blocks.forEachIndexed { i, b ->
                val take = assertNotNull(model.state.value.currentTake(i))
                assertEquals(RecapRules.textHash(speech.sent[i + 2]), take.spokenHash, "hash exactly the sent string")
                assertTrue(model.isJobItemDone("/P", b.id))
            }
            export.refresh()
            assertTrue(export.state.value.canRender)
            assertEquals(2, export.state.value.recap!!.voiced)
            export.render(Quality.PREVIEW); runCurrent()
            export.render(Quality.EXPORT); runCurrent()
            assertEquals(2, queue.submitted.size, "both production callers use the lossless v2 job")
            for (job in queue.submitted) {
                assertEquals(listOf(0L to 1000L, 2000L to 3200L, 4000L to 5500L), job.graph.clips.map { it.trimInMs to it.trimOutMs })
                assertEquals(recap.layers, job.graph.layers)
                assertEquals(3700L, job.graph.durationMs)
                assertEquals(recap.chosenTakes.values.toSet(), job.graph.voice!!.cues.map { cue ->
                    recap.takes.first { cue.file.contains(it.audio.value) }.audio
                }.toSet())
                assertTrue(job.srt!!.contains("written first")); assertFalse(job.srt!!.contains("updated"), "captions stay written")
            }
            val open = k.get<ProjectSession>().open!!
            val exact = k.get<RecapFrames>().job(open, Quality.PREVIEW, null, "exports/exact", "exact")!!
            assertEquals(queue.submitted.first().graph, exact.graph, "Exact frame and Preview share the same v2/lexicon path")
        } finally { app.close(); Dispatchers.resetMain() }
    }

    @Test fun inFlightSpeechKeepsOriginalTextLexiconAndProjectAfterEditsAndSwitch() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fs = InMemoryFileSystem(); val speech = Speech().apply { waitFor = CompletableDeferred() }
        val app = koinApplication { modules(fakePlatformModule(fs), uiModule, aiModule, module { single<TtsProvider> { speech } }) }
        try {
            val k = app.koin; val store = k.get<ProjectStore>(); val current = k.get<CurrentProject>(); val session = k.get<ProjectSession>()
            val lib = k.get<LibraryStore>()
            lib.save(Library(lexicon = listOf(LexiconEntry("written", "started"))))
            current.set(OpenProject("/A", create(store, fs, "/A")))
            val model = k.get<RecapModel>()
            val pending = model.speak(0)!!; runCurrent()
            assertEquals(listOf("started first"), speech.sent)
            lib.save(lib.load().copy(lexicon = listOf(LexiconEntry("written", "changed"))))
            model.saveSegment(0, "edited after request")
            current.set(OpenProject("/B", create(store, fs, "/B")))
            model.refresh()
            speech.waitFor!!.complete(Unit); pending.join()
            val a = store.open("/A").ok().recap!!
            assertEquals(RecapRules.textHash("started first"), a.takes.single().spokenHash)
            assertTrue(store.open("/B").ok().recap!!.takes.isEmpty())
            current.set(OpenProject("/A", store.open("/A").ok())); model.refresh()
            assertTrue(model.state.value.isStale(0), "do not rehash an old answer using new text/lexicon")
        } finally { app.close(); Dispatchers.resetMain() }
    }
}
