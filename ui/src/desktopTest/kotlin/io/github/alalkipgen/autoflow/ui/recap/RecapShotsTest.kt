package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
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
        private val pictures = HashMap<String, ImageBitmap>()
        private fun picture(ms: Long) = InterstellarFixture.sceneAt(ms).let { scene ->
            synchronized(pictures) { pictures.getOrPut(scene) { org.jetbrains.skia.Image.makeFromEncoded(InterstellarFixture.picture(scene)).toComposeImageBitmap() } }
        }
        /** Decoder stand-in: after each seek or Play/Pause it reports the requested time (40 ms on while playing) and its picture. */
        private val player = PreviewPlayer(atOnce = true) { _, request, modifier, onFrame, onSnapshot ->
            val frame by rememberUpdatedState(onFrame)
            val snapshot by rememberUpdatedState(onSnapshot)
            val at = remember { longArrayOf(-1, 0) }
            LaunchedEffect(request.seekRevision, request.playing) {
                if (at[0] != request.seekRevision) { at[0] = request.seekRevision; at[1] = request.sourceMs }
                repeat(2) {
                    val ms = if (request.playing) at[1] + 40 else at[1]
                    snapshot(MovieSnapshot(ms, InterstellarFixture.MOVIE_MS, if (request.playing) MoviePhase.PLAYING else MoviePhase.PAUSED))
                    frame?.invoke(picture(ms))
                    delay(40)
                }
            }
            Box(modifier)
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
            val frames = RecapFrames(session, settings, fs, media)
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

    /** Output time [ms] through the scrubber (the owner's drag), then the picture. */
    private fun ComposeUiTest.seek(ms: Long) {
        val last = (252_000L - 1).toFloat()
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
        crop: String? = null, open: ComposeUiTest.() -> Unit = {}) {
        if (out == null) return
        var extra = 0
        if (full) runDesktopComposeUiTest(width = (width * SCALE).toInt(), height = (height * SCALE).toInt()) {
            Shots(ratio).use { s ->
                setContent(content(s, step, width)); waitForIdle(); ready(); open(); waitForIdle()
                extra = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNodes()
                    .maxByOrNull { it.boundsInRoot.height }?.config?.get(SemanticsProperties.VerticalScrollAxisRange)?.maxValue()?.toInt() ?: 0
            }
        }
        runDesktopComposeUiTest(width = (width * SCALE).toInt(), height = (height * SCALE).toInt() + extra) {
            Shots(ratio).use { s ->
                setContent(content(s, step, width)); waitForIdle(); ready(); open(); waitForIdle(); thumbnails()
                val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
                val box = crop?.let { onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
                write(name, if (box == null) image else image.getSubimage(box.left.toInt(), box.top.toInt(), box.width.toInt(), box.height.toInt()))
                assertEquals(emptyList(), textFitProblems((width * SCALE).toInt(), font, strict = true, minNodes = 3), "$name ${language.code()} $font")
            }
        }
    }

    @Test fun source() = shot("1-source", RecapStep.SOURCE)
    @Test fun story() = shot("2-story", RecapStep.STORY)
    @Test fun voice() = shot("3-voice", RecapStep.VOICE)
    @Test fun style() = shot("4-style", RecapStep.STYLE)
    @Test fun export() = shot("5-export", RecapStep.EXPORT)

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
