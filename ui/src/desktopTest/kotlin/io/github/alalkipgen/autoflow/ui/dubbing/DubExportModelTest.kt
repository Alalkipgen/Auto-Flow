package io.github.alalkipgen.autoflow.ui.dubbing

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.dubbing.DubRules
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.DubOriginal
import io.github.alalkipgen.autoflow.core.model.DubTake
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.media.audio.DubMixer
import io.github.alalkipgen.autoflow.media.render.BurnFit
import io.github.alalkipgen.autoflow.media.render.BurnLength
import io.github.alalkipgen.autoflow.media.render.BurnSize
import io.github.alalkipgen.autoflow.media.render.MediaTaskJob
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.export.RecapFrames
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.media.MediaTasks
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.*

/** D-3b (specs/dubbing.md §2 Export, §6): pre-flight, the mix steps one after another, Lower / Mute, burn, .srt and .wav. */
@OptIn(ExperimentalCoroutinesApi::class)
class DubExportModelTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    internal class Queue : RenderQueue {
        val jobs = mutableListOf<RenderJob>(); val states = mutableMapOf<String, MutableStateFlow<RenderState>>()
        override fun enqueue(job: RenderJob): String { jobs += job; return "job${jobs.size}".also { states[it] = MutableStateFlow(RenderState.Queued) } }
        override fun observe(id: String): Flow<RenderState> = states.getValue(id)
        override fun cancel(id: String) { states.getValue(id).value = RenderState.Cancelled }
        fun task(i: Int): MediaTaskJob = jobs[i].mediaTask!!
        fun done(i: Int) { states.getValue("job${i + 1}").value = RenderState.Done(MediaTaskJob.absolute(jobs[i].workDir, task(i).output), 1, 1, null) }
    }

    private class Share : FileShare {
        val calls = mutableListOf<String>()
        override val sharesFiles = true
        override fun open(path: String, mime: String): PlatformResult<Unit> { calls += "open $path"; return PlatformResult.Ok(Unit) }
        override fun share(path: String, mime: String, title: String): PlatformResult<Unit> = PlatformResult.Ok(Unit)
        override suspend fun saveToGallery(path: String, mime: String, displayName: String): PlatformResult<String> = PlatformResult.Ok(displayName)
        override fun openFolder(path: String): PlatformResult<Unit> = PlatformResult.Ok(Unit)
    }

    private val font: ByteArray = Base64.getMimeDecoder().decode(javaClass.getResourceAsStream("/fonts/NotoSansMyanmar-subset.ttf.b64")!!.readBytes())

    private inner class Fixture(takes: Boolean = true, withFont: Boolean = false) {
        val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
        val dir = "/p/Dub film.autoflow"
        val session = ProjectSession(fs, store, ProjectIndex(fs, "/app/projects.json"), current)
        val settings = SettingsStore(fs, "/app/s.json")
        val queue = Queue(); val share = Share()
        val lock = VoiceLock("gemini", "tts-a", "Kore", "my")
        val doc = SubtitleDoc(source = "srt", nextId = 3, cues = listOf(
            SubtitleCue("c1", 1_000, 3_000, "We have to leave tonight.", textMy = "ဒီည ထွက်သွားရမယ်။"),
            SubtitleCue("c2", 4_000, 6_000, "Before the rain starts.", textMy = "မိုးမရွာခင် သွားရမယ်။")))
        init {
            val created = (store.create(dir, "Dub film", Mode.DUBBING) as CoreResult.Ok).value
            val art = ArtifactStore(fs, dir)
            val st = (art.put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(), ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
            val t = if (!takes) emptyMap() else doc.cues.associate { c ->
                val au = (art.put("RIFF-${c.id}".encodeToByteArray(), ArtifactKind.AUDIO, "wav") as CoreResult.Ok).value.id
                c.id to DubTake(au, DubRules.takeKey(c, Lexicon.EMPTY, lock, null), 1_500, leadMs = 80)
            }
            val tool = DubRules.approve(DubbingTool(lock = lock, takes = t), 65_000, 600_000, doc, 1)!!
            current.set(OpenProject(dir, (store.save(dir, created.copy(dubbing = tool, subtitles = SubtitlesTool(
                video = SourceVideo("source/movie.mp4", 9, 65_000, 1920, 1080, 25.0), current = st))) as CoreResult.Ok).value))
            if (withFont) { fs.createDirectories("/fonts"); fs.writeDurably("/fonts/noto.ttf", font); settings.update { it.copy(fontPaths = listOf("/fonts/noto.ttf")) } }
        }
        val model = DubExportModel(session, fs, MediaTasks(queue, MainScope()), MainScope(), RecapFrames(session, settings, fs), share = share)
        fun args(i: Int) = queue.task(i).args
        fun graph(i: Int) = args(i)[args(i).indexOf("-filter_complex") + 1]
    }

    @Test fun nothingStartsUntilEveryLineHasAVoice() {
        val f = Fixture(takes = false)
        assertEquals(listOf("c1", "c2"), f.model.check(f.current.state.value, f.doc)!!.noVoice)
        assertNull(f.model.start(f.doc))
        assertTrue(f.queue.jobs.isEmpty())
        assertEquals(UiText.DUB_EXPORT_NOT_READY, f.model.state.value.notice?.key)
    }

    @Test fun stemsThenVoiceThenTheVideoOneAfterAnotherWithTheOriginalLowered() {
        val f = Fixture()
        assertTrue(f.model.check(f.current.state.value, f.doc)!!.ok)
        f.model.start(f.doc)
        assertEquals(listOf(DubMixer.STEM), f.queue.jobs.map { it.mediaTask!!.kind }, "the next step waits for this one")
        assertEquals(3, f.model.state.value.total)
        assertTrue(f.args(0).filter { it.startsWith("artifacts/au_") }.size == 2, "both takes from the project's artifacts")
        assertTrue("atrim=start=0.080" in f.graph(0), "the lead silence is skipped")
        f.queue.done(0)
        assertEquals(DubMixer.VOICE, f.queue.task(1).kind)
        assertEquals(listOf(f.queue.task(0).output), f.args(1).filterIndexed { i, _ -> i > 0 && f.args(1)[i - 1] == "-i" })
        f.queue.done(1)
        val export = f.queue.task(2)
        assertEquals(DubMixer.EXPORT, export.kind)
        assertTrue("volume=0.15" in f.graph(2) && "copy" in f.args(2), "Lower 15 %, the picture copied")
        assertTrue(export.output.startsWith("exports/Dub-film-my-") && "-dub-" in export.output && export.output.endsWith(".mp4"), export.output)
        assertNull(f.model.done())
        f.queue.done(2)
        assertEquals("${f.dir}/${export.output}", f.model.done())
        assertFalse(f.model.state.value.busy)
        val srt = f.fs.read(f.model.state.value.srt!!).decodeToString()
        assertTrue("ဒီည ထွက်သွားရမယ်။" in srt && "leave" !in srt, "the .srt is the Burmese subtitle text")
        f.model.open()
        assertEquals(listOf("open ${f.dir}/${export.output}"), f.share.calls)
    }

    @Test fun muteBurnAndWavAndAFailedStepStopsTheRest() {
        val f = Fixture(withFont = true)
        val open = f.current.state.value!!
        f.session.update(open.dir, open.project.id) { p -> p.copy(dubbing = p.dubbing!!.copy(original = DubOriginal(DubOriginal.MUTE, 0.0))) }
        f.model.setBurn(true)
        assertTrue(f.current.state.value!!.project.dubbing!!.burnSubtitles)
        assertTrue(DubRules.isApproved(f.current.state.value!!.project.dubbing, f.doc), "options never touch the approval")
        f.model.setWav(true)
        f.model.start(f.doc)
        assertEquals(4, f.model.state.value.total, "stem, voice, the kept .wav, video")
        f.queue.done(0)
        f.queue.states.getValue("job2").value = RenderState.Failed("ffmpeg failed (exit 1)")
        assertEquals(2, f.queue.jobs.size, "a failed step stops the chain")
        assertFalse(f.model.state.value.busy)
        f.model.start(f.doc) // Export again: the same tasks (finished files are reused by the queue)
        assertEquals(f.queue.task(0), f.queue.task(2))
        f.queue.done(2); f.queue.done(3)
        val kept = f.queue.task(4)
        assertEquals(f.queue.task(3).args, kept.args, "the kept .wav is the same mix")
        assertTrue(kept.output.startsWith("exports/") && "-voice-" in kept.output && kept.output.endsWith(".wav"))
        f.queue.done(4)
        val g = f.graph(5)
        assertTrue(g.startsWith("[0:v]ass=") && "volume" !in g && "libx264" in f.args(5), "burned, Mute = only the voice")
        assertTrue(f.fs.exists("${f.dir}/.cache/burn/" + f.args(5).let { it[it.indexOf("-filter_complex") + 1] }.substringAfter("filename=.cache/burn/").substringBefore(":")))
    }

    /** Owner, 2026-11-21: a landscape dub burns 1 short line by default; size, lines and length are chosen, each its own video. */
    @Test fun burnedSubtitlesFitTheFrameAndTheChoiceNamesTheVideo() {
        val f = Fixture(withFont = true)
        f.model.setBurn(true)
        assertEquals(BurnFit(1, BurnLength.SHORT), f.model.fit(), "1920×1080 → 1 short line")
        fun assOf(i: Int): Pair<String, String> {
            val name = f.graph(i).substringAfter("filename=").substringBefore(":fontsdir")
            return name to f.fs.read("${f.dir}/$name").decodeToString()
        }
        f.model.start(f.doc); f.queue.done(0); f.queue.done(1)
        val (oneName, one) = assOf(2)
        assertTrue(oneName.endsWith("-dub-medium-1l-short.ass"), oneName)
        assertTrue(one.lines().filter { it.startsWith("Dialogue:") }.none { "\\N" in it }, "one line at a time")
        f.queue.done(2)
        f.model.setSize(BurnSize.SMALL)
        f.model.setFit(BurnFit(2, BurnLength.LONG))
        assertEquals(BurnFit(2, BurnLength.LONG), f.model.fit())
        f.model.start(f.doc)
        f.queue.done(3); f.queue.done(4)
        val (twoName, _) = assOf(5)
        assertTrue(twoName.endsWith("-dub-small-2l-long.ass"), twoName)
        assertTrue(f.queue.task(5).output != f.queue.task(2).output, "another choice makes another video")
        assertEquals(BurnSize.SMALL, f.model.state.value.size, "the choice stays for the next export")
    }

    @Test fun burningWithoutABurmeseFontSaysSo() {
        val f = Fixture()
        f.model.setBurn(true)
        assertNull(f.model.start(f.doc))
        assertTrue(f.queue.jobs.isEmpty())
        assertEquals(UiText.DUB_BURN_NO_FONT, f.model.state.value.notice?.key)
    }
}
