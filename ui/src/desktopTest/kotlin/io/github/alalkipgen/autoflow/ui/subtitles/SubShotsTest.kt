package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleOps
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.dubbing.DubScriptModel
import io.github.alalkipgen.autoflow.ui.dubbing.DubbingScreen
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Dest
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Shell
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlesFixture.Line
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.SceneSpan
import io.github.alalkipgen.autoflow.ai.TranscribeProvider
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.JsonSchema
import kotlinx.serialization.json.*
import io.github.alalkipgen.autoflow.ai.Transcript as AiTranscript
import io.github.alalkipgen.autoflow.ai.TranscriptSegment
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.core.recap.SubtitleTrack
import io.github.alalkipgen.autoflow.core.subtitles.MediaChunks
import io.github.alalkipgen.autoflow.media.probe.MediaInfo
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.recap.SceneAudioCutter
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import javax.imageio.ImageIO
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Group 5 (Subtitles) screenshots (plan §5 / §10): the samples' demo "Dune: Part Two" (12:40, 1920 × 1080, 23.98 fps,
 * 128 lines; #13 overlap, #14 not translated, #16 too short; playhead 0:42.3 on #12; #11 selected; 4 lines edited, not
 * saved) on a 360 dp phone at density 2, font 1.0 and 2.0, English and Burmese, the bundled Pyidaungsu; desktop 1440 × 900
 * at density 1. Runs only when `AUTOFLOW_GLASS_SHOTS_DIR` is set; writes `<language>/sub-<name>-<font>.png` and checks
 * that no text is cut.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class SubShotsTest(val language: UiLanguage, val font: Float) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}")
        fun cases() = listOf(UiLanguage.ENGLISH, UiLanguage.BURMESE).flatMap { l -> listOf(1f, 2f).map { arrayOf<Any>(l, it) } }
        private const val SCALE = 2f
        private const val WIDTH = 360
        private const val HEIGHT = 780
        const val DURATION = 760_000L
        /** The samples' lines #10–#17 (start, end, English, Burmese); #16 is 0.6 s (under `minCueMs` 0.7 s). */
        private val SAMPLE = mapOf(
            10 to Line(33_500, 36_000, "The spice must flow.", "စပိုက်စ် ဆက်စီးဆင်းနေရမယ်"),
            11 to Line(38_200, 40_900, "What is happening here?", "ဒီမှာ ဘာ ဖြစ်နေတာလဲ"),
            12 to Line(41_000, 44_600, "I will never leave you behind.", "ငါ မင်းကို ဘယ်တော့မှ ထားမသွားဘူး"),
            13 to Line(44_800, 47_400, "Then we ride at dawn.", "ဒါဆို အရုဏ်ဦးမှာ ထွက်ကြမယ်"),
            14 to Line(47_100, 50_100, "Where are the Fremen hiding?", null),
            15 to Line(50_600, 52_900, "Fear is the mind-killer.", "ကြောက်ရွံ့မှုက စိတ်ကို သတ်တတ်တယ်"),
            16 to Line(53_400, 54_000, "Go!", "သွား"),
            17 to Line(54_600, 57_800, "Long live the fighters, long live the Mahdi!", "စစ်သည်တွေ အသက်ရှည်ပါစေ၊ မဟ်ဒီ အသက်ရှည်ပါစေ"),
        )
        private val WORDS = SAMPLE.values.filter { it.my != null && it.endMs - it.startMs >= 1_000 }
        /** 128 lines: the sample's, the others borrow their words at the samples' starts. */
        internal fun lines(): List<Line> = (1..128).map { n ->
            SAMPLE[n] ?: run {
                val a = if (n < 10) 4_000L + (n - 1) * 3_200 else 59_000L + (n - 18) * 6_300
                val w = WORDS[n % WORDS.size]
                Line(a, a + 2_200 + (n % 3) * 300, w.text, w.my) // never over the next start: the sample's 3 checks only
            }
        }
        /** PR 5: the sample's video — 12:40, 1920 × 1080, 23.98 fps, with sound; text tracks 3 (eng, Recommended) and 4 (spa), a picture track 5. */
        val INFO = MediaInfo(DURATION, 1920, 1080, 23.976, "h264", "aac", listOf(
            SubtitleTrack(3, "subrip", "eng", "English (SDH)"), SubtitleTrack(4, "subrip", "spa", "Español"), SubtitleTrack(5, "hdmv_pgs_subtitle")))
        /** A Burmese font for the burn (Settings → Fonts). */
        val FONT: ByteArray by lazy {
            java.util.Base64.getMimeDecoder().decode(SubShotsTest::class.java.getResourceAsStream("/fonts/NotoSansMyanmar-subset.ttf.b64")!!.readBytes())
        }
        /** Check one frame's picture (no ffmpeg in the shots): the dusk frame with [line] burned in, Boxed. */
        fun frame(line: String): ByteArray {
            val w = 1280; val h = 720
            val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            img.createGraphics().run {
                setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                paint = java.awt.LinearGradientPaint(0f, 0f, 0f, h.toFloat(), floatArrayOf(0f, .45f, .75f, 1f),
                    arrayOf(java.awt.Color(0x0E1630), java.awt.Color(0x2A3A6A), java.awt.Color(0x1A1420), java.awt.Color(0x06080F)))
                fillRect(0, 0, w, h)
                color = java.awt.Color(0xF2F0E6); fillOval(820, 150, 70, 70)
                val dir = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "fonts") }
                    .first { File(it, "Pyidaungsu-Regular.ttf.b64").isFile }
                font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, java.io.ByteArrayInputStream(
                    java.util.Base64.getMimeDecoder().decode(File(dir, "Pyidaungsu-Regular.ttf.b64").readBytes()))).deriveFont(44f)
                val m = fontMetrics; val tw = m.stringWidth(line); val x = (w - tw) / 2; val y = h - 90
                color = java.awt.Color(0, 0, 0, 190); fillRect(x - 18, y - m.ascent - 6, tw + 36, m.ascent + m.descent + 12)
                color = java.awt.Color.WHITE; drawString(line, x, y)
                dispose()
            }
            return java.io.ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        }
        const val EDIT12 = "ငါ မင်းကို ဘယ်တော့မှ ထားခဲ့မှာ မဟုတ်ဘူး"
    }
    private val out = System.getenv("AUTOFLOW_GLASS_SHOTS_DIR")?.let(::File)

    /** The demo project and its models (nothing touches the disk). */
    private inner class Demo(dub: Boolean = false, cues: List<Line> = lines()) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        /** What ffprobe says of the video (PR 5: [INFO], its 3 tracks); null = cannot be read here. */
        var probe: suspend (String) -> MediaInfo? = { null }
        /** Writes the track's `.srt` (PR 5); default: no ffmpeg here. */
        var extract: suspend (String, Int, String) -> UiNotice? = { _, _, _ -> UiNotice(UiText.MOVIE_FFMPEG_UNAVAILABLE) }
        /** Transcribe (PR 5); null = not built here (the PlannedTile). */
        var transcribe: SubtitleTranscribeModel? = null
        /** Translate (PR 6); null = the fixture's (no Gemini). */
        var translate: SubtitleTranslateModel? = null
        val f = SubtitlesFixture(cues, DURATION, "Dune: Part Two", scope, dub = dub, probe = { probe(it) },
            extract = { v, i, o -> extract(v, i, o) })
        /** The sample's 12:40 video is over Dubbing's 10 min default (`dubbing.maxSourceMs`): the shots allow 15 min. */
        val script by lazy { DubScriptModel(f.session, f.fs, ToolsConfig().let { it.copy(dubbing = it.dubbing.copy(maxSourceMs = 900_000)) }) }
        init {
            update { p -> p.copy(subtitles = p.subtitles?.copy(video = p.subtitles!!.video!!.copy(file = "source/Dune.Part.Two.2024.mkv", fps = 23.976))) }
        }
        /** No decoder (sample 11 state 4): the video cannot play here. */
        var failing = false
        /** A paused decoder at the seek: a dusk picture (no VLC in the shots). */
        val player: SubtitlePlayer = { _, req, m, report ->
            LaunchedEffect(req) {
                report(if (failing) MovieSnapshot(phase = MoviePhase.FAILED, problem = "no decoder")
                    else MovieSnapshot(positionMs = req.sourceMs, durationMs = DURATION, phase = MoviePhase.PAUSED))
            }
            Box(m.background(Brush.verticalGradient(listOf(Color(0xFF0E1630), Color(0xFF2A3A6A), Color(0xFF1A1420), Color(0xFF06080F)))))
        }
        /** Export (PR 7): [shares] overrides the phone / desktop file buttons; [noBurner] = no video export here (PlannedTile). */
        var shares: Boolean? = null
        var noBurner = false
        /** A Burmese font in Settings (else Make video says "No Burmese font"); Check one frame waits forever with [holdFrame]. */
        var font = true
        var holdFrame = false
        val queue = SubtitlesFixture.Queue()
        /** The Render package card at the bottom of Export (no packing in the shots). */
        val packages = io.github.alalkipgen.autoflow.ui.pack.RenderPackageModel(f.session, f.fs, io.github.alalkipgen.autoflow.platform.JvmRenderPackages(),
            null, { io.github.alalkipgen.autoflow.core.recap.Lexicon(emptyList()) }, null, scope)
        private var exporter: SubtitleExportModel? = null
        private var burner: SubtitleBurnModel? = null
        fun exporter(phone: Boolean) = exporter ?: f.exporter(shares ?: phone).also { exporter = it }
        fun burner(phone: Boolean): SubtitleBurnModel? = if (noBurner) null else burner ?: f.burner(shares ?: phone, queue,
            ToolRunner { command, workDir, _, _, _ ->
                if (holdFrame) awaitCancellation()
                f.fs.writeDurably("$workDir/${command.last()}", frame(lines()[11].my!!))
                ToolExec.Done(0, "", emptyList(), 1, false)
            }, if (font) FONT else null).also { burner = it }
        /** The last started video's job → [state]. */
        fun job(state: RenderState) { queue.states.getValue("job${queue.jobs.size}").value = state }
        fun update(change: (io.github.alalkipgen.autoflow.core.model.Project) -> io.github.alalkipgen.autoflow.core.model.Project) {
            val open = f.session.open!!
            check(f.session.update(open.dir, open.project.id, change) is CoreResult.Ok)
        }
        fun noLines() = update { it.copy(subtitles = it.subtitles?.copy(current = null)) }
        /** PR 5: the source page as the sample opens it — no lines yet, the video's 3 tracks, Transcribe built. */
        fun source(withLines: Boolean = false, chunkMs: Long? = null, gemini: TranscribeProvider = Gemini(),
            cutter: SceneAudioCutter? = null, pauses: suspend (String, String) -> List<MediaChunks.Pause>? = { _, _ -> emptyList() }) {
            if (!withLines) noLines()
            probe = { INFO }
            val config = ToolsConfig().let { c -> chunkMs?.let { c.copy(transcribe = c.transcribe.copy(chunkMaxMs = it)) } ?: c }
            val usage = io.github.alalkipgen.autoflow.ai.usage.UsageCounter(f.fs, "/app/usage.json")
            kotlinx.coroutines.runBlocking { repeat(4) { usage.record("gemini-test") } } // the sample's "Today: 4 requests."
            transcribe = if (cutter == null) f.transcribe(gemini, config, pauses, usage = usage) else f.transcribe(gemini, config, pauses, cutter, usage)
        }
        /** PR 6: Translate over [gemini] in batches of 10 (the sample's 128 lines = 13 Gemini requests). */
        fun translating(gemini: TextProvider = Tr()) {
            translate = f.translate(gemini, ToolsConfig().let { it.copy(translate = it.translate.copy(batchLines = 10)) })
        }
        /** The editor the screen shows ([restoreOffer] swaps in a new one, as after a restart). */
        var editor = f.editor
        /** Sample 02 state 5: unsaved edits from an earlier session — a draft file, then a new editor that offers it back. */
        fun restoreOffer() {
            f.editor.open()
            val draft = f.editor.state.value.draft!!
            val changed = (SubtitleOps.setTextMy(draft.doc, "c12", EDIT12) as io.github.alalkipgen.autoflow.core.subtitles.Edit.Done).doc
            val file = io.github.alalkipgen.autoflow.core.subtitles.DraftFile(base = draft.base, savedAtMs = 1_764_191_640_000, doc = changed)
            f.fs.writeDurably("${f.dir}/.cache/st-draft.json", io.github.alalkipgen.autoflow.core.model.CoreJson.encodeToString(
                io.github.alalkipgen.autoflow.core.subtitles.DraftFile.serializer(), file).encodeToByteArray())
            editor = SubtitleEditorModel(f.session, f.fs, scope)
        }
        /** The samples' 4 edited, unsaved lines (#12 the Burmese line the sample shows). */
        fun dirty() {
            f.editor.open()
            f.editor.edit { SubtitleOps.setTextMy(it, "c12", EDIT12) }
            f.editor.edit { SubtitleOps.setTextMy(it, "c15", "ကြောက်ရွံ့မှုဟာ စိတ်ကို သတ်တတ်တယ်") }
            f.editor.edit { SubtitleOps.setText(it, "c17", "Long live the fighters! Long live the Mahdi!") }
            f.editor.edit { SubtitleOps.setTextMy(it, "c10", "စပိုက်စ် ဆက်ပြီး စီးဆင်းနေရမယ်") }
        }
        override fun close() { scope.cancel() }
    }

    private fun UiLanguage.code() = if (this == UiLanguage.BURMESE) "my" else "en"

    private fun write(name: String, image: java.awt.image.BufferedImage) {
        ImageIO.write(image, "png", File(out!!, "${language.code()}/sub-$name-$font.png").apply { parentFile.mkdirs() })
    }

    @Composable private fun Locals(width: Int, scale: Float, content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(scale, font), LocalUiLanguage provides language,
            LocalUiFontFamily provides BurmeseTestFont.family, content = content)

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }
    private fun ComposeUiTest.clickIf(tag: String) { if (onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) click(tag) }

    /** The screen as `AppShell` draws it (the Projects tab with an open editor). */
    private fun screen(d: Demo, width: Int, scale: Float, dub: Boolean): @Composable () -> Unit = {
        Locals(width, scale) {
            CompositionLocalProvider(io.github.alalkipgen.autoflow.ui.pack.LocalRenderPackages provides d.packages) {
            StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat(), Modifier.background(AutoFlowColors.Background), editor = true) {
                if (dub) DubbingScreen(d.script, d.f.model, d.f.session, d.editor, onBack = {}, player = d.player, transcribe = d.transcribe,
                    translate = d.translate ?: d.f.translate)
                else SubtitlesScreen(d.f.model, d.f.session, d.editor, player = d.player, onBack = {}, exporter = d.exporter(width < 600),
                    burner = d.burner(width < 600), transcribe = d.transcribe, translate = d.translate ?: d.f.translate)
            }
            }
        }
    }

    /** #11 selected (a tap: select + seek), then the playhead at 0:42.3 (on #12), the list at #10. */
    private fun ComposeUiTest.demoState() = demoState(42_300)

    /** As [demoState] with the playhead at [atMs]; [play] = Play pressed (sample 01: playing, no big Play). */
    private fun ComposeUiTest.demoState(atMs: Long, play: Boolean = false) {
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-cues").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("subtitles-cues").performScrollToKey("c11"); waitForIdle()
        click("subtitles-cue-c11")
        onNodeWithTag("subtitles-cues").performScrollToIndex(0); waitForIdle() // at font 2.0 the video tops the list
        onAllNodesWithTag("subtitles-scrubber").onFirst().performSemanticsAction(SemanticsActions.SetProgress) { it((atMs + .5f) / DURATION) }
        waitForIdle()
        if (play) { click("subtitles-play"); onNodeWithTag("subtitles-cues").performScrollToIndex(0); waitForIdle() }
    }

    /**
     * One shot after [prep] and [open]: phone 360 × [height] dp (density 2), desktop 1440 × 900; [only] = that node with
     * 8 dp around it (full width); [crop] = from the top to that node's bottom + 8 dp.
     */
    private fun shot(name: String, desktop: Boolean = false, dub: Boolean = false, height: Int = HEIGHT, width: Int = WIDTH,
        failing: Boolean = false, window: Boolean = false, dialog: Boolean = false, pad: Int = 8, view: TextChoice = TextChoice.BURMESE,
        cues: List<Line> = lines(), prep: Demo.() -> Unit = {},
        only: String? = null, crop: String? = null, open: ComposeUiTest.() -> Unit = {}) {
        if (out == null) return
        val w = if (desktop) 1440 else width
        val h = if (desktop) 900 else height
        val scale = if (desktop) 1f else SCALE
        runDesktopComposeUiTest(width = (w * scale).toInt(), height = (h * scale).toInt()) {
            Demo(dub, cues).use { d ->
                d.f.editor.setView(TextChoice.BURMESE) // the samples show the Burmese view
                d.prep()
                if (failing) d.failing = true
                // [view] after the editor opened (opening sets its own view): the samples show the Burmese view.
                setContent(screen(d, w, scale, dub)); waitForIdle(); d.editor.setView(view); waitForIdle(); open(); waitForIdle()
                // [window]: the Fullscreen dialog (its own root, over the screen).
                val roots = onAllNodes(isRoot())
                val image = (if (window) roots[roots.fetchSemanticsNodes().lastIndex] else roots[0]).captureToImage().toAwtImage().let { base ->
                    if (!dialog || roots.fetchSemanticsNodes().size < 2) base
                    else { // [dialog]: the screen with the dialog window (its scrim and card) drawn over it
                        val top = roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().toAwtImage()
                        java.awt.image.BufferedImage(base.width, base.height, java.awt.image.BufferedImage.TYPE_INT_ARGB).apply {
                            createGraphics().run { drawImage(base, 0, 0, null); drawImage(top, (base.width - top.width) / 2, (base.height - top.height) / 2, null); dispose() }
                        }
                    }
                }
                val tag = only?.split('|')?.firstOrNull { onAllNodesWithTag(it.substringBefore("..")).fetchSemanticsNodes().isNotEmpty() }
                if (tag != null && ".." in tag) { // from the top of the first node to the bottom of the second
                    val (a, b) = tag.split("..")
                    val top = (onAllNodesWithTag(a).onFirst().fetchSemanticsNode().boundsInRoot.top - pad * scale).toInt().coerceAtLeast(0)
                    val bottom = (onAllNodesWithTag(b).onFirst().fetchSemanticsNode().boundsInRoot.bottom + pad * scale).toInt().coerceAtMost(image.height)
                    write(name, image.getSubimage(0, top, image.width, bottom - top))
                } else if (tag != null) { // [pad] dp around it (more when the tag is on a text inside its card)
                    val box = onAllNodesWithTag(tag).onFirst().fetchSemanticsNode().boundsInRoot
                    val top = (box.top - pad * scale).toInt().coerceAtLeast(0)
                    write(name, image.getSubimage(0, top, image.width, ((box.bottom + pad * scale).toInt().coerceAtMost(image.height)) - top))
                } else if (crop != null) {
                    val bottom = (onAllNodesWithTag(crop).onFirst().fetchSemanticsNode().boundsInRoot.bottom + 8 * scale).toInt().coerceAtMost(image.height)
                    write(name, image.getSubimage(0, 0, image.width, bottom))
                } else write(name, image)
                val problems = textFitProblems((w * scale).toInt(), font, strict = true, minNodes = 3)
                if (System.getenv("AUTOFLOW_SHOTS_BEFORE") == null) assertEquals(emptyList(), problems, "$name ${language.code()} $font")
            }
        }
    }

    /** PR 2 (G, 🆕-1, 🆕-2; samples 01 / 11 / 11b / 11c / 12): the preview card in its states, Fullscreen portrait / landscape, desktop. */
    @Test fun preview() {
        val card = "subtitles-preview-card"
        shot("01-editor-playing", prep = { dirty() }) { demoState(42_300, play = true) }
        shot("11-playing", prep = { dirty() }, only = card) { demoState(42_300, play = true) }
        shot("11-paused-between", prep = { dirty() }, only = card) { demoState(37_200) }
        shot("11-view-original", view = TextChoice.ORIGINAL, prep = { dirty() }, only = card) { demoState(42_300) }
        shot("11-unavailable", prep = { dirty() }, failing = true, only = card) { demoState(42_300) }
        if (font == 1f) shot("12-desktop-editor-playing", desktop = true, prep = { dirty() }) { demoState(42_300, play = true) }
        // Last: there is no Fullscreen before Group 5 (the before shots then show the screen as it was).
        shot("11b-fullscreen-portrait", prep = { dirty() }, window = true) {
            demoState(42_300, play = true); clickIf("subtitles-preview-fullscreen")
        }
        shot("11c-fullscreen-landscape", width = 780, height = 360, prep = { dirty() }, window = true) {
            demoState(42_300, play = true); clickIf("subtitles-preview-fullscreen")
        }
    }

    /** PR 3 (A 4–7, A 9–10, 🆕-3, 🆕-6; samples 01 / 02): the list as opened, the sticky bars, Shift times open, the Restore card, the views. */
    @Test fun list() {
        val bar = "sticky-action-bar"
        shot("01-list", height = 1669, prep = { dirty() }) {
            demoState(42_300, play = true); onNodeWithTag("subtitles-cues").performScrollToKey("c10"); waitForIdle()
        }
        shot("02-sticky-saved", only = bar) { demoState() }
        shot("02-sticky-dirty", prep = { dirty() }, only = bar) { demoState() }
        shot("02-shift-open", height = 1100, prep = { dirty() }, only = "subtitles-view-ORIGINAL..subtitles-shift-card") {
            demoState() // From line 11 (the selected line)
            onNodeWithTag("subtitles-cues").performScrollToIndex(0); waitForIdle()
            onNodeWithTag("subtitles-cues").performScrollToNode(hasTestTag("subtitles-shift")); waitForIdle()
            click("subtitles-shift"); clickIf("subtitles-shift-from")
            onNodeWithTag("subtitles-cues").performScrollToNode(hasTestTag("subtitles-shift-card")); waitForIdle()
        }
        shot("02-view-original", view = TextChoice.ORIGINAL, prep = { dirty() }, only = "subtitles-cue-c12") {
            demoState(); onNodeWithTag("subtitles-cues").performScrollToKey("c12"); waitForIdle()
        }
        shot("02-view-both", view = TextChoice.BOTH, prep = { dirty() }, only = "subtitles-cue-c12") {
            demoState(); onNodeWithTag("subtitles-cues").performScrollToKey("c12"); waitForIdle()
        }
        shot("02-checks", height = if (font == 1f) 1100 else 2200, prep = { dirty() }, only = "subtitles-cue-c13..subtitles-cue-c16") {
            demoState(); onNodeWithTag("subtitles-cues").performScrollToKey("c13"); waitForIdle()
        }
    }

    /** PR 1 (A 1–2, A 8, 🆕-8; samples 01 / 02 / 13a / 13b / 12): the top bar dirty + Undo / Redo, clean, the notice callout; Dubbing steps 1–2. */
    /** PR 3 (A 10): the Restore card as after a restart (below the preview card: a taller window at font 2.0). */
    @Test fun listRestore() {
        shot("02-restore", height = if (font == 1f) HEIGHT else 2200, prep = { restoreOffer() }, only = "subtitles-restore") {
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-restore").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    /** PR 4 (B; samples 03 / 03b): the Edit line panel under #12 (paused at 0:41.0), its panel alone, the first line (no Previous). */
    @Test fun editLine() {
        val tall = if (font == 1f) 1659 else 3400
        fun ComposeUiTest.open(id: String, top: String) {
            onNodeWithTag("subtitles-cues").performScrollToKey(id); waitForIdle()
            click("subtitles-cue-edit-$id")
            onNodeWithTag("subtitles-cues").performScrollToKey(top); waitForIdle()
        }
        shot("03-edit-line", height = 1659, prep = { dirty() }) { demoState(41_000); open("c12", "c11") }
        shot("03-edit-panel", height = tall, prep = { dirty() }, only = "subtitles-edit-panel") { demoState(41_000); open("c12", "c12") }
        shot("03b-first-line", height = tall, prep = { dirty() }, only = "subtitles-edit-panel") { demoState(41_000); open("c1", "c1") }
    }

    /** PR 4 (03b piece 2): a time that is not valid → the amber notice in the header, the old time kept. */
    @Test fun editLineNotice() {
        val tall = if (font == 1f) 1659 else 3400
        fun ComposeUiTest.open(id: String, top: String) {
            onNodeWithTag("subtitles-cues").performScrollToKey(id); waitForIdle()
            click("subtitles-cue-edit-$id")
            onNodeWithTag("subtitles-cues").performScrollToKey(top); waitForIdle()
        }
        shot("03b-time-notice", height = tall, prep = { dirty() }, only = "subtitles-editor-notice") {
            demoState(41_000); open("c12", "c12")
            val time = hasTestTag("subtitles-start-time") // the field itself now; before PR 4 its box
            onNode(hasSetTextAction() and (time or hasAnyAncestor(time))).performTextReplacement("abc"); click("subtitles-done")
            onNodeWithTag("subtitles-cues").performScrollToIndex(0); waitForIdle()
        }
    }

    /** A Gemini that answers each chunk with one line, waits forever on chunk [hold], and is busy (429) on chunk [busyAt]. */
    private class Gemini(val hold: Int? = null, val busyAt: Int? = null) : TranscribeProvider {
        @Volatile var n = 0
        override suspend fun transcribe(audio: ByteArray, lang: String, mime: String, lines: List<String>): AiResult<AiTranscript> = error("not used")
        override suspend fun transcribeScenes(audio: ByteArray, mime: String, scenes: List<SceneSpan>, lang: String?): AiResult<List<List<TranscriptSegment>>> {
            n++
            if (n == hold) awaitCancellation()
            if (n == busyAt) return AiResult.RateLimited(null, "429 Resource exhausted")
            return AiResult.Ok(listOf(listOf(TranscriptSegment(1_000, 3_500, "Line $n"))), Usage("gemini-test"))
        }
    }

    private fun ComposeUiTest.waitTag(tag: String) =
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** PR 5 (C; samples 04 / 05): the source page, its states; the no-video box. */
    @Test fun source() {
        val src = "subtitles-source"
        shot("04-source", height = if (font == 1f) HEIGHT else 1700, prep = { source() }) { waitTag("subtitles-use-track") }
        shot("05-reading", height = 1700, prep = { source(); probe = { awaitCancellation() } }, only = src)
        shot("05-no-tracks", height = 1700, prep = { source(); probe = { INFO.copy(subtitleTracks = emptyList()) } }, only = src) {
            waitTag("subtitles-tracks-none")
        }
        shot("05-unreadable", height = 1700, prep = { source(); probe = { null } }, only = src) { waitTag("subtitles-tracks-unknown") }
        shot("05-busy", height = 1700, prep = { source(); extract = { _, _, _ -> awaitCancellation() } }, only = "subtitles-busy..$src") {
            waitTag("subtitles-use-track"); click("subtitles-use-track"); waitTag("subtitles-busy")
        }
        shot("05-notice-failed", height = 1700, prep = {
            source(); extract = { _, _, out -> f.fs.writeDurably(out, "1\n00:00:01,000 --> 00:00:02,000\nOne\n\nnot a cue\n".encodeToByteArray()); null }
        }, only = "subtitles-notice", pad = 26) { waitTag("subtitles-use-track"); click("subtitles-use-track"); waitTag("subtitles-notice") }
        shot("05-again", height = 1700, prep = { source(withLines = true) }, only = src) {
            waitTag("subtitles-change-source"); click("subtitles-change-source"); waitTag("subtitles-source-cancel")
        }
        shot("05-no-sound", height = 1700, prep = {
            source(); update { p -> p.copy(subtitles = p.subtitles?.copy(video = p.subtitles!!.video!!.copy(hasAudio = false))) }
        }, only = src) { waitTag("subtitles-transcribe-no-sound") }
        shot("05-planned", height = 1700, prep = { source(); transcribe = null }, only = "subtitles-transcribe-planned") { waitTag("subtitles-use-track") }
        shot("05-no-video", height = 1700, prep = { source(); update { p -> p.copy(subtitles = p.subtitles?.copy(video = null)) } },
            only = "subtitles-no-video", pad = 84)
    }

    /** PR 5 (D; samples 07a / 07c): the Transcribe dialog — first time, and again over lines (a new version). */
    @Test fun transcribeDialog() {
        val tall = if (font == 1f) HEIGHT else 1700
        shot("07a-dialog-transcribe", height = tall, prep = { source() }, window = true, dialog = true) {
            waitTag("subtitles-transcribe"); click("subtitles-transcribe"); waitTag("subtitles-transcribe-confirm")
        }
        shot("07c-dialog-transcribe-again", height = tall, prep = { source(withLines = true) }, window = true, dialog = true) {
            waitTag("subtitles-change-source"); click("subtitles-change-source"); click("subtitles-transcribe"); waitTag("subtitles-transcribe-again")
        }
    }

    /** PR 5 (D; sample 06): finding the pauses, the job running / paused (Gemini busy) / stopped (no ffmpeg), the notices after. */
    @Test fun transcribeJob() {
        val job = "subtitles-transcribe-job"
        fun ComposeUiTest.start() {
            waitTag("subtitles-transcribe"); click("subtitles-transcribe"); waitTag("subtitles-transcribe-start"); click("subtitles-transcribe-start")
        }
        shot("06-finding", height = 1700, prep = { source(pauses = { _, _ -> awaitCancellation() }) }, only = "subtitles-transcribe-finding", pad = 44) {
            waitTag("subtitles-transcribe"); click("subtitles-transcribe"); waitTag("subtitles-transcribe-finding")
        }
        val running = Gemini(hold = 3)
        shot("06-running", height = 1700, prep = { source(chunkMs = 60_000, gemini = running) }, only = job) {
            start(); waitUntil(timeoutMillis = 15_000) { running.n >= 3 }; waitTag("subtitles-transcribe-pause")
        }
        shot("06-paused-busy", height = 1700, prep = { source(chunkMs = 60_000, gemini = Gemini(busyAt = 6)) }, only = job) {
            start(); waitTag("subtitles-transcribe-resume")
        }
        shot("06-stopped", height = 1700, prep = {
            var cuts = 0
            source(chunkMs = 60_000, cutter = SceneAudioCutter { _, chunk, out, _ ->
                if (++cuts == 6) error(VideoTranscriber.NO_FFMPEG)
                f.fs.writeDurably(out, chunk.ranges.joinToString { "${it.inMs}-${it.outMs}" }.encodeToByteArray()); null
            })
        }, only = job) { start(); waitTag("subtitles-transcribe-resume") }
        shot("06-done", height = 1700, prep = { source() }, only = "subtitles-transcribe-notice", pad = 26) {
            start(); waitTag("subtitles-transcribe-notice")
        }
        shot("06-cancelled", height = 1700, prep = { source(chunkMs = 60_000, gemini = Gemini(hold = 2)) }, only = "subtitles-transcribe-notice",
            pad = 26) {
            start(); waitTag("subtitles-transcribe-cancel"); click("subtitles-transcribe-cancel"); waitTag("subtitles-transcribe-notice")
        }
    }

    /**
     * A Gemini that answers each batch in Burmese, waits forever on request [hold], is busy (429) on request [busyAt],
     * stops on request [failAt] with [failWith] ("" = no detail), and answers the batch that starts at line [bad] in the
     * wrong order (asked again once, then that batch is not translated).
     */
    private class Tr(val hold: Int? = null, val busyAt: Int? = null, val failAt: Int? = null, val failWith: String? = null,
        val bad: String? = null) : TextProvider {
        @Volatile var n = 0
        override suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> {
            n++
            if (n == hold) awaitCancellation()
            if (n == busyAt) return AiResult.RateLimited(null, "429 Resource exhausted")
            if (n == failAt) throw IllegalStateException(failWith)
            val ids = Json.parseToJsonElement(prompt.substringBefore("\n\n")).jsonObject["lines"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            val out = if (ids.firstOrNull() == bad) ids.reversed() else ids
            return AiResult.Ok(buildJsonObject { putJsonArray("lines") { out.forEach { id -> addJsonObject { put("id", id); put("textMy", "မြန်မာ $id") } } } },
                Usage("gemini-test"))
        }
    }

    /** PR 6 (E, 🆕-4; samples 07b / 08): the Translate dialog with two names; the job running / paused / stopped; the notices. */
    @Test fun translateDialog() {
        val tall = if (font == 1f) HEIGHT else 1700
        shot("07b-dialog-translate", height = tall, prep = { translating() }, window = true, dialog = true) {
            demoState(); click("subtitles-translate"); waitTag("subtitles-translate-confirm")
            listOf("Paul" to "ပေါလ်", "Chani" to "ချာနီ").forEachIndexed { i, (from, my) ->
                click("subtitles-translate-name-add")
                fun type(tag: String, text: String) =
                    onNode(hasSetTextAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag)))).performTextInput(text)
                type("subtitles-translate-name-from-$i", from); type("subtitles-translate-name-my-$i", my); waitForIdle()
            }
        }
    }

    @Test fun translateJob() {
        val job = "subtitles-translate-job"
        fun ComposeUiTest.start() {
            demoState(); click("subtitles-translate"); waitTag("subtitles-translate-start"); click("subtitles-translate-start")
        }
        val running = Tr(hold = 4)
        shot("08-running", prep = { translating(running) }) {
            start(); waitUntil(timeoutMillis = 15_000) { running.n >= 4 }; waitTag(job); waitTag("subtitles-translate-cancel")
            onNodeWithTag("subtitles-cues").performScrollToIndex(0); waitForIdle()
        }
        val sticky = Tr(hold = 4)
        shot("08-sticky", prep = { translating(sticky) }, only = "sticky-action-bar", pad = 0) {
            start(); waitUntil(timeoutMillis = 15_000) { sticky.n >= 4 }; waitTag("subtitles-translate-cancel")
        }
        shot("08-paused-busy", height = 1700, prep = { translating(Tr(busyAt = 7)) }, only = job) { start(); waitTag("subtitles-translate-resume") }
        shot("08-stopped-no-gemini", height = 1700, prep = { translating(Tr(failAt = 7, failWith = SubtitleTranslateJob.NO_GEMINI)) }, only = job) {
            start(); waitTag("subtitles-translate-resume")
        }
        shot("08-stopped-no-detail", height = 1700, prep = { translating(Tr(failAt = 7, failWith = "")) }, only = job) { start(); waitTag("subtitles-translate-resume") }
        shot("08-done", height = 1700, prep = { translating() }, only = "subtitles-translate-notice", pad = 26) {
            start(); waitTag("subtitles-translate-notice")
        }
        shot("08-not-all", height = 1700, prep = { translating(Tr(bad = "c41")) }, only = "subtitles-translate-notice..subtitles-translate-retry") {
            start(); waitTag("subtitles-translate-retry")
        }
        shot("08-nothing", height = 1700, prep = { translating() }, only = "subtitles-translate-notice", pad = 26) {
            start(); waitTag("subtitles-translate-notice"); click("subtitles-translate"); waitForIdle()
        }
        val cancelled = Tr(hold = 2)
        shot("08-cancelled", height = 1700, prep = { translating(cancelled) }, only = "subtitles-translate-notice", pad = 26) {
            start(); waitUntil(timeoutMillis = 15_000) { cancelled.n >= 2 }; waitTag("subtitles-translate-cancel")
            click("subtitles-translate-cancel"); waitTag("subtitles-translate-notice")
        }
    }

    /**
     * PR 7 (F 1–7, 🆕-5; samples 09 / 10 / 12c): the Export page, the video's job / done / cancelled / failed, Check one
     * frame, the `.srt` and video notices, Original, the PlannedTile, the preview hint open; the desktop page.
     */
    @Test fun export() {
        val tall = if (font == 1f) 1700 else 3400
        var demo: Demo? = null
        fun Demo.keep() { demo = this }
        fun ComposeUiTest.openExport(lines: Boolean = true) {
            if (lines) {
                demoState(); onNodeWithTag("subtitles-cues").performScrollToKey("c12"); waitForIdle(); click("subtitles-cue-c12")
            } else waitTag("subtitles-export-open")
            click("subtitles-export-open"); waitTag("subtitles-export")
        }
        fun ComposeUiTest.see(tag: String) { waitTag(tag); onNodeWithTag(tag).performScrollTo(); waitForIdle() }
        fun ComposeUiTest.make(then: RenderState? = null) {
            click("subtitles-burn-make"); waitUntil(timeoutMillis = 5_000) { demo!!.queue.jobs.isNotEmpty() }
            then?.let { demo!!.job(it); waitForIdle() }
        }
        fun done() = RenderState.Done("${demo!!.f.dir}/" + demo!!.queue.jobs.last().mediaTask!!.output, 1, 60_000, null)
        shot("09-export", height = if (font == 1f) 1500 else 2600, window = true, dialog = true, prep = { keep() }) { openExport() }
        shot("10-making", height = tall, window = true, prep = { keep() }, only = "subtitles-burn-job") {
            openExport(); make(RenderState.Running(0.46, 1.0)); see("subtitles-burn-job")
        }
        shot("10-sticky-off", height = tall, window = true, prep = { keep() }, only = "subtitles-burn-make", pad = 12) {
            openExport(); make(RenderState.Running(0.46, 1.0)); waitTag("subtitles-burn-job")
        }
        shot("10-done-phone", height = tall, window = true, prep = { keep() }, only = "subtitles-burn-done..subtitles-burn-gallery", pad = 24) {
            openExport(); make(); demo!!.job(done()); see("subtitles-burn-gallery")
        }
        shot("10-done-desktop", height = tall, window = true, prep = { keep(); shares = false }, only = "subtitles-burn-done..subtitles-burn-folder", pad = 24) {
            openExport(); make(); demo!!.job(done()); see("subtitles-burn-folder")
        }
        shot("10-cancelled", height = tall, window = true, prep = { keep() }, only = "subtitles-burn-cancelled", pad = 20) {
            openExport(); make(RenderState.Running(0.46, 1.0)); see("subtitles-burn-cancel"); click("subtitles-burn-cancel"); see("subtitles-burn-cancelled")
        }
        shot("10-failed", height = tall, window = true, prep = { keep() }, only = "subtitles-burn-failed", pad = 20) {
            openExport(); make(RenderState.Failed("ffmpeg exited with code 1.")); see("subtitles-burn-failed")
        }
        shot("10-checking", height = tall, window = true, prep = { keep(); holdFrame = true }, only = "subtitles-check-prev..subtitles-check-frame", pad = 20) {
            openExport(); see("subtitles-check-frame"); click("subtitles-check-frame"); waitUntil(timeoutMillis = 5_000) { demo!!.burner(false)!!.state.value.checking }
        }
        shot("10-frame", height = tall, window = true, prep = { keep() }, only = "subtitles-check-prev..subtitles-check-image", pad = 48) {
            openExport(); see("subtitles-check-frame"); click("subtitles-check-frame"); see("subtitles-check-image")
        }
        shot("10-srt-saved", height = tall, window = true, only = "subtitles-export-srt") {
            openExport(); click("subtitles-export-downloads"); see("subtitles-export-notice")
        }
        val bad = lines().mapIndexed { i, l -> if (i == 11) l.copy(endMs = l.startMs - 400) else l }
        shot("10-srt-bad-times", height = tall, window = true, cues = bad, only = "subtitles-export-notice", pad = 20) {
            openExport(); click("subtitles-export-downloads"); see("subtitles-export-notice")
        }
        shot("10-srt-no-lines", height = tall, window = true, cues = emptyList(), only = "subtitles-export-notice", pad = 20) {
            openExport(lines = false); click("subtitles-export-downloads"); see("subtitles-export-notice")
        }
        shot("10-srt-desktop", height = tall, window = true, prep = { shares = false }, only = "subtitles-export-srt") {
            openExport(); click("subtitles-export-save"); see("subtitles-export-notice")
        }
        shot("10-original", window = true, dialog = true) { openExport(); click("subtitles-export-ORIGINAL") }
        shot("10-no-font", height = tall, window = true, prep = { keep(); font = false }, only = "subtitles-burn-notice", pad = 20) {
            openExport(); click("subtitles-burn-make"); see("subtitles-burn-notice")
        }
        shot("10-gallery", height = tall, window = true, prep = { keep() }, only = "subtitles-burn-notice", pad = 20) {
            openExport(); make(); demo!!.job(done()); see("subtitles-burn-gallery"); click("subtitles-burn-gallery"); see("subtitles-burn-notice")
        }
        shot("10-planned", height = tall, window = true, prep = { noBurner = true }, only = "subtitles-export-video-planned") { openExport() }
        shot("10-hint-open", height = tall, window = true, only = "subtitles-export-video") {
            openExport(); clickIf("burn-preview-info"); see("subtitles-export-video")
        }
        if (font == 1f) shot("12c-desktop-export", desktop = true, window = true, dialog = true) { openExport() }
    }

    /**
     * PR 8 (H 1–3, 🆕-7; samples 12 / 12b / 12c): the desktop editor (the Lines header, the bar's caption and fixed buttons), the
     * open Edit line, the Export page in two panes — 1440 × 900 at font 1.0 and 2.0.
     */
    @Test fun desktop() {
        shot("12-desktop-lines", desktop = true, prep = { dirty() }) { demoState() }
        shot("12b-desktop-edit-line", desktop = true, prep = { dirty() }) {
            demoState(41_000); onNodeWithTag("subtitles-cues").performScrollToKey("c12"); waitForIdle(); click("subtitles-cue-edit-c12")
        }
        var demo: Demo? = null
        shot("12c-desktop-export-job", desktop = true, window = true, dialog = true, prep = { demo = this }) {
            demoState(); onNodeWithTag("subtitles-cues").performScrollToKey("c12"); waitForIdle(); click("subtitles-cue-c12")
            click("subtitles-export-open"); waitTag("subtitles-export")
            click("subtitles-burn-make"); waitUntil(timeoutMillis = 5_000) { demo!!.queue.jobs.isNotEmpty() }
            demo!!.job(RenderState.Running(0.46, 1.0)); waitTag("subtitles-burn-job")
        }
    }

    @Test fun shell() {
        shot("01-editor", prep = { dirty() }) { demoState() }
        shot("01-editor-full", height = 1669, prep = { dirty() }) { demoState() }
        shot("02-bar-dirty", prep = { dirty() }, crop = "subtitles-top-bar") { demoState() }
        shot("02-bar-clean", crop = "subtitles-top-bar") { demoState() }
        shot("02-notice-saved", prep = { dirty(); check(f.editor.save()) }, only = "subtitles-editor-notice") { demoState() }
        shot("13a-dubbing-step-1", dub = true, height = 1314, prep = { source() }) { clickIf("dub-step-VIDEO") }
        shot("13b-dubbing-step-2", dub = true, height = 1314, prep = { dirty() }) { click("dub-step-TRANSLATE"); demoState() }
        if (font == 1f) {
            shot("12-desktop-editor", desktop = true, prep = { dirty() }) { demoState() }
            shot("12-desktop-dubbing-step-2", desktop = true, dub = true, prep = { dirty() }) { click("dub-step-TRANSLATE"); demoState() }
        }
    }
}
