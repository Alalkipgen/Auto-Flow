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
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleOps
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
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
        private val KORE = io.github.alalkipgen.autoflow.core.recap.VoiceLock("gemini", "tts-a", "Kore", "my")
        private val NILAR = io.github.alalkipgen.autoflow.core.recap.VoiceLock("edge", "edge-tts", "my-MM-NilarNeural", "my")
        private val CHARON = io.github.alalkipgen.autoflow.core.recap.VoiceLock("gemini", "gemini-3.8-flash-tts", "Charon", "my")
        private val EDGE = io.github.alalkipgen.autoflow.ai.config.EdgeTtsConfig(model = "edge-tts", voices = listOf(
            io.github.alalkipgen.autoflow.ai.config.EdgeVoice("my-MM-ThihaNeural", "Thiha", "male", "my"),
            io.github.alalkipgen.autoflow.ai.config.EdgeVoice("my-MM-NilarNeural", "Nilar", "female", "my")))
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
        val export = DubExportModel(f.session, f.fs, io.github.alalkipgen.autoflow.ui.media.MediaTasks(DubExportModelTest.Queue(), scope), scope)
        /** The stand-in decoder: a sunset picture (no VLC in the shots), paused at the seek; [failed] = it cannot play here. */
        var failed = false
        val player: io.github.alalkipgen.autoflow.ui.subtitles.SubtitlePlayer = { _, req, m, report ->
            androidx.compose.runtime.LaunchedEffect(req) {
                report(io.github.alalkipgen.autoflow.media.playback.MovieSnapshot(positionMs = req.sourceMs, durationMs = durationMs,
                    phase = if (failed) io.github.alalkipgen.autoflow.media.playback.MoviePhase.FAILED else io.github.alalkipgen.autoflow.media.playback.MoviePhase.PAUSED))
            }
            androidx.compose.foundation.layout.Box(m.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(
                androidx.compose.ui.graphics.Color(0xFF3A1E12), androidx.compose.ui.graphics.Color(0xFFC0602A),
                androidx.compose.ui.graphics.Color(0xFF7A3A1A), androidx.compose.ui.graphics.Color(0xFF140A08)))))
        }
        private fun update(change: (io.github.alalkipgen.autoflow.core.model.Project) -> io.github.alalkipgen.autoflow.core.model.Project) {
            val open = f.session.open!!
            check(f.session.update(open.dir, open.project.id, change) is CoreResult.Ok)
        }
        fun noVideo() = update { it.copy(subtitles = it.subtitles?.copy(video = null)) }
        fun noLines() = update { it.copy(subtitles = it.subtitles?.copy(current = null)) }
        fun approve() { script.open(); check(script.approve()) }
        /**
         * Takes for the lines (the samples' 33 of 40: none for every 6th line and #40; #9 and #21 too long), spoken with
         * one Gemini voice; [all] = every line voiced and none too long.
         */
        fun voices(all: Boolean = false, lock: io.github.alalkipgen.autoflow.core.recap.VoiceLock = KORE, tone: String? = null, mute: Boolean = false) {
            val open = f.session.open!!
            val art = io.github.alalkipgen.autoflow.core.store.ArtifactStore(f.fs, open.dir)
            val doc = script.state.value.doc ?: run { script.open(); script.state.value.doc!! }
            val takes = doc.cues.withIndex().filter { (i, _) -> all || ((i + 1) % 6 != 0 && i + 1 != 40) }.associate { (i, c) ->
                val ms = if (!all && (i + 1 == 9 || i + 1 == 21)) 9_000L else (c.endMs - c.startMs - 200).coerceAtLeast(500)
                c.id to io.github.alalkipgen.autoflow.core.model.DubTake(
                    (art.put("RIFF-${c.id}".encodeToByteArray(), io.github.alalkipgen.autoflow.core.model.ArtifactKind.AUDIO, "wav") as CoreResult.Ok).value.id,
                    io.github.alalkipgen.autoflow.core.dubbing.DubRules.takeKey(c, io.github.alalkipgen.autoflow.core.recap.Lexicon.EMPTY, lock, tone), ms)
            }
            update { it.copy(dubbing = (it.dubbing ?: io.github.alalkipgen.autoflow.core.model.DubbingTool()).copy(lock = lock, tone = tone, takes = takes,
                original = if (mute) io.github.alalkipgen.autoflow.core.model.DubOriginal(io.github.alalkipgen.autoflow.core.model.DubOriginal.MUTE, 0.0)
                    else io.github.alalkipgen.autoflow.core.model.DubOriginal())) }
        }
        /** The Voice step's model (only when [withVoice]: without it the step is the planned tile, PR 1's shots). */
        var withVoice = false
        var models = listOf("gemini-3.8-flash-tts")
        val jobs = kotlinx.coroutines.flow.MutableStateFlow<List<io.github.alalkipgen.autoflow.core.recap.AiJob>>(emptyList())
        val runner = object : io.github.alalkipgen.autoflow.core.recap.AiJobRunner {
            override val jobs = this@Demo.jobs
            override suspend fun enqueue(job: io.github.alalkipgen.autoflow.core.recap.AiJob): String = job.id
            override suspend fun cancel(id: String) = Unit
            override suspend fun refresh() = Unit
        }
        val voice by lazy {
            DubVoiceModel(f.session, DubSpeakJob(f.session, f.fs, io.github.alalkipgen.autoflow.ai.fake.FakeTtsProvider()), runner, scope, models = models,
                edgeVoices = { EDGE })
        }
        /** The Speak job of the open project: [left] of 40 lines still to speak. */
        fun job(state: AiJobState, left: Int, error: String? = null) {
            jobs.value = listOf(io.github.alalkipgen.autoflow.core.recap.AiJob("speak-1", io.github.alalkipgen.autoflow.core.recap.AiJobKind.DUB_TTS,
                f.session.open!!.dir, (41 - left..40).map { "c$it" }, state, error, initialItemCount = 40))
        }
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
                DubbingScreen(d.script, d.f.model, d.f.session, d.f.editor, onBack = {}, player = d.player, translate = d.f.translate, export = d.export,
                    voice = if (d.withVoice) d.voice else null)
            }
        }
    }

    /** One shot of the Dubbing screen after [prep] and [open] (e.g. a step tab); phone at [HEIGHT], desktop 1440 × 900. */
    private fun shot(name: String, desktop: Boolean = false, durationMs: Long = DURATION, lines: List<Line> = lines(),
        prep: Demo.() -> Unit = {}, crop: String? = null, only: String? = null, landscape: Boolean = false, margin: Int = 8, tall: Int = 2,
        open: ComposeUiTest.() -> Unit = {}) {
        if (out == null) return
        val width = if (desktop) 1440 else WIDTH
        val scale = if (desktop) 1f else SCALE
        // A card alone gets a twice-high window ([tall] times for the open voice card) so the whole card is in sight at font 2.0
        // (the lines list scrolls under the footer).
        val (w, h) = if (landscape) HEIGHT to WIDTH else width to (if (desktop) 900 else if (only != null) HEIGHT * tall else HEIGHT)
        runDesktopComposeUiTest(width = (w * scale).toInt(), height = (h * scale).toInt()) {
            Demo(durationMs, lines).use { d ->
                d.prep()
                setContent(screen(d, w, scale)); waitForIdle(); open(); waitForIdle()
                val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
                if (only != null) {
                    // Only the [only] node with 8 dp around it, the full width (e.g. the preview card).
                    val box = onNodeWithTag(only).fetchSemanticsNode().boundsInRoot
                    val top = (box.top - margin * scale).toInt().coerceAtLeast(0)
                    write(name, image.getSubimage(0, top, image.width, ((box.bottom + margin * scale).toInt().coerceAtMost(image.height)) - top))
                } else if (crop == null) write(name, image) else {
                    // From the top down to the [crop] node's bottom + 8 dp (e.g. the project bar).
                    val bottom = (onNodeWithTag(crop).fetchSemanticsNode().boundsInRoot.bottom + 8 * scale).toInt().coerceAtMost(image.height)
                    write(name, image.getSubimage(0, 0, image.width, bottom))
                }
                assertEquals(emptyList(), textFitProblems((w * scale).toInt(), font, strict = true, minNodes = 3), "$name ${language.code()} $font")
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

    /** A click on [tag] when it is there (the before shots have no ⓘ / Fullscreen). */
    private fun ComposeUiTest.clickIf(tag: String) { if (onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) click(tag) }

    private fun ComposeUiTest.seekPreview(ms: Long) {
        onAllNodesWithTag("dub-scrubber").onFirst().performSemanticsAction(SemanticsActions.SetProgress) { it((ms + .5f) / DURATION) }
        waitForIdle()
    }

    /** Play with the test clock held; with Fullscreen open there are two Play buttons on the same clock, either one plays. */
    private fun ComposeUiTest.playPreview() {
        mainClock.autoAdvance = false
        onAllNodesWithTag("dub-play").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        mainClock.advanceTimeByFrame()
        waitForIdle()
    }

    /**
     * PR 2 (D + 🆕-1, samples 11 / 11b / 11c): the preview card on Dub script — playing on line #23 at 2:21.4; paused
     * between two lines (no chip, no caption); the hint open; Export with Burmese subtitles off; the video cannot play
     * here; Fullscreen portrait and landscape; the whole Dub script step with the card on top (01).
     */
    @Test fun preview() {
        val card = "dub-preview-box"
        shot("11-playing", prep = { voices() }, only = card) { seekPreview(141_400); playPreview() }
        shot("11-between-lines", prep = { voices() }, only = card) { seekPreview(146_600) }
        shot("11-hint-open", prep = { voices() }, only = card) { seekPreview(141_400); clickIf("dub-preview-info") }
        shot("11-export-no-subtitles", prep = { voices(all = true); approve() }, only = card) { click("dub-step-EXPORT"); seekPreview(141_400) }
        shot("11-unavailable", prep = { voices(); failed = true }, only = card)
        shot("11b-fullscreen-portrait", prep = { voices() }) { seekPreview(141_400); clickIf("dub-preview-fullscreen"); playPreview() }
        shot("11c-fullscreen-landscape", prep = { voices() }, landscape = true) { seekPreview(141_400); clickIf("dub-preview-fullscreen"); playPreview() }
        shot("01-script-top", prep = { voices() }) { seekPreview(141_400) }
        if (font == 1f) shot("12-desktop-script-preview", desktop = true, prep = { voices() }) { seekPreview(141_400) }
    }

    /** Scroll the Dub script list to the line [id]. */
    private fun ComposeUiTest.toLine(id: String) { onNodeWithTag("dub-rows").performScrollToKey(id); waitForIdle() }

    /** Type into the spoken field of [id] (focused, so the cyan ring shows). */
    private fun ComposeUiTest.typeSpoken(id: String, text: String) {
        onNode(hasContentDescription(UiText.DUB_SPOKEN.text(language)) and hasAnyAncestor(hasTestTag("dub-spoken-$id")))
            .performClick().performTextReplacement(text)
        waitForIdle()
    }

    /**
     * PR 3 (E + 🆕-2, samples 01 / 02): the Dub script — the notice after Save (green) and after Shorten (amber); #19
     * edited, not saved (field focused + Save); #21 its own spoken line (Use the subtitle); #23 under the playhead (the
     * cyan outline); the sticky bar not approved / approved; the list scrolled to #19 (01); desktop.
     */
    @Test fun script() {
        val edited = "မင်းနောက်က ဆက်ခံမယ့်သူကို ရွေးရမယ်"
        val own = "ဒါက နဂါးစစ်သည် တစ်ယောက်ရဲ့ တာဝန်ပဲ"
        shot("02-notice-saved", prep = { script.open(); check(script.setSpoken("c19", edited)) }, crop = "dub-notice")
        shot("02-notice-shorten", prep = {
            script.open(); check(script.setSpoken(mapOf("c21" to own), io.github.alalkipgen.autoflow.ui.i18n.UiNotice(UiText.DUB_SHORTEN_USED, "n" to 1)))
        }, crop = "dub-notice")
        shot("02-edited", prep = { voices() }, only = "dub-row-c19") { toLine("c19"); typeSpoken("c19", edited) }
        shot("02-own", prep = { voices(); script.open(); check(script.setSpoken("c21", own)); script.open() }, only = "dub-row-c21") { toLine("c21") }
        shot("02-playing", prep = { voices() }, only = "dub-row-c23") { seekPreview(141_400); toLine("c23") }
        shot("02-head-seek", prep = { voices() }, only = "dub-row-c24") { toLine("c24"); click("dub-row-head-c24") }
        shot("02-sticky-not-approved", only = "sticky-action-bar")
        shot("02-sticky-approved", prep = { approve() }, only = "sticky-action-bar") { click("dub-step-SCRIPT") }
        shot("01-script-lines", prep = { voices(); script.open(); check(script.setSpoken("c21", own)); script.open() }) {
            seekPreview(141_400); toLine("c19"); typeSpoken("c19", edited)
        }
        if (font == 1f) shot("12-desktop-script-lines", desktop = true, prep = { voices(); script.open(); check(script.setSpoken("c21", own)); script.open() }) {
            seekPreview(141_400); toLine("c19")
        }
    }

    private fun ComposeUiTest.toVoice(key: String) { onNodeWithTag("dub-voice-rows").performScrollToKey(key); waitForIdle() }

    /** PR 4 (F 1–5, 🆕-3, 🆕-4; samples 03 / 04 / 05 / 06a): the voice card closed and open, the Speak job, notices, the dialog. */
    @Test fun voice() {
        val nilar: Demo.() -> Unit = { withVoice = true; approve(); voices(lock = NILAR, tone = "calm") }
        shot("03-voice", prep = nilar) { toVoice("settings") }
        shot("04-open-edge", prep = nilar, only = "dub-voice-settings", tall = 3) { toVoice("settings"); clickIf("dub-voice-expand"); toVoice("settings") }
        shot("04-open-gemini-mute", prep = { withVoice = true; approve(); voices(lock = CHARON, tone = "calm", mute = true) },
            only = "dub-voice-settings", tall = 3) {
            toVoice("settings"); clickIf("dub-voice-expand"); toVoice("settings")
        }
        shot("04-no-models", prep = { withVoice = true; models = emptyList(); approve() }, only = "dub-voice-settings", tall = 3) { toVoice("settings") }
        shot("04-running", prep = { nilar(this); job(AiJobState.RUNNING, 15) }, only = "dub-voice-settings") { toVoice("settings") }
        shot("05-job-running", prep = { nilar(this); job(AiJobState.RUNNING, 15) }, only = "dub-speak-job") { toVoice("speak") }
        shot("05-job-paused", prep = { nilar(this); job(AiJobState.PAUSED, 16) }, only = "dub-speak-job") { toVoice("speak") }
        shot("05-job-stopped", prep = { nilar(this); job(AiJobState.FAILED, 16, "Edge: 429 Too Many Requests.") }, only = "dub-speak-job") {
            toVoice("speak")
        }
        shot("05-notice-ok", prep = { withVoice = true; approve(); voices(all = true, lock = NILAR, tone = "calm"); voice.ask() }, only = "dub-speak-notice",
            margin = 24) { toVoice("speak") }
        shot("05-notice-amber", prep = { withVoice = true; approve(); voices(lock = NILAR, tone = "calm"); job(AiJobState.RUNNING, 15)
            voice.ask(); jobs.value = emptyList() }, only = "dub-speak-notice", margin = 24) { toVoice("speak") }
        shot("06a-dialog", prep = nilar) { toVoice("speak"); click("dub-speak") }
        if (font == 1f) shot("12-desktop-voice", desktop = true, prep = nilar) { toVoice("settings") }
    }
}
