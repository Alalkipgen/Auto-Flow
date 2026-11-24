package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleOps
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Dest
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Shell
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlesFixture
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
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Group 4 (Dubbing) screenshots (plan §5 / §10): the samples' demo "Kung Fu Panda 4" (40 lines, Burmese subtitles done)
 * on a 360 dp phone at density 2, font 1.0 and 2.0, English and Burmese, the bundled Pyidaungsu; desktop 1440 × 900 at
 * density 1. The Dubbing screen sits in the Studio v2 shell as in `AppShell` (an open project editor). Runs only when
 * `AUTOFLOW_GLASS_SHOTS_DIR` is set; writes `<language>/dub-<name>-<font>.png` and checks that no text is cut.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class DubShotsTest(val language: UiLanguage, val font: Float) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}")
        fun cases() = listOf(UiLanguage.ENGLISH, UiLanguage.BURMESE).flatMap { l -> listOf(1f, 2f).map { arrayOf<Any>(l, it) } }
        private const val SCALE = 2f
        private const val WIDTH = 360
        private const val HEIGHT = 780
        /** 9:40 — the samples say 24:10, which is over the Dubbing limit (`dubbing.maxSourceMs` = 10 min). */
        const val DURATION = 580_000L
        /** The samples' lines (#18–#26, #31: start, end, English source, Burmese subtitle). */
        private val SAMPLE = mapOf(
            18 to Line(118_400, 120_800, "This is my final lesson.", "ဒါ ငါ့ရဲ့ နောက်ဆုံး သင်ခန်းစာပဲ"),
            19 to Line(122_000, 124_600, "You must choose your successor.", "မင်း ဆက်ခံမယ့်သူကို ရွေးရမယ်"),
            20 to Line(126_300, 128_200, "I do not want to choose, Master.", "ငါ မရွေးချင်ဘူး ဆရာ"),
            21 to Line(130_100, 132_600, "That is the duty of the Dragon Warrior.", "ဒါက နဂါး စစ်သည်ရဲ့ တာဝန်ပဲ"),
            22 to Line(132_800, 134_600, "Then where do we even start?", "ဒါဆို ဘယ်ကနေ စရမလဲ"),
            23 to Line(141_000, 143_100, "We have to find this dragon scroll, fast.", "ဒီ နဂါးစာလိပ်ကို ငါတို့ အမြန် ရှာရမယ်"),
            24 to Line(143_100, 145_300, "Wherever she goes, I will follow.", "သူ ဘယ်ကို သွားသွား ငါ လိုက်မယ်"),
            25 to Line(148_300, 150_200, "That city is dangerous.", "အဲဒီ မြို့က အန္တရာယ် များတယ်"),
            26 to Line(151_700, 152_800, "Trust me.", "ငါ့ကို ယုံပါ"),
            31 to Line(171_200, 173_000, "We will protect this city together.", "ဒီ မြို့ကို ငါတို့ အတူတူ ကာကွယ်ကြမယ်"),
        )
        /** 40 lines: the sample's, the others borrow their words (2 s each) at the samples' starts, the last ones in 9:40. */
        internal fun lines(translated: Boolean = true): List<Line> = (1..40).map { n ->
            val start = when { n < 18 -> 9_000L + (n - 1) * 6_500; n < 31 -> 152_800L + (n - 26) * 3_700; else -> 175_000L + (n - 31) * 40_000 }
            val l = SAMPLE[n] ?: SAMPLE.values.toList()[n % SAMPLE.size].let { Line(start, start + 2_000, it.text, it.my) }
            if (translated) l else l.copy(my = if (n % 6 == 0) null else l.my)
        }
    }
    private val out = System.getenv("AUTOFLOW_GLASS_SHOTS_DIR")?.let(::File)

    /** A Dubbing demo project and its models (nothing touches the disk). */
    private inner class Demo(durationMs: Long = DURATION, lines: List<Line> = lines()) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val f = SubtitlesFixture(lines, durationMs, "Kung Fu Panda 4", scope, dub = true)
        val script = DubScriptModel(f.session, f.fs)
        private fun update(change: (io.github.alalkipgen.autoflow.core.model.Project) -> io.github.alalkipgen.autoflow.core.model.Project) {
            val open = f.session.open!!
            check(f.session.update(open.dir, open.project.id, change) is CoreResult.Ok)
        }
        fun noVideo() = update { it.copy(subtitles = it.subtitles?.copy(video = null)) }
        fun noLines() = update { it.copy(subtitles = it.subtitles?.copy(current = null)) }
        fun approve() { script.open(); check(script.approve()) }
        /** An unsaved subtitle change (the editor's draft). */
        fun dirty() { f.editor.open(); f.editor.edit { SubtitleOps.setTextMy(it, "c2", "မင်း ဆက်ခံမယ့်သူကို အခု ရွေးရမယ်") } }
        override fun close() { scope.cancel() }
    }

    private fun UiLanguage.code() = if (this == UiLanguage.BURMESE) "my" else "en"

    private fun write(name: String, image: java.awt.image.BufferedImage) {
        ImageIO.write(image, "png", File(out!!, "${language.code()}/dub-$name-$font.png").apply { parentFile.mkdirs() })
    }

    @Composable private fun Locals(width: Int, scale: Float, content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(scale, font), LocalUiLanguage provides language,
            LocalUiFontFamily provides BurmeseTestFont.family, content = content)

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    /** The Dubbing screen as `AppShell` draws it (the Projects tab with an open editor; the window's plain Background). */
    private fun screen(d: Demo, width: Int, scale: Float): @Composable () -> Unit = {
        Locals(width, scale) {
            StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat(), Modifier.background(AutoFlowColors.Background), editor = true) {
                DubbingScreen(d.script, d.f.model, d.f.session, d.f.editor, onBack = {}, player = d.f.paused, translate = d.f.translate)
            }
        }
    }

    /** One shot of the Dubbing screen after [prep] and [open] (e.g. a step tab); phone at [HEIGHT], desktop 1440 × 900. */
    private fun shot(name: String, desktop: Boolean = false, durationMs: Long = DURATION, lines: List<Line> = lines(),
        prep: Demo.() -> Unit = {}, crop: String? = null, open: ComposeUiTest.() -> Unit = {}) {
        if (out == null) return
        val width = if (desktop) 1440 else WIDTH
        val scale = if (desktop) 1f else SCALE
        runDesktopComposeUiTest(width = (width * scale).toInt(), height = ((if (desktop) 900 else HEIGHT) * scale).toInt()) {
            Demo(durationMs, lines).use { d ->
                d.prep()
                setContent(screen(d, width, scale)); waitForIdle(); open(); waitForIdle()
                val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
                if (crop == null) write(name, image) else {
                    // From the top down to the [crop] node's bottom + 8 dp (e.g. the project bar).
                    val bottom = (onNodeWithTag(crop).fetchSemanticsNode().boundsInRoot.bottom + 8 * scale).toInt().coerceAtMost(image.height)
                    write(name, image.getSubimage(0, 0, image.width, bottom))
                }
                assertEquals(emptyList(), textFitProblems((width * scale).toInt(), font, strict = true, minNodes = 3), "$name ${language.code()} $font")
            }
        }
    }

    /** PR 1 (A, B, sample 10 / 10b / 13 / 06b / 09a / 09b): the bar on each step, every lock, the planned tile, the leave dialog. */
    @Test fun shell() {
        DubStep.entries.forEach { s ->
            shot("13-bar-${s.ordinal + 1}", prep = { approve() }, crop = "project-progress") { click("dub-step-${s.name}") }
        }
        shot("10-lock", open = { click("dub-step-VOICE") })
        shot("10b-lock-too-long", durationMs = 1_450_000) { click("dub-step-VIDEO") }
        shot("10b-lock-no-video", prep = { noVideo() }) { click("dub-step-TRANSLATE") }
        shot("10b-lock-no-lines", prep = { noLines() }) { click("dub-step-TRANSLATE") }
        shot("10b-lock-not-translated", lines = lines(translated = false)) { click("dub-step-SCRIPT") }
        shot("10b-lock-unsaved", prep = { dirty() }) { click("dub-step-SCRIPT") }
        shot("10b-planned", prep = { approve() }) { click("dub-step-VOICE") }
        shot("06b-leave", prep = { dirty() }) { click("dub-step-TRANSLATE"); click("project-back") }
        shot("09a-video") { click("dub-step-VIDEO") }
        shot("09b-translate") { click("dub-step-TRANSLATE") }
        if (font == 1f) {
            shot("12-desktop-lock", desktop = true) { click("dub-step-VOICE") }
            shot("12-desktop-script", desktop = true)
        }
    }
}
