package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleExport
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import io.github.alalkipgen.autoflow.media.render.BurnLook
import io.github.alalkipgen.autoflow.media.render.BurnSize
import io.github.alalkipgen.autoflow.media.render.MediaTaskJob
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.export.RecapFrames
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.media.MediaTasks
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.*

/** S-4c: Video with subtitles — pre-flight, the burn task on the queue, progress / Cancel, then Play · Share · Save. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SubtitleBurnTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    private val font: ByteArray = Base64.getMimeDecoder().decode(javaClass.getResourceAsStream("/fonts/NotoSansMyanmar-subset.ttf.b64")!!.readBytes())

    private class Queue : RenderQueue {
        val jobs = mutableListOf<RenderJob>(); val states = mutableMapOf<String, MutableStateFlow<RenderState>>(); val cancelled = mutableListOf<String>()
        override fun enqueue(job: RenderJob): String { jobs += job; return "job${jobs.size}".also { states[it] = MutableStateFlow(RenderState.Queued) } }
        override fun observe(id: String): Flow<RenderState> = states.getValue(id)
        override fun cancel(id: String) { cancelled += id; states.getValue(id).value = RenderState.Cancelled }
    }

    private class Share(override val sharesFiles: Boolean) : FileShare {
        val calls = mutableListOf<String>()
        override fun open(path: String, mime: String): PlatformResult<Unit> { calls += "open $mime $path"; return PlatformResult.Ok(Unit) }
        override fun share(path: String, mime: String, title: String): PlatformResult<Unit> { calls += "share $mime $path"; return PlatformResult.Ok(Unit) }
        override suspend fun saveToGallery(path: String, mime: String, displayName: String): PlatformResult<String> {
            calls += "gallery $displayName"; return PlatformResult.Ok("Movies/AutoFlow/$displayName")
        }
        override fun openFolder(path: String): PlatformResult<Unit> { calls += "folder $path"; return PlatformResult.Ok(Unit) }
    }

    /** ffmpeg stand-in for Check one frame: writes a 64×27 PNG to the last argument, or fails with [fail]. */
    private class Frames(val fs: InMemoryFileSystem) : ToolRunner {
        val commands = mutableListOf<List<String>>(); var fail: String? = null
        override suspend fun run(command: List<String>, workDir: String, timeoutMs: Long, keepOutput: Boolean, onLine: (String) -> Unit): ToolExec {
            commands += command
            fail?.let { return ToolExec.Done(1, "", listOf(it), 1, false) }
            val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(64, 27, 1), "png", it) }
            fs.writeDurably("$workDir/${command.last()}", png.toByteArray())
            return ToolExec.Done(0, "", emptyList(), 1, false)
        }
    }

    private inner class Fixture(shares: Boolean, withFont: Boolean) {
        val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
        val index = ProjectIndex(fs, "/app/projects.json")
        val dir = "/p/My film.autoflow"
        val session = ProjectSession(fs, store, index, current)
        val settings = SettingsStore(fs, "/app/s.json")
        val share = Share(shares)
        val queue = Queue()
        val doc = SubtitleDoc(source = SubtitleDoc.SOURCE_SRT, nextId = 3, cues = listOf(
            SubtitleCue("c1", 1_000, 3_000, "We have to leave tonight.", textMy = "ဒီည ထွက်သွားရမယ်။"),
            SubtitleCue("c2", 4_000, 6_000, "Not yet translated")))
        val version: ArtifactId
        init {
            val created = (store.create(dir, "My film", Mode.SUBTITLES) as CoreResult.Ok).value
            version = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(),
                ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
            val project = created.copy(subtitles = SubtitlesTool(video = SourceVideo("source/movie.mp4", 9, 60_000, 1920, 800, 23.976), current = version))
            current.set(OpenProject(dir, (store.save(dir, project) as CoreResult.Ok).value))
            if (withFont) {
                fs.createDirectories("/fonts"); fs.writeDurably("/fonts/noto.ttf", font)
                settings.update { it.copy(fontPaths = listOf("/fonts/noto.ttf")) }
            }
        }
        val model = SubtitlesModel(session, fs, DashboardModel(fs, store, index, settings, FakePicker(), current), null, MainScope(), probe = { null })
        val editor = SubtitleEditorModel(session, fs, MainScope())
        val exporter = SubtitleExportModel(session, fs, share, MainScope())
        val tasks = MediaTasks(queue, MainScope())
        val runner = Frames(fs)
        val locator = object : ToolLocator {
            override fun findFfmpeg() = "ffmpeg"
            override fun findVlc(): String? = null
            override fun isVlcDir(dir: String) = false
        }
        val burner = SubtitleBurnModel(session, fs, tasks, RecapFrames(session, settings, fs), MediaTools(settings, locator, runner), share = share,
            audioCodec = { _, _ -> "aac" })
        fun out(choice: TextChoice, look: String) =
            "exports/" + SubtitleExport.fileName("My film", choice, version.value, "mp4").removeSuffix(".mp4") + "-$look.mp4"
        fun task(i: Int = 0): MediaTaskJob = queue.jobs[i].mediaTask!!
        fun ass(i: Int = 0): String = fs.read("$dir/" + task(i).args[task(i).args.indexOf("-vf") + 1].substringAfter("filename=").substringBefore(":fontsdir")).decodeToString()
    }

    private val noDecoder: SubtitlePlayer = { _, _, m, report ->
        LaunchedEffect(Unit) { report(MovieSnapshot(phase = MoviePhase.FAILED, problem = "no decoder")) }
        Box(m)
    }

    private fun ComposeUiTest.show(f: Fixture, font: Float = 1f, language: UiLanguage = UiLanguage.ENGLISH) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
            SubtitlesScreen(f.model, f.session, f.editor, player = noDecoder, exporter = f.exporter, burner = f.burner)
        }
    }

    @Test fun burmeseNeedsAFontOriginalBurnsAsATask() = runBlocking {
        val f = Fixture(shares = true, withFont = false)
        f.burner.start(f.doc, f.version, TextChoice.BURMESE)
        assertEquals(UiText.SUB_BURN_NO_FONT, f.burner.state.value.notice?.key)
        assertTrue(f.queue.jobs.isEmpty())
        f.burner.start(f.doc.copy(cues = emptyList()), f.version, TextChoice.ORIGINAL)
        assertEquals(UiText.SUB_EXPORT_NO_LINES, f.burner.state.value.notice?.key)

        f.burner.setLook(BurnLook.BOXED); f.burner.setSize(BurnSize.LARGE)
        f.burner.start(f.doc, f.version, TextChoice.ORIGINAL)
        val t = f.task()
        assertEquals(MediaTaskJob.SUBTITLE_BURN, t.kind)
        assertEquals(f.out(TextChoice.ORIGINAL, "boxed-large-1l-short"), t.output)
        assertEquals(60_000, t.durationMs)
        assertEquals(listOf("-i", "source/movie.mp4"), t.args.subList(2, 4))
        assertTrue(t.args.containsAll(listOf("-c:a", "copy")) && t.args.last() == MediaTaskJob.PART, t.args.toString())
        val ass = f.ass()
        assertTrue("PlayResX: 1920" in ass && "PlayResY: 800" in ass && "We have to leave tonight." in ass && "Not yet translated" in ass, ass)
        assertEquals("$dir/${t.output}", f.burner.state.value.output)
        assertNull(f.burner.state.value.notice)
        f.burner.start(f.doc, f.version, TextChoice.ORIGINAL) // one burn at a time
        assertEquals(1, f.queue.jobs.size)
        f.burner.cancel()
        assertEquals(listOf("job1"), f.queue.cancelled)
    }

    private val dir = "/p/My film.autoflow"

    @Test fun burmeseBurnCopiesTheFont() = runBlocking {
        val f = Fixture(shares = true, withFont = true)
        f.burner.start(f.doc, f.version, TextChoice.BURMESE)
        val t = f.task()
        assertEquals(f.out(TextChoice.BURMESE, "clean-medium-1l-short"), t.output)
        assertTrue(f.fs.list("$dir/.cache/fonts").any { it.endsWith(".ttf") }, f.fs.list("$dir/.cache/fonts").toString())
        assertTrue(t.args[t.args.indexOf("-vf") + 1].endsWith(":fontsdir=.cache/fonts"))
        val ass = f.ass()
        assertTrue("ဒီည ထွက်သွားရမယ်။" in ass && "Not yet translated" in ass, ass)
    }

    @Test fun phoneShowsProgressThenSharesAndSaves() = runDesktopComposeUiTest(width = 412, height = 900) {
        val f = Fixture(shares = true, withFont = false)
        show(f)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-export-open").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("subtitles-export-open").performMouseInput { click() }
        onNodeWithTag("subtitles-export-video-planned").assertDoesNotExist()
        // F-6 (D-95): the burn card previews the video before Make video; without a decoder it says so and the card goes on.
        // Group 5 F 4: the hint is behind the card's `info` button (Group 4 rule), shown only while it is open.
        onNodeWithTag("burn-preview-hint").assertDoesNotExist()
        onNodeWithTag("burn-preview-info").performMouseInput { click() }
        onNodeWithTag("burn-preview-hint").assertExists()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("burn-preview-unavailable").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("subtitles-burn-CLEAN").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "selected"))
        onNodeWithTag("subtitles-burn-make").performMouseInput { click() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-burn-notice").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("subtitles-burn-notice").assertTextContains("Burmese font", substring = true)
        onNodeWithTag("subtitles-export-ORIGINAL").performScrollTo().performMouseInput { click() }
        onNodeWithTag("subtitles-burn-OUTLINE").performScrollTo().performMouseInput { click() }
        onNodeWithTag("subtitles-burn-make").performMouseInput { click() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-burn-job").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(f.out(TextChoice.ORIGINAL, "outline-medium-1l-short"), f.task().output)
        f.queue.states.getValue("job1").value = RenderState.Running(0.42, 1.0)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Making the video: 42%").fetchSemanticsNodes().isNotEmpty() }
        val out = "$dir/" + f.out(TextChoice.ORIGINAL, "outline-medium-1l-short")
        f.queue.states.getValue("job1").value = RenderState.Done(out, 1, 60_000, null)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-burn-done").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("subtitles-burn-job").assertDoesNotExist()
        onNodeWithTag("subtitles-burn-play").performScrollTo().performMouseInput { click() }
        onNodeWithTag("subtitles-burn-share").performScrollTo().performMouseInput { click() }
        onNodeWithTag("subtitles-burn-gallery").performScrollTo().performMouseInput { click() }
        waitUntil(timeoutMillis = 5_000) { f.share.calls.size == 3 }
        assertEquals(listOf("open video/mp4 $out", "share video/mp4 $out", "gallery ${out.substringAfterLast('/')}"), f.share.calls)
        onNodeWithTag("subtitles-burn-folder").assertDoesNotExist()
    }

    @Test fun desktopCancelThenOpenFolder() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val f = Fixture(shares = false, withFont = true)
        show(f)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-export-open").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("subtitles-export-open").performMouseInput { click() }
        onNodeWithTag("subtitles-burn-make").performMouseInput { click() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-burn-cancel").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("subtitles-burn-cancel").performScrollTo().performMouseInput { click() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-burn-cancelled").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("subtitles-burn-make").performMouseInput { click() }
        waitUntil(timeoutMillis = 5_000) { f.queue.jobs.size == 2 }
        val out = "$dir/" + f.task(1).output
        f.queue.states.getValue("job2").value = RenderState.Done(out, 1, 60_000, null)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-burn-folder").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("subtitles-burn-share").assertDoesNotExist()
        onNodeWithTag("subtitles-burn-folder").performScrollTo().performMouseInput { click() }
        assertEquals(listOf("folder $dir/exports"), f.share.calls)
    }

    @Test fun videoCardFits() {
        for (width in listOf(360, 1440)) for (font in listOf(1f, 2f)) for (language in UiLanguage.entries) {
            runDesktopComposeUiTest(width = width, height = 900) {
                val f = Fixture(shares = width < 840, withFont = true)
                show(f, font, language)
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-export-open").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("subtitles-export-open").performMouseInput { click() }
                onNodeWithTag("subtitles-burn-make").performMouseInput { click() }
                waitUntil(timeoutMillis = 5_000) { f.queue.jobs.size == 1 }
                f.queue.states.getValue("job1").value = RenderState.Running(0.5, 1.0)
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-burn-cancel").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(emptyList(), textFitProblems(width, font, strict = true), "$width × $font × $language running")
                f.queue.states.getValue("job1").value = RenderState.Done("$dir/" + f.task().output, 1, 60_000, null)
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-burn-done").fetchSemanticsNodes().isNotEmpty() }
                if (width >= 840) assertIconButton("subtitles-burn-folder", UiText.OPEN_FOLDER, language, iconDp = 16f) // as the .srt's Open folder
                assertEquals(emptyList(), textFitProblems(width, font, strict = true), "$width × $font × $language done")
            }
        }
    }

    @Test fun checkOneFrameUsesTheBurnAssAtTheLineMiddle() = runBlocking {
        val f = Fixture(shares = true, withFont = true)
        f.burner.checkFrame(f.doc, f.version, TextChoice.ORIGINAL, 1)
        val cmd = f.runner.commands.single()
        val name = f.out(TextChoice.ORIGINAL, "clean-medium-1l-short").removePrefix("exports/").removeSuffix(".mp4")
        assertEquals(listOf("ffmpeg", "-hide_banner", "-y", "-nostdin", "-ss", "5.000", "-copyts", "-i", "source/movie.mp4", "-vf"), cmd.take(10))
        assertEquals(".cache/burn/$name-5000.png", cmd.last())
        assertTrue(f.fs.exists("$dir/.cache/burn/$name.ass"))
        val frame = f.burner.state.value.frame!!
        assertEquals(1 to 5_000L, frame.line to frame.atMs)
        assertTrue(f.queue.jobs.isEmpty()) // a check is not a render
        f.burner.setLook(BurnLook.BOXED)
        assertNull(f.burner.state.value.frame) // a changed look needs a new check

        f.runner.fail = "No such filter: 'ass'"
        f.burner.checkFrame(f.doc, f.version, TextChoice.ORIGINAL, 0)
        assertNull(f.burner.state.value.frame)
        assertEquals("No such filter: 'ass'", f.burner.state.value.notice?.text(UiLanguage.ENGLISH))
        assertFalse(f.burner.state.value.checking)

        val g = Fixture(shares = true, withFont = false)
        g.burner.checkFrame(g.doc, g.version, TextChoice.BURMESE, 0)
        assertEquals(UiText.SUB_BURN_NO_FONT, g.burner.state.value.notice?.key)
        assertTrue(g.runner.commands.isEmpty())
    }

    @Test fun checkOneFrameInTheDialog() = runDesktopComposeUiTest(width = 412, height = 900) {
        val f = Fixture(shares = true, withFont = true)
        show(f)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-export-open").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("subtitles-export-open").performMouseInput { click() }
        onNodeWithTag("subtitles-check-line").assertTextContains("Line 1", substring = true)
        onNodeWithTag("subtitles-check-prev").assertIsNotEnabled()
        onNodeWithTag("subtitles-check-next").performScrollTo().performMouseInput { click() }
        onNodeWithTag("subtitles-check-line").assertTextContains("Line 2", substring = true)
        onNodeWithTag("subtitles-check-next").assertIsNotEnabled()
        onNodeWithTag("subtitles-check-frame").performScrollTo().performMouseInput { click() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-check-image").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("5.000", f.runner.commands.single()[5])
        onNodeWithTag("subtitles-export-ORIGINAL").performScrollTo().performMouseInput { click() }
        onNodeWithTag("subtitles-check-image").assertDoesNotExist()
    }

    @Test fun checkFrameFits() {
        for (width in listOf(360, 1440)) for (font in listOf(1f, 2f)) for (language in UiLanguage.entries) {
            runDesktopComposeUiTest(width = width, height = 900) {
                val f = Fixture(shares = width < 840, withFont = true)
                show(f, font, language)
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-export-open").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("subtitles-export-open").performMouseInput { click() }
                // P9-12d: ‹ › are chevron icon buttons with their names; Check one frame = picture, Make video = film.
                assertCardIcon("subtitles-export-video", My.breakSyllables(UiText.SUB_EXPORT_VIDEO.text(language)), 30f, "card-tile")
                assertIconOnly("subtitles-check-prev", UiText.SUB_PREV_LINE, language)
                assertIconOnly("subtitles-check-next", UiText.SUB_NEXT_LINE, language)
                onAllNodes(hasText("‹", substring = true) or hasText("›", substring = true), useUnmergedTree = true).assertCountEquals(0)
                assertIconButton("subtitles-check-frame", UiText.SUB_CHECK_FRAME, language)
                assertIconButton("subtitles-burn-make", UiText.SUB_MAKE_VIDEO, language, scroll = false) // F 5: the sticky
                onNodeWithTag("subtitles-check-frame").performScrollTo().performMouseInput { click() }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-check-image").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(emptyList(), textFitProblems(width, font, strict = true), "$width × $font × $language")
            }
        }
    }
}
