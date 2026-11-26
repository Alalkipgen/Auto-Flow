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
import kotlinx.coroutines.cancel
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import javax.imageio.ImageIO
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
        /** A text-fit problem from before Group 5 that a later PR of the group fixes (see [shot]). */
        private const val KNOWN = "Dune.Part.Two.2024.mkv"
        const val EDIT12 = "ငါ မင်းကို ဘယ်တော့မှ ထားခဲ့မှာ မဟုတ်ဘူး"
    }
    private val out = System.getenv("AUTOFLOW_GLASS_SHOTS_DIR")?.let(::File)

    /** The demo project and its models (nothing touches the disk). */
    private inner class Demo(dub: Boolean = false) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val f = SubtitlesFixture(lines(), DURATION, "Dune: Part Two", scope, dub = dub)
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
        fun update(change: (io.github.alalkipgen.autoflow.core.model.Project) -> io.github.alalkipgen.autoflow.core.model.Project) {
            val open = f.session.open!!
            check(f.session.update(open.dir, open.project.id, change) is CoreResult.Ok)
        }
        fun noLines() = update { it.copy(subtitles = it.subtitles?.copy(current = null)) }
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
            StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat(), Modifier.background(AutoFlowColors.Background), editor = true) {
                if (dub) DubbingScreen(d.script, d.f.model, d.f.session, d.f.editor, onBack = {}, player = d.player, translate = d.f.translate)
                else SubtitlesScreen(d.f.model, d.f.session, d.f.editor, player = d.player, onBack = {}, exporter = d.f.exporter(width < 600),
                    burner = d.f.burner(width < 600), translate = d.f.translate)
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
        failing: Boolean = false, window: Boolean = false, prep: Demo.() -> Unit = {},
        only: String? = null, crop: String? = null, open: ComposeUiTest.() -> Unit = {}) {
        if (out == null) return
        val w = if (desktop) 1440 else width
        val h = if (desktop) 900 else height
        val scale = if (desktop) 1f else SCALE
        runDesktopComposeUiTest(width = (w * scale).toInt(), height = (h * scale).toInt()) {
            Demo(dub).use { d ->
                d.f.editor.setView(TextChoice.BURMESE) // the samples show the Burmese view
                d.prep()
                if (failing) d.failing = true
                setContent(screen(d, w, scale, dub)); waitForIdle(); open(); waitForIdle()
                // [window]: the Fullscreen dialog (its own root, over the screen).
                val roots = onAllNodes(isRoot())
                val image = (if (window) roots[roots.fetchSemanticsNodes().lastIndex] else roots[0]).captureToImage().toAwtImage()
                val tag = only?.split('|')?.firstOrNull { onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
                if (tag != null) {
                    val box = onAllNodesWithTag(tag).onFirst().fetchSemanticsNode().boundsInRoot
                    val top = (box.top - 8 * scale).toInt().coerceAtLeast(0)
                    write(name, image.getSubimage(0, top, image.width, ((box.bottom + 8 * scale).toInt().coerceAtMost(image.height)) - top))
                } else if (crop != null) {
                    val bottom = (onAllNodesWithTag(crop).onFirst().fetchSemanticsNode().boundsInRoot.bottom + 8 * scale).toInt().coerceAtMost(image.height)
                    write(name, image.getSubimage(0, 0, image.width, bottom))
                } else write(name, image)
                // The Source page's video file name breaks inside a word at font 2.0 (as before Group 5); its card is PR 5's (C).
                val problems = textFitProblems((w * scale).toInt(), font, strict = true, minNodes = 3).filterNot { KNOWN in it }
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
        shot("11-view-original", prep = { dirty(); f.editor.setView(TextChoice.ORIGINAL) }, only = card) { demoState(42_300) }
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

    /** PR 1 (A 1–2, A 8, 🆕-8; samples 01 / 02 / 13a / 13b / 12): the top bar dirty + Undo / Redo, clean, the notice callout; Dubbing steps 1–2. */
    @Test fun shell() {
        shot("01-editor", prep = { dirty() }) { demoState() }
        shot("01-editor-full", height = 1669, prep = { dirty() }) { demoState() }
        shot("02-bar-dirty", prep = { dirty() }, crop = "subtitles-top-bar") { demoState() }
        shot("02-bar-clean", crop = "subtitles-top-bar") { demoState() }
        shot("02-notice-saved", prep = { dirty(); check(f.editor.save()) }, only = "subtitles-editor-notice") { demoState() }
        shot("13a-dubbing-step-1", dub = true, height = 1314, prep = { noLines() }) { clickIf("dub-step-VIDEO") }
        shot("13b-dubbing-step-2", dub = true, height = 1314, prep = { dirty() }) { click("dub-step-TRANSLATE"); demoState() }
        if (font == 1f) {
            shot("12-desktop-editor", desktop = true, prep = { dirty() }) { demoState() }
            shot("12-desktop-dubbing-step-2", desktop = true, dub = true, prep = { dirty() }) { click("dub-step-TRANSLATE"); demoState() }
        }
    }
}
