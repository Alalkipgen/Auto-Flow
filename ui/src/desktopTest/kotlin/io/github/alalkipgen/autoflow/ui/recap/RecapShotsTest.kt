package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.platform.ProcessRunner
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.Clipboard
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.LocalPreviewPlayer
import io.github.alalkipgen.autoflow.ui.components.PreviewPlayer
import io.github.alalkipgen.autoflow.ui.export.ExportModel
import io.github.alalkipgen.autoflow.ui.export.RecapFrames
import io.github.alalkipgen.autoflow.ui.export.StudioExportScreen
import io.github.alalkipgen.autoflow.ui.export.ExportUiState
import io.github.alalkipgen.autoflow.ui.export.ExportRun
import io.github.alalkipgen.autoflow.ui.export.PreviewFile
import io.github.alalkipgen.autoflow.ui.export.RenderCard
import io.github.alalkipgen.autoflow.ui.export.ExportPreviewCard
import io.github.alalkipgen.autoflow.ui.export.PreflightCard
import io.github.alalkipgen.autoflow.ui.export.ExactFramesCard
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ambientBackground
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.audio.LoudnessMeasurement
import io.github.alalkipgen.autoflow.core.recap.PreflightCheck
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Dest
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Shell
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Group 2 PR 1 screenshots (plan README-FIRST §3): steps 1–5 with the phone top preview, A `0-preview-states` and
 * D 25a–d, on a 360 dp phone at density 2, font 1.0 and 2.0, English and Burmese, the bundled Pyidaungsu, with the
 * sample's demo project ([InterstellarFixture]). The decoder is a stand-in that shows the sample's demo picture for the
 * requested movie time (the real desktop decoder needs a display); the Exact frame is the real ffmpeg render. Runs only
 * when `AUTOFLOW_GLASS_SHOTS_DIR` is set; writes `<language>/recap-<name>-<font>.png` and checks that no text is cut.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class RecapShotsTest(val language: UiLanguage, val font: Float) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}")
        fun cases() = listOf(UiLanguage.ENGLISH, UiLanguage.BURMESE).flatMap { l -> listOf(1f, 2f).map { arrayOf<Any>(l, it) } }
        private const val SCALE = 2f
        private const val WIDTH = 360
        private const val HEIGHT = 780
        /** The Source card's length: the demo's 14 picked scenes end to end. */
        private const val SOURCE_MS = 250_600L
        private val pictures = HashMap<String, ImageBitmap>()
        private fun picture(ms: Long) = InterstellarFixture.sceneAt(ms).let { scene ->
            synchronized(pictures) { pictures.getOrPut(scene) { org.jetbrains.skia.Image.makeFromEncoded(InterstellarFixture.picture(scene)).toComposeImageBitmap() } }
        }
        /**
         * Decoder stand-in: after each seek or Play/Pause it reports the requested time (40 ms on while playing) and its picture
         * (to the frame feed, or — no feed, the Source card's plain decoder — drawn by itself).
         */
        private val player = PreviewPlayer(atOnce = true) { _, request, modifier, onFrame, onSnapshot ->
            val frame by rememberUpdatedState(onFrame)
            val snapshot by rememberUpdatedState(onSnapshot)
            val at = remember { longArrayOf(-1, 0) }
            var shown by remember { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(request.seekRevision, request.playing) {
                if (at[0] != request.seekRevision) { at[0] = request.seekRevision; at[1] = request.sourceMs }
                repeat(2) {
                    val ms = if (request.playing) at[1] + 40 else at[1]
                    snapshot(MovieSnapshot(ms, InterstellarFixture.MOVIE_MS, if (request.playing) MoviePhase.PLAYING else MoviePhase.PAUSED))
                    frame?.invoke(picture(ms)) ?: run { shown = picture(ms) }
                    delay(40)
                }
            }
            Box(modifier) { shown?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) } }
        }
        private val pyidaungsu: File by lazy {
            val dir = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "fonts") }.first { File(it, "Pyidaungsu-Regular.ttf.b64").isFile }
            File(Files.createTempDirectory("recap-shots-font").toFile(), "Pyidaungsu-Regular.ttf").apply {
                writeBytes(Base64.getMimeDecoder().decode(File(dir, "Pyidaungsu-Regular.ttf.b64").readBytes()))
            }
        }
    }
    private val out = System.getenv("AUTOFLOW_GLASS_SHOTS_DIR")?.let(::File)

    /** The demo project opened like the app: Recap + Export models on one session, real ffmpeg for frames. */
    private class Shots(ratio: Ratio) : AutoCloseable {
        val root: File = Files.createTempDirectory("recap-shots").toFile()
        val fixture = InterstellarFixture.create(root, ratio = ratio)
        val fs = JvmFs()
        val current = CurrentProject()
        val session = ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, File(root, "index.json").path), current)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val settings = SettingsStore(fs, File(root, "settings.json").path)
        val recap: RecapModel
        val export: ExportModel
        init {
            current.set(OpenProject(fixture.dir.path, fixture.project))
            settings.update { it.copy(fontPaths = listOf(pyidaungsu.path)) }
            val locator = object : ToolLocator {
                override fun findFfmpeg() = System.getenv("AUTOFLOW_FFMPEG") ?: "ffmpeg"
                override fun findFfprobe() = System.getenv("AUTOFLOW_FFPROBE") ?: "ffprobe"
                override fun findVlc(): String? = null
                override fun isVlcDir(dir: String) = false
            }
            val runner = ToolRunner { cmd, wd, timeout, keep, line -> when (val r = ProcessRunner.run(cmd, File(wd), timeout, keep, line)) {
                is ProcessRunner.Exec.Done -> ToolExec.Done(r.exitCode, r.output, r.tail, r.wallMs, r.timedOut)
                is ProcessRunner.Exec.CannotStart -> ToolExec.CannotStart(r.message)
            } }
            val media = MediaTools(settings, locator, runner)
            val frames = RecapFrames(session, settings, fs, media, previewCacheDir = File(root, "cache/export-preview").path)
            val run = MutableStateFlow<RenderState>(RenderState.Queued)
            val queue = object : RenderQueue {
                override fun enqueue(job: RenderJob) = "render-1"
                override fun observe(id: String) = run
                override fun cancel(id: String) = Unit
            }
            val share = object : FileShare {
                override fun open(path: String, mime: String) = PlatformResult.Ok(Unit)
                override fun share(path: String, mime: String, title: String) = PlatformResult.Ok(Unit)
                override suspend fun saveToGallery(path: String, mime: String, displayName: String) = PlatformResult.Ok(displayName)
            }
            val clipboard = object : Clipboard { override fun copyText(text: String) = PlatformResult.Ok(Unit) }
            export = ExportModel(session, settings, fs, queue, share, null, scope, clipboard, frames = frames)
            // Real scene / block thumbnails (ffmpeg from the demo movie), as in the sample.
            recap = RecapModel(session, fs, media, null, scope, thumbnailCacheDir = File(root, "thumbs").path, frames = frames)
        }
        override fun close() { scope.cancel(); root.deleteRecursively() }
    }

    private fun UiLanguage.code() = if (this == UiLanguage.BURMESE) "my" else "en"

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    /** The top preview shows its picture (or, collapsed, its row). */
    private fun ComposeUiTest.ready() {
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("timeline-play").fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText(UiText.PREPARING_FRAME.text(language)).fetchSemanticsNodes().isEmpty() }
        waitForIdle()
    }

    /** Output time [ms] of a [durationMs] card through the scrubber (the owner's drag), then the picture. */
    private fun ComposeUiTest.seek(ms: Long, durationMs: Long = 252_000L) {
        val last = (durationMs - 1).toFloat()
        onNodeWithTag("timeline-scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it((ms + .5f) / last) }
        waitForIdle(); ready()
    }

    private fun ComposeUiTest.play() { click("timeline-play"); ready() }

    /**
     * Every scene / block thumbnail slot in the window has its frame (only those load; at most 20 s, a missing one stays
     * the placeholder).
     */
    private fun ComposeUiTest.thumbnails() {
        fun count(slot: Boolean) = onAllNodes(SemanticsMatcher("thumbnail") { n ->
            n.config.getOrNull(SemanticsProperties.TestTag)?.let { t ->
                (t.startsWith("block-thumbnail-") || t.startsWith("scene-thumbnail-") || t.startsWith("recap-mini-bar-thumbnail")) &&
                    t.endsWith("-slot") == slot
            } == true && !n.boundsInWindow.isEmpty
        }, useUnmergedTree = true).fetchSemanticsNodes().size
        runCatching { waitUntil(timeoutMillis = 20_000) { count(false) >= count(true) } }
        waitForIdle()
    }

    private fun ComposeUiTest.write(name: String, image: java.awt.image.BufferedImage) {
        ImageIO.write(image, "png", File(out!!, "${language.code()}/recap-$name-$font.png").apply { parentFile.mkdirs() })
    }

    private fun content(s: Shots, step: RecapStep, width: Int): @Composable () -> Unit = {
        CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(SCALE, font), LocalUiLanguage provides language,
            LocalUiFontFamily provides BurmeseTestFont.family, LocalPreviewPlayer provides player) {
            StudioV2Shell(StudioV2Dest.PROJECTS, {}, 2, width.toFloat(), editor = true) {
                val navigation = RecapNavigation({}, {}, {})
                if (step == RecapStep.EXPORT) StudioExportScreen(s.export, s.recap, width.toFloat(), navigation)
                else StudioRecapScreen(s.recap, step, width.toFloat(), {}, navigation)
            }
        }
    }

    /**
     * Two passes like SettingsShotsTest: the first measures the page's scroll range at 780 dp after [open], the second
     * draws the page at its full height ([full]) or at 780 dp, writes the PNG and checks the texts; [crop] = only that
     * node's bounds.
     */
    private fun shot(name: String, step: RecapStep, ratio: Ratio = Ratio.R16_9, full: Boolean = true, width: Int = WIDTH, height: Int = HEIGHT,
        crop: String? = null, prepare: (Shots) -> Unit = {}, area: (ComposeUiTest.() -> androidx.compose.ui.geometry.Rect)? = null,
        open: ComposeUiTest.() -> Unit = {}) {
        if (out == null) return
        var extra = 0
        if (full) runDesktopComposeUiTest(width = (width * SCALE).toInt(), height = (height * SCALE).toInt()) {
            Shots(ratio).use { s ->
                prepare(s)
                setContent(content(s, step, width)); waitForIdle(); ready(); open(); waitForIdle()
                extra = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNodes()
                    .maxByOrNull { it.boundsInRoot.height }?.config?.get(SemanticsProperties.VerticalScrollAxisRange)?.maxValue()?.toInt() ?: 0
            }
        }
        runDesktopComposeUiTest(width = (width * SCALE).toInt(), height = (height * SCALE).toInt() + extra) {
            Shots(ratio).use { s ->
                prepare(s)
                setContent(content(s, step, width)); waitForIdle(); ready(); open(); waitForIdle(); thumbnails()
                val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
                val box = crop?.let { onNodeWithTag(it).fetchSemanticsNode().boundsInRoot } ?: area?.invoke(this)
                write(name, if (box == null) image else image.getSubimage(box.left.toInt(), box.top.toInt(), box.width.toInt(), box.height.toInt()))
                assertEquals(emptyList(), textFitProblems((width * SCALE).toInt(), font, strict = true, minNodes = 3), "$name ${language.code()} $font")
            }
        }
    }

    /** A 1-source: the Source card (PR 3) plays the picked scenes (the demo's 14 picks, 4:10) — paused at 0:36, in scene #4. */
    @Test fun source() {
        shot("1-source", RecapStep.SOURCE) { seek(36_000, SOURCE_MS) }
        shot("1-source-card", RecapStep.SOURCE, crop = "recap-top-preview") { seek(36_000, SOURCE_MS) }
        shot("1-source-playing-mini-bar", RecapStep.SOURCE, full = false) {
            seek(36_000, SOURCE_MS); play()
            onNodeWithTag("source-content").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 900f * SCALE) }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("recap-mini-bar").fetchSemanticsNodes().isNotEmpty() }
            thumbnails()
        }
    }
    /**
     * C 11 (PR 6): the Voice step with block 3 open (header card · Script · Clips · Voice · Fix timing · Takes · Block tools ·
     * sticky Re-voice). C 12: the Block voice tools card in its states (Record tab of block 4 — its take is stale —, Clean,
     * Speak · More options open · caption timing editing) and the Story step's detail bottom (Back · Approve story).
     */
    @Test fun voiceDetail() {
        shot("11-voice-block-detail", RecapStep.VOICE) { openBlock(2) }
        val tools: ComposeUiTest.() -> androidx.compose.ui.geometry.Rect = {
            val top = onAllNodesWithText(UiText.V3_BLOCK_TOOLS.text(language)).fetchSemanticsNodes().first().boundsInRoot.top - 24 * SCALE
            val bottom = onNodeWithTag("sticky-action-bar").fetchSemanticsNode().boundsInRoot.top
            androidx.compose.ui.geometry.Rect(0f, top, WIDTH * SCALE, bottom)
        }
        shot("12-1-record-stale", RecapStep.VOICE, area = tools) { openBlock(3); tab(UiText.VOICE_TAB_RECORD) }
        shot("12-3-clean", RecapStep.VOICE, area = tools) { openBlock(2); tab(UiText.VOICE_TAB_CLEAN) }
        shot("12-4-more-options", RecapStep.VOICE, area = tools) {
            openBlock(2); onNode(hasContentDescription(UiText.V3_MORE_OPTIONS.text(language)) and hasStateDescription(UiText.V3_COLLAPSED.text(language))).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
        }
        shot("12-5-caption-timing-editing", RecapStep.VOICE, area = tools) {
            openBlock(2); onNode(hasContentDescription(UiText.V3_MORE_OPTIONS.text(language)) and hasStateDescription(UiText.V3_COLLAPSED.text(language))).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle(); onNodeWithText(UiText.EDIT_CAPTION_TIMING.text(language)).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
        }
        shot("12-8-story-detail-bottom", RecapStep.STORY, full = false) {
            click("block-select-2")
            onNodeWithTag("story-content").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            waitForIdle()
        }
    }

    private fun ComposeUiTest.openBlock(index: Int) {
        onNodeWithTag("voice-open-$index").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText(UiText.V3_LOADING_TAKES.text(language)).fetchSemanticsNodes().isEmpty() }
        waitForIdle()
    }

    private fun ComposeUiTest.tab(text: UiText) {
        onNode(hasText(io.github.alalkipgen.autoflow.ui.i18n.My.breakSyllables(text.text(language))) and hasClickAction()).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    @Test fun story() = shot("2-story", RecapStep.STORY)
    @Test fun voice() = shot("3-voice", RecapStep.VOICE)
    @Test fun style() = shot("4-style", RecapStep.STYLE)
    @Test fun export() = shot("5-export", RecapStep.EXPORT)

    /** A real 540p `exports/preview.mp4` (3 s of the demo movie), as after Preview 540p. */
    private fun makePreview(s: Shots) {
        val file = File(s.fixture.dir, "exports/preview.mp4").apply { parentFile.mkdirs() }
        val process = ProcessBuilder(System.getenv("AUTOFLOW_FFMPEG") ?: "ffmpeg", "-y", "-loglevel", "error", "-ss", "5",
            "-i", File(s.fixture.dir, "source/Interstellar.2014.mkv").path, "-t", "3", "-vf", "scale=960:540,format=yuv420p", "-r", "25",
            "-c:v", "libx264", "-preset", "ultrafast", "-an", file.path).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "preview ffmpeg failed: $log" }
    }

    private fun ComposeUiTest.poster() {
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("export-preview-poster").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
    }

    /** B 5-export after Preview 540p: the Render preview card; D 25c–d (PR 5): the same Fullscreen look for `export-preview-fullscreen`. */
    @Test fun exportPreview() {
        shot("5-export-preview-card", RecapStep.EXPORT, crop = "export-preview-card", prepare = { makePreview(it) }) { poster() }
        shot("25c-export-fullscreen-portrait", RecapStep.EXPORT, full = false, prepare = { makePreview(it) }) { poster(); click("export-preview-fullscreen") }
        shot("25d-export-fullscreen-landscape", RecapStep.EXPORT, full = false, width = HEIGHT, height = WIDTH, prepare = { makePreview(it) }) {
            poster(); click("export-preview-fullscreen")
        }
    }

    /**
     * B `5-export-states`: the other states of the Export cards, each the real card composable in that state — Render running
     * (low battery) · Render done · Render preview after Preview 540p (a real 540p file's poster) · Preflight all clear ·
     * Exact frames made (the real export-pipeline stills of the demo). The desktop has no inline video player, so Render done
     * shows its stand-in box where the phone shows ExoPlayer.
     */
    @Test fun exportStates() {
        if (out == null) return
        val tall = if (font > 1.5f) 6000 else 3400 // font 2.0: exact frames in one column
        runDesktopComposeUiTest(width = (WIDTH * SCALE).toInt(), height = (tall * SCALE).toInt()) {
            Shots(Ratio.R16_9).use { s ->
                makePreview(s)
                s.export.refresh()
                val dir = s.fixture.dir.path
                val output = File(s.fixture.dir, "exports/export.mp4").path
                fun run(state: RenderState) = ExportUiState(projectName = "Interstellar", run = ExportRun(dir, Quality.EXPORT, "render-1", output, state))
                val loudness = RenderState.Loudness(-14.0, LoudnessMeasurement(-19.3, -1.5, 7.0, -29.6, .2), null)
                val rights = PreflightCheck.Item("", PreflightCheck.Kind.INFO, reason = PreflightCheck.Reason.RIGHTS)
                val preview = File(s.fixture.dir, "exports/preview.mp4").let { PreviewFile(it.path, it.length()) }
                setContent {
                    CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(SCALE, font), LocalUiLanguage provides language,
                        LocalUiFontFamily provides BurmeseTestFont.family, LocalPreviewPlayer provides player) {
                        val state by s.export.state.collectAsState()
                        LaunchedEffect(Unit) { s.export.makeThumbnails() }
                        Column(Modifier.fillMaxWidth().ambientBackground().padding(12.dp).testTag("states"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            @Composable fun label(text: String) = AfText(text, color = AutoFlowColors.PrimaryText, type = AfType.Caption)
                            label("Render · running (low battery)")
                            RenderCard(run(RenderState.Running(.62, null)).copy(lowBattery = true), s.export, studio = true)
                            label("Render · done")
                            RenderCard(run(RenderState.Done(output, 96_000, 252_000, null, loudness)), s.export, studio = true)
                            label("Render preview · after Preview 540p")
                            ExportPreviewCard(preview, s.export, showOpen = true, glass = true)
                            label("Preflight · all clear")
                            PreflightCard(listOf(rights), glass = true)
                            label("Exact frames · made (2 columns)")
                            ExactFramesCard(state.thumbs, canMake = true, s.export, glass = true)
                        }
                    }
                }
                waitUntil(timeoutMillis = 240_000) { s.export.state.value.thumbs?.working == false }
                poster()
                val box = onNodeWithTag("states").fetchSemanticsNode().boundsInRoot
                val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
                write("5-export-states", image.getSubimage(0, 0, image.width, box.bottom.toInt().coerceAtMost(image.height)))
                assertEquals(null, s.export.state.value.thumbs?.failed)
                assertEquals(emptyList(), textFitProblems((WIDTH * SCALE).toInt(), font, strict = true, minNodes = 3), "5-export-states ${language.code()} $font")
            }
        }
    }

    /**
     * A `0-preview-states` 1–3 and 5–7 (4 = the mini bar, PR 2): the top card alone, measured on the full page so a font 2.0
     * card is never cut by the window; 7 (9:16) at the phone height, as its picture cap is 45 % of the window.
     */
    @Test fun previewStates() {
        val card = "recap-top-preview"
        shot("0-state-1-paused", RecapStep.STORY, full = true, crop = card)
        shot("0-state-2-playing", RecapStep.STORY, full = true, crop = card) { seek(52_000); play() }
        shot("0-state-3-collapsed", RecapStep.STORY, full = true, crop = card) { seek(52_000); click("recap-top-preview-collapse") }
        shot("0-state-5-exact", RecapStep.STORY, full = true, crop = card) {
            seek(126_000); click("timeline-exact")
            waitUntil(timeoutMillis = 120_000) {
                // The frame button is itself described "Exact frame": wait for the still in the picture.
                onAllNodes(hasContentDescription(UiText.EXACT_FRAME_DESCRIPTION.text(language)) and hasAnyAncestor(hasTestTag(card)) and
                    !hasClickAction()).fetchSemanticsNodes().isNotEmpty()
            }
            waitForIdle()
        }
        shot("0-state-6-style-edit", RecapStep.STYLE, full = true, crop = card) {
            click("style-section-LAYERS"); click("layer-edit-text-2"); ready()
        }
        shot("0-state-7-9x16", RecapStep.STORY, Ratio.R9_16, full = false, crop = card) { seek(70_000) }
    }

    /**
     * PR 2 (A §1 "Mini bar", "Sync with the lists"): 0-preview-states 4 = playing, scrolled down → the mini bar under the top
     * bar; Story with block 3 playing from its thumbnail (Cyan outline + Pause badge); Source with the scene badges.
     */
    @Test fun miniBarAndThumbnails() {
        shot("0-state-4-mini-bar", RecapStep.STORY, full = false) {
            seek(52_000); play()
            onNodeWithTag("story-content").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 700f * SCALE) }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("recap-mini-bar").fetchSemanticsNodes().isNotEmpty() }
            thumbnails()
        }
        shot("2-story-playing", RecapStep.STORY) { click("block-preview-2"); ready() }
    }

    /** D 25a ⋮ open on Story (playing at 0:48) · 25b Diagnostics · 25c Fullscreen portrait · 25d landscape. */
    @Test fun menuDiagnosticsFullscreen() {
        val playing: ComposeUiTest.() -> Unit = { seek(48_000); play() }
        shot("25a-menu-open", RecapStep.STORY, full = false) { playing(); click("project-overflow") }
        shot("25b-dialog-diagnostics", RecapStep.STORY, full = false) {
            playing(); click("project-overflow"); click("recap-diagnostics")
            onNode(hasSetTextAction() and (hasTestTag("timeline-seek-input") or hasAnyAncestor(hasTestTag("timeline-seek-input"))))
                .performTextReplacement("126000")
            waitForIdle()
        }
        shot("25c-fullscreen-portrait", RecapStep.STORY, full = false) { playing(); click("timeline-fullscreen"); ready() }
        shot("25d-fullscreen-landscape", RecapStep.STORY, full = false, width = HEIGHT, height = WIDTH) {
            playing(); click("timeline-fullscreen"); ready()
        }
    }
}
