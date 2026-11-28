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
import kotlinx.serialization.json.put
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

    private inner class Fixture(takes: Boolean = true, withFont: Boolean = false,
        language: io.github.alalkipgen.autoflow.ui.i18n.UiLanguage = io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.ENGLISH, tasks: Boolean = true) {
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
        val libraryStore = io.github.alalkipgen.autoflow.core.library.LibraryStore(fs, "/app/library")
        val library = io.github.alalkipgen.autoflow.ui.library.LibraryModel(libraryStore, session, MainScope(), fs)
        val model = DubExportModel(session, fs, MediaTasks(queue.takeIf { tasks }, MainScope()), MainScope(), RecapFrames(session, settings, fs), share = share,
            uiLanguage = { language }, library = library)
        fun args(i: Int) = queue.task(i).args
        fun graph(i: Int) = args(i)[args(i).indexOf("-filter_complex") + 1]
    }

    /** Group 4 §8.1: without the media tools the export says so in the owner's language (was an English-only text). */
    @Test fun noMediaToolsSaysSoInTheOwnersLanguage() {
        val f = Fixture(tasks = false)
        assertNull(f.model.start(f.doc))
        assertEquals(UiText.V3_DUB_CANNOT_MAKE, f.model.state.value.notice?.key)
        assertEquals("ဒီနေရာမှာ ဗီဒီယို ထုတ်လို့ မရပါ။", f.model.state.value.notice?.text(io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.BURMESE))
    }

    /** Group 4 §8.2: the Jobs / notification title of each step is in the owner's language (was always English). */
    @Test fun theJobsTitleIsInTheOwnersLanguage() {
        val f = Fixture(language = io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.BURMESE)
        f.model.start(f.doc)
        assertEquals("Dub film · ${UiText.DUB_EXPORT_MIXING.my}", f.queue.jobs.single().title)
        val en = Fixture()
        en.model.start(en.doc)
        assertEquals("Dub film · ${UiText.DUB_EXPORT_MIXING.en}", en.queue.jobs.single().title)
    }

    /** Group 4 §G 1: the checklist's "All {n} lines have a voice" counts the lines that are spoken. */
    @Test fun spokenLinesCountsTheLinesWithWords() {
        val f = Fixture()
        assertEquals(2, f.model.spokenLines(f.doc))
        assertEquals(1, f.model.spokenLines(f.doc.copy(cues = f.doc.cues + SubtitleCue("c3", 7_000, 8_000, "", textMy = "")).let { d ->
            d.copy(cues = d.cues.drop(1)) }))
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
        assertEquals(io.github.alalkipgen.autoflow.core.recap.CaptionSize.S, f.session.open!!.project.dubbing!!.captionStyle.size,
            "the choice stays in the project (D-106)")
    }

    @Test fun burningWithoutABurmeseFontSaysSo() {
        val f = Fixture()
        f.model.setBurn(true)
        assertNull(f.model.start(f.doc))
        assertTrue(f.queue.jobs.isEmpty())
        assertEquals(UiText.DUB_BURN_NO_FONT, f.model.state.value.notice?.key)
    }

    /** D-107: the boxes are saved in the project, the subtitles sit on the bottom box, and the export draws boxes then subtitles. */
    @Test fun boxesAreSavedPreviewedAndDrawnUnderTheSubtitles() {
        val f = Fixture(withFont = true)
        f.model.toggleBox(io.github.alalkipgen.autoflow.core.model.DubMaskKind.BOTTOM)
        f.model.toggleBox(io.github.alalkipgen.autoflow.core.model.DubMaskKind.CUSTOM)
        assertEquals(1, f.model.state.value.box, "a new box is picked")
        f.model.fillBox(1, "#ffffff")
        f.model.setBlurStrength(io.github.alalkipgen.autoflow.core.recap.MaskStrength.LIGHT)
        val tool = f.session.open!!.project.dubbing!!
        assertEquals(listOf(io.github.alalkipgen.autoflow.core.model.DubMaskKind.BOTTOM, io.github.alalkipgen.autoflow.core.model.DubMaskKind.CUSTOM),
            tool.masks.map { it.kind })
        assertEquals(listOf(null, "#FFFFFF"), tool.masks.map { it.fill })
        // Without subtitles the boxes alone make a new mp4 (the picture is no longer copied).
        f.model.start(f.doc); f.queue.done(0); f.queue.done(1)
        val plain = f.graph(2)
        assertTrue(plain.startsWith("[0:v]split[dm0a][dm0b];") && "gblur=sigma=12[dm0c]" in plain && "color=0xFFFFFF@1:t=fill[dm1]" in plain &&
            "[dm1]null[vout];" in plain, plain)
        assertTrue(f.queue.task(2).output.endsWith(".mp4") && "libx264" in f.args(2) && "copy" !in f.args(2))
        f.queue.done(2)
        f.model.setBurn(true)
        assertTrue(f.model.previewCaptions(f.session.open, f.doc).all { o -> o.centerY?.let { kotlin.math.abs(it - .885f) < 1e-4f } == true },
            "the subtitles sit on the bottom box")
        f.model.start(f.doc); f.queue.done(3); f.queue.done(4)
        val burned = f.graph(5)
        assertTrue(Regex("\\[dm1\\]ass=filename=[^;]*-y885\\.ass:fontsdir=[^;]*\\[vout\\];").containsMatchIn(burned), burned)
        f.queue.done(5)
        f.model.setCaptionsOnBox(false)
        assertTrue(f.model.previewCaptions(f.session.open, f.doc).all { it.centerY == null }, "the usual place")
        f.model.removeBox(0); f.model.removeBox(0)
        assertEquals(emptyList(), f.model.masks())
        assertNull(f.model.state.value.box)
    }

    /**
     * D-106: the caption style is saved in the project, the preview shows the overlays the burn writes, and each look /
     * size is its own video; the default keeps the boxed white Medium and its old name.
     */
    @Test fun captionStyleIsSavedPreviewedAndBurned() {
        val f = Fixture(withFont = true)
        f.model.setBurn(true)
        assertEquals(DubbingTool.DEFAULT_CAPTIONS, f.model.style())
        assertTrue(f.model.previewCaptions(f.session.open, f.doc).all { it.box && it.color == "#FFFFFF" && it.strokeWidth == 0 })
        f.model.setCaptionStyle { it.copy(colour = "#FFE14D", box = false, strokeWidth = 6, strokeColour = "#FF4444") }
        val saved = f.session.open!!.project.dubbing!!.captionStyle
        assertEquals(io.github.alalkipgen.autoflow.core.recap.CaptionStyle(colour = "#FFE14D", strokeWidth = 6, strokeColour = "#FF4444"), saved)
        val preview = f.model.previewCaptions(f.session.open, f.doc)
        assertTrue(preview.isNotEmpty() && preview.all { !it.box && it.color == "#FFE14D" && it.strokeWidth == 6 && it.strokeColour == "#FF4444" })
        f.model.start(f.doc); f.queue.done(0); f.queue.done(1)
        val name = f.graph(2).substringAfter("filename=").substringBefore(":fontsdir")
        assertTrue(Regex(".*-dub-medium-1l-short-[0-9a-f]{8}\\.ass").matches(name), name)
        val ass = f.fs.read("${f.dir}/$name").decodeToString()
        assertTrue("&H004DE1FF" in ass && "&H004444FF" in ass, ass)
        assertEquals(preview.size, ass.lines().count { it.startsWith("Dialogue:") }, "the preview shows every part the burn draws")
        f.queue.done(2)
        f.model.setCustomSize(1.4f)
        assertEquals(1.4f, f.model.style().customSizeFactor)
        f.model.start(f.doc); f.queue.done(3); f.queue.done(4)
        val big = f.graph(5).substringAfter("filename=").substringBefore(":fontsdir")
        assertTrue(big.contains("-dub-x1.4-1l-short-"), big)
        assertTrue(f.queue.task(5).output != f.queue.task(2).output, "another size makes another video")
        f.queue.done(5)
        f.model.setSize(BurnSize.LARGE)
        assertEquals(null, f.model.style().customSizeFactor)
        f.model.setCustomColour("12ab34"); assertEquals("#12AB34", f.model.style().customColour)
        f.model.setCustomColour("zz"); assertEquals(null, f.model.style().customColour)
        f.model.setCustomSize(9f); assertEquals(3f, f.model.style().customSizeFactor, "at most 3 × Medium")
    }

    /** D-106: Save caption style… keeps the Dubbing style in the Library; the preset applies back to a Dubbing project. */
    @Test fun captionStyleIsSharedThroughTheLibrary() {
        val f = Fixture()
        f.model.setCaptionStyle { it.copy(colour = "#7FE7FF", box = false, strokeWidth = 3, customSizeFactor = 1.4f) }
        f.model.saveCaptionStyle("Cyan outline")
        assertTrue(f.model.state.value.styleOk, "${f.model.state.value.styleNotice}")
        assertEquals(UiText.V3_LIBRARY_SAVED, f.model.state.value.styleNotice?.key)
        val preset = f.libraryStore.load().presets.single()
        assertEquals("Cyan outline", preset.name)
        f.model.setCaptionStyle { DubbingTool.DEFAULT_CAPTIONS }
        f.library.refresh(); f.library.applyPreset(preset.id)
        assertTrue(f.library.state.value.messageOk, "${f.library.state.value.notice}")
        assertEquals(io.github.alalkipgen.autoflow.core.recap.CaptionStyle(colour = "#7FE7FF", strokeWidth = 3, customSizeFactor = 1.4f), f.model.style())
        // A preset saved before D-106 (size / colour / box only) keeps the rest of the project's style.
        f.libraryStore.save(f.libraryStore.load().copy(presets = listOf(io.github.alalkipgen.autoflow.core.library.Preset("old", "Old", io.github.alalkipgen.autoflow.core.library.PresetKind.CAPTION,
            kotlinx.serialization.json.buildJsonObject { put("size", "S"); put("colour", "#FF4444"); put("box", true) }))))
        f.library.refresh(); f.library.applyPreset("old")
        assertEquals(io.github.alalkipgen.autoflow.core.recap.CaptionStyle(size = io.github.alalkipgen.autoflow.core.recap.CaptionSize.S, colour = "#FF4444", box = true,
            strokeWidth = 3, customSizeFactor = 1.4f), f.model.style())
    }
}
