package io.github.alalkipgen.autoflow.ui.voice

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.ai.config.EdgeTtsConfig
import io.github.alalkipgen.autoflow.ai.config.EdgeVoice
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.voice.VoiceTakes
import io.github.alalkipgen.autoflow.media.audio.PcmWav
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.home.HomeScreen
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.media.MediaTasks
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectEntry
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Dest
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Shell
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Group 6 (Voice & Audio + Quick Recap) screenshots (plan §5 / §10): the samples' demo — Text → voice "ကြော်ငြာ အသံ"
 * (289 characters, 5 Burmese parts, Nilar · Edge, tone Calm, 3 of 5 voiced), Clean up "အင်တာဗျူး အသံ"
 * (interview_2025-11-20.m4a, 12:40), Video sound "Bagan Vlog" (Bagan_Vlog.mp4, 3:12, 1920 × 1080), Quick Recap 5 min ·
 * Commentary — on a 360 dp phone at density 2, font 1.0 and 2.0, English and Burmese, the bundled Pyidaungsu; desktop
 * 1280 × 800 at density 1. Phone shots show the whole scroll (the window grows to the content). Runs only when
 * `AUTOFLOW_GLASS_SHOTS_DIR` is set; writes `<language>/va-<name>-<font>.png` and checks that no text is cut.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class VoiceShotsTest(val language: UiLanguage, val font: Float) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}")
        fun cases() = listOf(UiLanguage.ENGLISH, UiLanguage.BURMESE).flatMap { l -> listOf(1f, 2f).map { arrayOf<Any>(l, it) } }
        private const val SCALE = 2f
        private const val WIDTH = 360
        private const val HEIGHT = 780
        const val MODEL = "gemini-3.8-flash-tts"
        /** The samples' 5 parts (71, 56, 68, 47, 43 characters; 289 with the spaces): one part each at 75 characters. */
        val PARTS = listOf(
            "ရွှေလမင်း လက်ဖက်ရည်ဆိုင်က ဒီလ ဆယ့်ငါးရက်နေ့မှာ ဆိုင်ခွဲအသစ် ဖွင့်ပါပြီ။",
            "မနက် ခြောက်နာရီကနေ ည ဆယ်နာရီအထိ နေ့တိုင်း ဖွင့်ထားပါတယ်။",
            "ဖွင့်ပွဲ ပထမ တစ်ပတ်မှာ လက်ဖက်ရည် တစ်ခွက်ဝယ်ရင် တစ်ခွက် အခမဲ့ ရပါမယ်။",
            "မိသားစုနဲ့ သူငယ်ချင်းတွေကို ခေါ်ပြီး လာခဲ့ကြပါ။",
            "ရွှေလမင်း — အရသာရှိတဲ့ မနက်ခင်းတိုင်းအတွက်။",
        )
        val TEXT = PARTS.joinToString(" ")
        val EDGE = EdgeTtsConfig(model = "edge-tts", endpoint = "wss://e", trustedClientToken = "T", voices = listOf(
            EdgeVoice("my-MM-ThihaNeural", "Thiha", "male", "my"), EdgeVoice("my-MM-NilarNeural", "Nilar", "female", "my")))
        val NILAR = VoiceLock("edge", "edge-tts", "my-MM-NilarNeural", "my")
        val KORE = VoiceLock("gemini", MODEL, "Kore", "my")
        const val TEXT_NAME = "ကြော်ငြာ အသံ"
        const val CLEAN_NAME = "အင်တာဗျူး အသံ"
        const val VIDEO_NAME = "Bagan Vlog"
    }
    private val out = System.getenv("AUTOFLOW_GLASS_SHOTS_DIR")?.let(::File)

    @Before fun main() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun reset() { Dispatchers.resetMain() }

    /** The demo projects and their models (nothing touches the disk). */
    inner class Demo : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val fs = InMemoryFileSystem()
        val store = ProjectStore(fs)
        val current = CurrentProject()
        val index = ProjectIndex(fs, "/app/projects.json")
        val session = ProjectSession(fs, store, index, current)
        val config = ToolsConfig(tts = ToolsConfig.Tts(maxCharsPerPart = 75))
        /** The folder dialog (new projects go to [FakePicker.folder]). */
        val picker = FakePicker("/projects")
        val dashboard = DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), picker, current)
        /** Burmese TTS models (`config/models.json`); empty = none set up. */
        var models = listOf(MODEL)
        /** Edge voices offered; null = none. */
        var edge: EdgeTtsConfig? = EDGE
        val model by lazy { VoiceAudioModel(session, dashboard, config, scope, models = models, edgeVoices = { edge }) }
        val tasks = MediaTasks(null, scope)
        val export by lazy { VoiceExportModel(session, fs, tasks, config, null, scope) }
        /** The file dialog of Clean up / Video sound ([FakePicker.files] = the picked file). */
        val files = FakePicker("/projects")
        /** What ffprobe says of a picked file; null = cannot be read here. */
        var probe: suspend (String) -> io.github.alalkipgen.autoflow.media.probe.MediaInfo? = { null }
        /** Clean up / Video sound not built here (the planned cards). */
        var planned = false
        val clean by lazy { VoiceCleanModel(session, fs, dashboard, files, tasks, export, config, scope, probe = { probe(it) }, ffmpeg = { _, _, _ -> null }) }
        val video by lazy { VideoSoundModel(session, fs, dashboard, files, tasks, export, config, scope, probe = { probe(it) }) }
        /** The app's AI jobs (a stand-in runner: nothing runs; [job] sets the open project's Speak job). */
        val jobs = kotlinx.coroutines.flow.MutableStateFlow<List<io.github.alalkipgen.autoflow.core.recap.AiJob>>(emptyList())
        val runner = object : io.github.alalkipgen.autoflow.core.recap.AiJobRunner {
            override val jobs = this@Demo.jobs
            override suspend fun enqueue(job: io.github.alalkipgen.autoflow.core.recap.AiJob): String = job.id
            override suspend fun cancel(id: String) = Unit
            override suspend fun refresh() = Unit
        }
        val speak by lazy { VoiceSpeakModel(session, VoiceSpeakJob(session, fs, io.github.alalkipgen.autoflow.ai.fake.FakeTtsProvider(), config), runner, scope) }
        /** The Speak job of the open Text → voice project: parts [left] still to speak (p4, p5 of the sample). */
        fun job(state: io.github.alalkipgen.autoflow.core.recap.AiJobState, left: List<String> = listOf("p4", "p5"), error: String? = null) {
            jobs.value = listOf(io.github.alalkipgen.autoflow.core.recap.AiJob("speak-1", io.github.alalkipgen.autoflow.core.recap.AiJobKind.TEXT_TTS,
                session.open!!.dir, left, state, error, initialItemCount = 2))
        }
        /** Home instead of Voice & Audio. */
        var home = false

        /** The chooser (Voice & Audio from Home): nothing made yet. */
        fun chooser() { model.begin() }
        /** Text → voice before the project exists: the text typed. */
        fun fresh(text: String = TEXT) { model.begin(); model.choose(VoiceTool.TASK_TEXT); if (text.isNotEmpty()) model.setText(text) }

        private fun open(name: String, voice: VoiceTool): String {
            val dir = "/projects/$name.autoflow"
            val created = (store.create(dir, name, Mode.VOICE_AUDIO) as CoreResult.Ok).value
            current.set(OpenProject(dir, (store.save(dir, created.copy(voice = voice)) as CoreResult.Ok).value))
            return dir
        }

        /** The Text → voice project as the sample opens it: [voiced] of 5 parts have a take. */
        fun text(voiced: Int = 3, lock: VoiceLock? = NILAR, tone: String? = "calm") {
            val plan = VoiceTakes.plan(VoiceTool(task = VoiceTool.TASK_TEXT, text = TEXT, lock = lock, tone = tone), config.tts.maxCharsPerPart, Lexicon.EMPTY)
            val dir = "/projects/$TEXT_NAME.autoflow"
            val created = (store.create(dir, TEXT_NAME, Mode.VOICE_AUDIO) as CoreResult.Ok).value
            val artifacts = ArtifactStore(fs, dir)
            val parts = plan.parts.mapIndexed { i, p ->
                if (i >= voiced) p else p.copy(take = (artifacts.put(PcmWav.encode(1_000, FloatArray(100) { (i + 1) / 8f }), ArtifactKind.AUDIO, "wav")
                    as CoreResult.Ok).value.id)
            }
            current.set(OpenProject(dir, (store.save(dir, created.copy(voice = plan.copy(parts = parts))) as CoreResult.Ok).value))
        }

        /** Clean up "အင်တာဗျူး အသံ": interview_2025-11-20.m4a, 12:40 (a video file with [video]). */
        fun clean(video: Boolean = false) {
            val file = if (video) "source/interview_2025-11-20.mp4" else "source/interview_2025-11-20.m4a"
            open(CLEAN_NAME, VoiceTool(task = VoiceTool.TASK_CLEAN, source = SourceVideo(file, 18_400_000, 760_000,
                width = if (video) 1920 else 0, height = if (video) 1080 else 0)))
        }

        /** Video sound "Bagan Vlog": Bagan_Vlog.mp4, 3:12, 1920 × 1080, with sound ([sound] false = without). */
        fun videoSound(sound: Boolean = true) {
            open(VIDEO_NAME, VoiceTool(task = VoiceTool.TASK_VIDEO_SOUND, source = SourceVideo("source/Bagan_Vlog.mp4", 96_000_000, 192_000,
                width = 1920, height = 1080, fps = 30.0, hasAudio = sound)))
        }

        override fun close() { scope.cancel() }
    }

    private fun UiLanguage.code() = if (this == UiLanguage.BURMESE) "my" else "en"

    private fun write(name: String, image: java.awt.image.BufferedImage) {
        ImageIO.write(image, "png", File(out!!, "${language.code()}/va-$name-$font.png").apply { parentFile.mkdirs() })
    }

    @Composable private fun Locals(width: Int, scale: Float, content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(scale, font), LocalUiLanguage provides language,
            LocalUiFontFamily provides BurmeseTestFont.family, content = content)

    fun ComposeUiTest.click(tag: String) {
        // "Before" shots run on older code that may not have this control yet.
        if (System.getenv("AUTOFLOW_SHOTS_BEFORE") != null && onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) return
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    /** The screen as `AppShell` draws it: Voice & Audio in the Projects tab, or Home. */
    private fun screen(d: Demo, width: Int, scale: Float): @Composable () -> Unit = {
        Locals(width, scale) {
            if (d.home) StudioV2Shell(StudioV2Dest.HOME, {}, 0, width.toFloat(), ambient = true) {
                HomeScreen(jobs = emptyList(), onNewSubtitles = {}, onNewVoice = {}, onNewDubbing = {}, recent = listOf(
                    ProjectEntry("/projects/$TEXT_NAME.autoflow", TEXT_NAME, Mode.VOICE_AUDIO, Ratio.R16_9, 0, 0, kotlin.time.Instant.fromEpochMilliseconds(1_764_000_000_000))))
            }
            else StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat(), editor = true) {
                Box { VoiceAudioScreen(d.model, d.session, onBack = {}, speak = d.speak, export = d.export, clean = d.clean.takeIf { !d.planned },
                    video = d.video.takeIf { !d.planned }) }
            }
        }
    }

    /** The scroll's hidden height in dp (0 when it all fits or there is no scroll). */
    private fun ComposeUiTest.overflow(scale: Float): Int {
        val tag = listOf("voice-scroll", "home-scroll").firstOrNull { onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() } ?: return 0
        val range = onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) ?: return 0
        return (range.maxValue() / scale).toInt() + 1
    }

    /**
     * One shot after [prep] and [open]: phone 360 dp wide (density 2) and as tall as its whole scroll ([full]; else
     * [height]), desktop 1280 × 800; [only] = that node with 8 dp around it ('|' = the first that exists); [crop] = from
     * the top to that node's bottom + 8 dp.
     */
    fun shot(name: String, desktop: Boolean = false, height: Int = HEIGHT, full: Boolean = !desktop, prep: Demo.() -> Unit = {},
        only: String? = null, crop: String? = null, dialog: Boolean = false, open: ComposeUiTest.() -> Unit = {}) {
        if (out == null) return
        val w = if (desktop) 1280 else WIDTH
        val scale = if (desktop) 1f else SCALE
        var h = if (desktop) 800 else height
        if (full) runDesktopComposeUiTest(width = (w * scale).toInt(), height = (h * scale).toInt()) {
            Demo().use { d -> d.prep(); setContent(screen(d, w, scale)); waitForIdle(); open(); waitForIdle(); h += overflow(scale) }
        }
        runDesktopComposeUiTest(width = (w * scale).toInt(), height = (h * scale).toInt()) {
            Demo().use { d ->
                d.prep()
                setContent(screen(d, w, scale)); waitForIdle(); open(); waitForIdle()
                val roots = onAllNodes(isRoot())
                val image = roots[0].captureToImage().toAwtImage().let { base ->
                    if (!dialog || roots.fetchSemanticsNodes().size < 2) base
                    else { // the screen with the dialog window (its scrim and card) drawn over it
                        val top = roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().toAwtImage()
                        java.awt.image.BufferedImage(base.width, base.height, java.awt.image.BufferedImage.TYPE_INT_ARGB).apply {
                            createGraphics().run { drawImage(base, 0, 0, null); drawImage(top, (base.width - top.width) / 2, (base.height - top.height) / 2, null); dispose() }
                        }
                    }
                }
                val tag = only?.split('|')?.firstOrNull { onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
                if (tag != null) {
                    val box = onAllNodesWithTag(tag).onFirst().fetchSemanticsNode().boundsInRoot
                    val top = (box.top - 8 * scale).toInt().coerceAtLeast(0)
                    write(name, image.getSubimage(0, top, image.width, ((box.bottom + 8 * scale).toInt().coerceAtMost(image.height)) - top))
                } else if (crop != null) {
                    val bottom = (onAllNodesWithTag(crop).onFirst().fetchSemanticsNode().boundsInRoot.bottom + 8 * scale).toInt().coerceAtMost(image.height)
                    write(name, image.getSubimage(0, 0, image.width, bottom))
                } else write(name, image)
                val problems = textFitProblems((w * scale).toInt(), font, strict = true, minNodes = 3)
                if (System.getenv("AUTOFLOW_SHOTS_BEFORE") == null) assertEquals(emptyList(), problems, "$name ${language.code()} $font")
            }
        }
    }

    /** PR 1 (A, J literals, K): the shell — top bar, ambient background, the notice callout; Home's arrows and "All". */
    @Test fun shell() {
        shot("03-chooser", prep = { chooser() })
        shot("04-text-new", prep = { fresh() })
        shot("05-text-voice", prep = { text() })
        if (font == 1f) shot("12-desktop-text", desktop = true, prep = { text() })
        // The notice: Continue with this text when the project cannot be made (no folder picked: "Choose where to save").
        shot("04-notice", prep = { picker.folder = " "; fresh() }, crop = "voice-text") {
            click("voice-continue")
        }
        shot("01-home-idle", prep = { home = true }, only = "home-quick")
        shot("01-home-length", prep = { home = true }, only = "home-quick") { click("home-quick-start") }
        shot("01-home-style", prep = { home = true }, only = "home-quick") { click("home-quick-start"); click("quick-next-style") }
        shot("02-home-all", prep = { home = true }, only = "home-continue")
    }

    /** PR 2 (B): the chooser — glass task cards, the planned cards, the chooser notices (amber; red when the step failed). */
    @Test fun chooser() {
        // 03-chooser: in [shell].
        shot("03b-planned", prep = { planned = true; chooser() })
        shot("03b-no-sound", prep = { chooser(); files.files = listOf("/in/silent_clip.mp4")
            probe = { io.github.alalkipgen.autoflow.media.probe.MediaInfo(60_000, 1920, 1080, 30.0, "h264", null) } }, crop = "voice-task-video") {
            click("voice-task-clean")
        }
        shot("03b-not-video", prep = { chooser(); files.files = listOf("/in/song.m4a")
            probe = { io.github.alalkipgen.autoflow.media.probe.MediaInfo(60_000, null, null, null, null, "aac") } }, crop = "video-sound-notice") {
            click("voice-task-video")
        }
        shot("03b-no-dialogs", prep = { chooser(); files.unsupported = true }, crop = "voice-task-video") { click("voice-task-clean") }
    }

    /** PR 3 (C): the voice card (VA-2) closed / open / Gemini / no voice / no models, the ⓘ hints (VA-4), the tone chips. */
    @Test fun voiceTop() {
        // 05-text-voice (closed card, as opened): in [shell].
        shot("06-voice-open", prep = { text() }, crop = "voice-tone-calm") { click("voice-expand") }
        shot("06-voice-gemini", prep = { text(lock = KORE) }, crop = "voice-tone-calm") { click("voice-expand") }
        shot("06-no-voice", prep = { text(voiced = 0, lock = null) }, crop = "voice-tone-calm")
        shot("06-no-models", prep = { models = emptyList(); text() }, crop = "voice-tone-calm")
        shot("06-voice-info", prep = { text() }, crop = "voice-tone-calm") { click("voice-expand"); click("voice-info-models"); click("voice-info-edge") }
    }

    /** PR 4 (D, E, 🆕 VA-3, VA-6): the parts card and rows, the take rule ⓘ, the sticky + caption, the Speak dialog, the job card, notices. */
    @Test fun partsSpeak() {
        val running = io.github.alalkipgen.autoflow.core.recap.AiJobState.RUNNING
        val paused = io.github.alalkipgen.autoflow.core.recap.AiJobState.PAUSED
        val failed = io.github.alalkipgen.autoflow.core.recap.AiJobState.FAILED
        shot("07-parts-info", prep = { text() }) { click("voice-info-parts") }
        shot("07-all-voiced", prep = { text(voiced = 5) })
        shot("07a-dialog-edge", prep = { text() }, dialog = true) { click("voice-speak") }
        shot("07b-dialog-gemini", prep = { text(lock = KORE) }, dialog = true) { click("voice-speak") }
        shot("07c-dialog-redo", prep = { text() }, dialog = true) { click("voice-redo-p2") }
        shot("08-speaking", prep = { text(); job(running) })
        shot("08-paused", prep = { text(); job(paused, error = "Edge TTS is busy (rate limited). Resume to go on.") }, crop = "voice-speak-job")
        shot("08-stopped", prep = { text(); job(failed, error = "Edge: the connection closed.") }, crop = "voice-speak-job")
        shot("08-notice-ok", prep = { text(voiced = 5); speak.ask() }, crop = "voice-speak-notice")
        shot("08-notice-warn", prep = { text(); job(running); speak.ask(); jobs.value = emptyList() }, crop = "voice-speak-notice")
        if (font == 1f) shot("12-desktop-dialog", desktop = true, prep = { text() }, dialog = true) { click("voice-speak") }
    }
}
