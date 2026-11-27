package io.github.alalkipgen.autoflow.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.github.alalkipgen.autoflow.media.render.BatteryReader
import io.github.alalkipgen.autoflow.media.render.BatteryReading
import io.github.alalkipgen.autoflow.ui.components.paneScroll
import io.github.alalkipgen.autoflow.ui.export.ExportModel
import io.github.alalkipgen.autoflow.ui.export.MusicVideoExportScreen
import io.github.alalkipgen.autoflow.ui.export.MusicVideoPreview
import io.github.alalkipgen.autoflow.core.lyrics.LyricLine
import io.github.alalkipgen.autoflow.core.lyrics.LyricSync
import io.github.alalkipgen.autoflow.media.audio.LoudnessMeasurement
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.media.render.RenderResult
import io.github.alalkipgen.autoflow.media.render.RenderState
import kotlinx.coroutines.flow.MutableStateFlow
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import java.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardGenerator
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.lyrics.LyricTimings
import io.github.alalkipgen.autoflow.core.lyrics.TapSync
import io.github.alalkipgen.autoflow.core.model.Character
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.workflow.Blocker
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.ambientBackground
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.inbox.SharedInbox
import io.github.alalkipgen.autoflow.ui.inbox.InboxModel
import io.github.alalkipgen.autoflow.ui.inbox.InboxScreen
import io.github.alalkipgen.autoflow.ui.input.InputModel
import io.github.alalkipgen.autoflow.ui.input.CharactersCard
import io.github.alalkipgen.autoflow.ui.input.GenerateCard
import io.github.alalkipgen.autoflow.ui.input.InputScreen
import io.github.alalkipgen.autoflow.ui.input.InputUiState
import io.github.alalkipgen.autoflow.ui.input.SongCard
import io.github.alalkipgen.autoflow.ui.input.LyricSyncUiState
import io.github.alalkipgen.autoflow.ui.input.LyricTimingCard
import io.github.alalkipgen.autoflow.ui.input.NoPlayer
import io.github.alalkipgen.autoflow.ui.input.TapPanelBody
import io.github.alalkipgen.autoflow.ui.input.TimingStatus
import io.github.alalkipgen.autoflow.ui.input.TimingStatusBody
import io.github.alalkipgen.autoflow.ui.jobs.MusicGeminiJobCard
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.storyboard.JobState
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardModel
import io.github.alalkipgen.autoflow.ui.shots.ShotsModel
import io.github.alalkipgen.autoflow.ui.references.ReferencesModel
import io.github.alalkipgen.autoflow.ui.handoff.FlowHandoffModel
import io.github.alalkipgen.autoflow.ui.shotsheet.ShotSheetScreen
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardResult
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardService
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Group 3 (Music Video) screenshots (plan §5 / §10): the samples' demo project "Bagan Sunset" (a 1:36 song, 16:9, 8 s
 * clips, the sample's Burmese lyrics) on a 360 dp phone at density 2, font 1.0 and 2.0, English and Burmese, the bundled
 * Pyidaungsu; desktop 1280 × 800 for PRs 1, 5 and 8. The page sits under the project bar and the Gemini run card as in
 * `AppShell` (V2ProjectScreen). Runs only when `AUTOFLOW_GLASS_SHOTS_DIR` is set; writes `<language>/mv-<name>-<font>.png`
 * and checks that no text is cut.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class MvShotsTest(val language: UiLanguage, val font: Float) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}")
        fun cases() = listOf(UiLanguage.ENGLISH, UiLanguage.BURMESE).flatMap { l -> listOf(1f, 2f).map { arrayOf<Any>(l, it) } }
        private const val SCALE = 2f
        private const val WIDTH = 360
        private const val HEIGHT = 780
        val LYRICS = listOf("[Verse]", "ရွှေရောင် နေဝင်ချိန် ပုဂံမြေမှာ", "မင်းအပြုံးလေး လင်းလက်နေတယ်", "လယ်ကွင်းထဲ လက်တွဲ လျှောက်ခဲ့ကြ",
            "စေတီတွေကြား လေပြေလေး တိုက်", "[Chorus]", "မီးပုံးလေးတွေ ကောင်းကင်ထဲ", "မိုးစက်တွေကြား မင်းနာမည်", "ဧရာဝတီ ရေပြင်ပေါ်မှာ",
            "ကြယ်တွေလို အိပ်မက်တွေ").joinToString("\n")
    }
    private val out = System.getenv("AUTOFLOW_GLASS_SHOTS_DIR")?.let(::File)

    /** Gemini that waits (the run card stays "running" for the shot). */
    private class WaitingGemini : TextProvider {
        private val fake = FakeTextProvider()
        val go = CompletableDeferred<Unit>()
        override suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> {
            go.await(); return fake.json(prompt, schema, system)
        }
    }

    /** The demo project, opened like the app (one session for every page model). */
    private class Demo(waiting: Int = 0) : AutoCloseable {
        val root: File = Files.createTempDirectory("mv-shots").toFile()
        val fs = JvmFs()
        val current = CurrentProject()
        val session = ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, File(root, "index.json").path), current)
        val gemini = WaitingGemini()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = StoryboardService(session, StoryboardGenerator(gemini))
        val jobs = StoryboardJobs(service, scope)
        val input: InputModel
        val shared: SharedInbox
        init {
            val dir = File(root, "Bagan Sunset.autoflow").path
            val project = (ProjectStore(fs).create(dir, "Bagan Sunset", Mode.MUSIC_VIDEO, ProjectSettings(ratio = Ratio.R16_9, clipMs = 8_000,
                durationMs = 96_000, songStyle = "Burmese pop ballad", videoStyle = "Cinematic", mood = "nostalgic, golden sunset")) as CoreResult.Ok).value
            current.set(OpenProject(dir, project))
            session.writeLyrics(LYRICS)
            File(dir, "source/song.mp3").apply { parentFile.mkdirs(); writeBytes(ByteArray(64)) }
            val hold = File(root, SharedInbox.DIR_NAME).apply { mkdirs() }
            listOf("VID_20261121_183012.mp4", "IMG_4821.jpg", "lyrics_notes.pdf").take(waiting).forEach { File(hold, it).writeBytes(ByteArray(8)) }
            shared = SharedInbox(fs, session, hold.path)
            input = InputModel(session, jobs)
        }
        /** The shot sheet's page models, as AppShell makes them (no clipboard / browser in the shots). */
        val storyboard by lazy { StoryboardModel(session, service, jobs) }
        val shots by lazy { ShotsModel(session) }
        val refs by lazy { ReferencesModel(session, { PlatformResult.Ok(Unit) }, { PlatformResult.Ok(Unit) }, FakePicker()) }
        val handoff by lazy {
            FlowHandoffModel(session, { PlatformResult.Ok(Unit) }, { PlatformResult.Ok(Unit) }, FakePicker(), nowMs = { 1_764_000_000_000L }, fs = fs)
        }
        private val project get() = current.state.value!!.project
        private fun shot(n: Int) = project.shots.first { it.number == n }
        /** One image per shot (the same bytes would be one shared artifact). */
        private fun image(n: Int) = java.io.ByteArrayOutputStream().also { out ->
            ImageIO.write(java.awt.image.BufferedImage(16, 9, java.awt.image.BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, n * 0x101010) }, "png", out)
        }.toByteArray()
        /**
         * The samples' shot list (sample 05): shots 1, 2, 5, 7, 10 accepted, 3 imported, 4 needs Flow, 6 prompt ready,
         * 9 stale (its storyboard changed after the prompt), 8, 11, 12 draft; three credit lines; one batch sheet.
         */
        fun statuses() {
            storyboard()
            fun prompt(n: Int) { storyboard.approve(n); shots.compilePrompt(n); shots.approvePrompt(n) }
            val accepted = listOf(1, 2, 5, 7, 10)
            for (n in accepted + listOf(3, 4)) { prompt(n); refs.importRefBytes(project, shot(n), image(n), "png"); refs.approveRef(n) }
            for (n in accepted + 3) handoff.importVideoBytes(project, shot(n), byteArrayOf(n.toByte(), 1, 2), "mp4")
            for (n in accepted) handoff.acceptVideo(n)
            prompt(6)
            prompt(9); storyboard.edit(9) { it.copy(lightingEn = it.lightingEn + ", warm dusk") }
            handoff.logCredits(1, 20); handoff.logCredits(2, 100); handoff.logCredits(3, 100)
            handoff.exportBatch()
        }
        /** No project open, with the same shared files waiting (the Inbox's no-project state). */
        val noSession = ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, File(root, "none-inbox.json").path), CurrentProject())
        val sharedNoProject by lazy { SharedInbox(fs, noSession, File(root, SharedInbox.DIR_NAME).path) }
        /** The samples' inbox files (sample 11): a Flow video, a reference image, a PDF that cannot be attached. */
        fun inboxFiles() {
            val dir = File(current.state.value!!.dir, "inbox").apply { mkdirs() }
            listOf("flow_shot04_final.mp4", "ref_heroine_close.png", "lyrics_notes.pdf").forEach { File(dir, it).writeBytes(ByteArray(8)) }
        }
        /**
         * The Export model the Preview and Export pages use (no share; Pyidaungsu for the lyrics after [lyricFont]):
         * set [queued] (a render queue, as on the phone; else "not available here") and [batteryPercent] before first use.
         */
        val settings by lazy { SettingsStore(fs, File(root, "settings.json").path) }
        var queued = false
        var batteryPercent = 80
        /** The queued render's state (the shots set it; nothing renders). */
        val renderRun = MutableStateFlow<RenderState>(RenderState.Queued)
        private val queue = object : RenderQueue {
            override fun enqueue(job: RenderJob) = "render-1"
            override fun observe(id: String) = renderRun
            override fun cancel(id: String) { renderRun.value = RenderState.Cancelled }
        }
        val export by lazy {
            ExportModel(session, settings, fs, if (queued) queue else null, null, BatteryReader { BatteryReading(batteryPercent, charging = false) }, scope)
        }
        val dir get() = current.state.value!!.dir
        /** The demo lyrics synced (one line every 9 s); [stale] = timings of an older text (the lyrics changed since). */
        fun synced(stale: Boolean = false) {
            val lines = LyricSync.lines(LYRICS).mapIndexed { i, t -> LyricLine(if (stale && i == 0) "$t …" else t, i * 9_000L, i * 9_000L + 8_000L) }
            session.saveLyricTimings(LyricTimings(lines = lines))
        }
        /** A finished 1080p Export read back from its record (the app restarted): 1:36, took 41 s, two-pass loudness. */
        fun lastExport() {
            val mp4 = File(dir, "exports/export.mp4").apply { parentFile.mkdirs(); writeBytes(ByteArray(2_048)) }
            File(dir, "exports/export.result.json").writeBytes(RenderResult(mp4.name, 96_000, 41_000, mp4.length(),
                RenderState.Loudness(-14.0, LoudnessMeasurement(-14.2, -1.5, 6.0, -24.3, 0.2), null)).encode())
        }
        /** Every shot with its video accepted (the Video card's green check). */
        fun allAccepted() {
            storyboard()
            for (n in 1..project.shots.size) {
                storyboard.approve(n); shots.compilePrompt(n); shots.approvePrompt(n)
                refs.importRefBytes(project, shot(n), image(n), "png"); refs.approveRef(n)
                handoff.importVideoBytes(project, shot(n), byteArrayOf(n.toByte(), 1, 2), "mp4"); handoff.acceptVideo(n)
            }
        }
        /** The lyric font in Settings (the burned lyrics need a Burmese font, as in the app). */
        fun lyricFont() {
            val dir = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "fonts") }.first { File(it, "Pyidaungsu-Regular.ttf.b64").isFile }
            val font = File(root, "Pyidaungsu-Regular.ttf").apply { writeBytes(Base64.getMimeDecoder().decode(File(dir, "Pyidaungsu-Regular.ttf.b64").readBytes())) }
            settings.update { it.copy(fontPaths = listOf(font.path)) }
        }
        /** An Input model with no project open. */
        val noProject = InputModel(ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, File(root, "none.json").path), CurrentProject()), jobs)
        /** The samples' two characters (sample 01). */
        fun cast() {
            input.addCharacter(); input.setCharacterToken(0, "HERO"); input.setCharacterName(0, "မောင်မောင်")
            input.setCharacterDescription(0, "a young Burmese man in his 20s, short black hair, white shirt, green longyi")
            input.addCharacter(); input.setCharacterToken(1, "HEROINE"); input.setCharacterName(1, "သီတာ")
            input.setCharacterDescription(1, "a young Burmese woman, long hair with a jasmine flower, thanaka on her cheeks, red htamein")
        }
        /** The cast, then a storyboard (Gemini answers at once), so Generate asks for a new version. */
        fun storyboard() { cast(); gemini.go.complete(Unit); input.generate {} }
        override fun close() { scope.cancel(); root.deleteRecursively() }
    }

    private fun UiLanguage.code() = if (this == UiLanguage.BURMESE) "my" else "en"

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun write(name: String, image: java.awt.image.BufferedImage) {
        ImageIO.write(image, "png", File(out!!, "${language.code()}/mv-$name-$font.png").apply { parentFile.mkdirs() })
    }

    @Composable private fun Locals(width: Int, content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalCompact provides (width < 600), LocalDensity provides Density(SCALE, font), LocalUiLanguage provides language,
            LocalUiFontFamily provides BurmeseTestFont.family, content = content)

    /** The bar of this build (the "before" run drops the arguments it does not have). */
    @Composable private fun Bar(d: Demo, screen: String) =
        V2ProjectBar("Bagan Sunset", v2ProjectScreens(false), screen, {}, {}, inboxWaiting = d.shared.state.value.waiting.size)

    /** A project page as AppShell draws it: project bar, Gemini run card, the page. */
    private fun page(d: Demo, width: Int, screen: String = "Input", exit: Boolean = false,
        body: @Composable (Demo) -> Unit = { InputScreen(it.input, it.jobs, onGenerated = {}) }): @Composable () -> Unit = {
        Locals(width) {
            // The window draws the plain Background behind project pages (no ambient there, as in AppShell).
            StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat(), Modifier.background(AutoFlowColors.Background), editor = true) {
                Column(Modifier.fillMaxSize()) {
                    Bar(d, screen)
                    MusicGeminiJobCard(d.jobs, d.current.state.value, Modifier.fillMaxWidth().padding(screenPadding))
                    Box(Modifier.fillMaxWidth().weight(1f)) { body(d) }
                }
            }
            if (exit) ExitDialog({}, {})
        }
    }

    /**
     * Two passes: the first measures the page's scroll range after [open], the second draws it at its full height
     * ([full]) or at [height], writes the PNG and checks the texts.
     */
    private fun shot(name: String, width: Int = WIDTH, height: Int = HEIGHT, full: Boolean = true, waiting: Int = 0, running: Boolean = false,
        exit: Boolean = false, screen: String = "Input", body: (@Composable (Demo) -> Unit)? = null, prep: (Demo) -> Unit = {},
        crop: List<String>? = null, open: ComposeUiTest.() -> Unit = {}) {
        if (out == null) return
        var extra = 0
        fun ComposeUiTest.load(d: Demo) {
            prep(d)
            if (running) d.input.generate {}
            setContent(if (body == null) page(d, width, screen, exit) else page(d, width, screen, exit, body)); waitForIdle(); open(); waitForIdle()
        }
        if (full) runDesktopComposeUiTest(width = (width * SCALE).toInt(), height = (height * SCALE).toInt()) {
            Demo(waiting).use { d ->
                load(d)
                extra = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNodes()
                    .maxByOrNull { it.boundsInRoot.height }?.config?.get(SemanticsProperties.VerticalScrollAxisRange)?.maxValue()?.toInt() ?: 0
            }
        }
        runDesktopComposeUiTest(width = (width * SCALE).toInt(), height = (height * SCALE).toInt() + extra) {
            Demo(waiting).use { d ->
                load(d)
                val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
                if (crop == null) write(name, image) else {
                    // Only the [crop] nodes (e.g. the sheet's step cards 1–2) with 8 dp around them, the full page width.
                    val boxes = crop.map { onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
                    val top = (boxes.minOf { it.top } - 8 * SCALE).toInt().coerceAtLeast(0)
                    val bottom = (boxes.maxOf { it.bottom } + 8 * SCALE).toInt().coerceAtMost(image.height)
                    write(name, image.getSubimage(0, top, image.width, bottom - top))
                }
                assertEquals(emptyList(), textFitProblems((width * SCALE).toInt(), font, strict = true, minNodes = 3), "$name ${language.code()} $font")
            }
        }
    }

    /** A labelled sheet of [states] on the ambient background (like the samples' variant pages). */
    private fun sheet(name: String, tall: Int, states: List<Pair<String, @Composable (Demo) -> Unit>>, waiting: Int = 0, running: Boolean = false,
        prep: (Demo) -> Unit = {}) {
        if (out == null) return
        runDesktopComposeUiTest(width = (WIDTH * SCALE).toInt(), height = (tall * SCALE).toInt()) {
            Demo(waiting).use { d ->
                prep(d)
                if (running) d.input.generate {}
                setContent {
                    Locals(WIDTH) {
                        Column(Modifier.fillMaxWidth().ambientBackground().padding(vertical = 12.dp).testTag("states"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            states.forEach { (label, state) ->
                                AfText(label, Modifier.padding(horizontal = 12.dp), color = AutoFlowColors.PrimaryText, type = AfType.Caption)
                                state(d)
                            }
                        }
                    }
                }
                waitForIdle()
                val box = onNodeWithTag("states").fetchSemanticsNode().boundsInRoot
                val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
                write(name, image.getSubimage(0, 0, image.width, box.bottom.toInt().coerceAtMost(image.height)))
                assertEquals(emptyList(), textFitProblems((WIDTH * SCALE).toInt(), font, strict = true, minNodes = 3), "$name ${language.code()} $font")
            }
        }
    }

    /** PR 1 (A, 🆕-1 / 🆕-16, sample 15 / 15b / 04d): the bar on Input, ⋮ open, the variants, the exit dialog, desktop. */
    @Test fun projectBar() {
        shot("15-input")
        shot("15-menu", full = false) { click("mv-project-overflow") }
        sheet("15b-variants", if (font > 1.5f) 1500 else 900, listOf(
            "Input · project bar" to { d -> Bar(d, "Input") },
            "Shot sheet · Inbox chip with 3 shared files waiting" to { d -> Bar(d, SHOT_SHEET) },
            "Gemini run · running" to { d -> MusicGeminiJobCard(d.jobs, d.current.state.value, Modifier.fillMaxWidth().padding(screenPadding)) },
        ), waiting = 3, running = true)
        shot("15c-running", full = false, running = true)
        shot("04d-dialog-exit", full = false, exit = true)
        if (font == 1f) shot("15d-desktop", width = 1280, height = 800, full = false)
    }

    /** One Input card of [state] (the page's padding). */
    @Composable private fun InCard(content: @Composable () -> Unit) = Box(Modifier.fillMaxWidth().padding(screenPadding)) { content() }

    private fun InputUiState.noSong() = copy(songFile = null, durationText = "", settings = settings.copy(songStyle = "", mood = ""))

    /** PR 2 (B, samples 01 / 02 / 02b / 04a / 04c): the Input page, its states, the locked page, the Generate and Remove dialogs. */
    @Test fun inputPage() {
        shot("01-input", prep = Demo::storyboard)
        val hero = Character("HERO", "မောင်မောင်", "a young Burmese man in his 20s, short black hair, white shirt, green longyi")
        sheet("02-input-states", if (font > 1.5f) 7600 else 4300, listOf(
            "Song · no song yet (dashed drop box)" to { d -> InCard { SongCard(d.input.state.value.noSong(), d.input, false) } },
            "Song · importing" to { d -> InCard { SongCard(d.input.state.value.noSong().copy(songFile = "bagan_sunset.mp3", importingSong = true), d.input, false) } },
            "Song · just imported (green message)" to { d ->
                val notice = UiNotice(UiText.SONG_IMPORTED, "duration" to "1:36")
                InCard { SongCard(d.input.state.value.copy(songFile = "bagan_sunset.mp3", songMessage = notice.text(language), songNotice = notice, songMessageOk = true), d.input, false) }
            },
            "Characters · problems (amber) + a field with an error" to { d ->
                InCard { CharactersCard(d.input.state.value.copy(characters = listOf(hero, Character("HERO")), hasStoryboard = true), d.input, false) }
            },
            "Generate · blocked" to { d ->
                InCard { GenerateCard(d.input.state.value.copy(blockers = listOf(Blocker.EMPTY_LYRICS, Blocker.NO_DURATION)), JobState(), d.jobs, d.current.state.value, {}) }
            },
            "Generate · Gemini failed" to { d ->
                InCard { GenerateCard(d.input.state.value, JobState(last = StoryboardResult.Failed("Gemini: 503 Service Unavailable — the model is overloaded. Try again later.")),
                    d.jobs, d.current.state.value, {}) }
            },
            "No project open" to { d -> Box(Modifier.fillMaxWidth().height(if (font > 1.5f) 220.dp else 140.dp)) { InputScreen(d.noProject, d.jobs, onGenerated = {}) } },
        ))
        shot("02b-input-locked", full = false, running = true)
        shot("04a-dialog-generate", full = false, prep = Demo::storyboard) {
            onNodeWithText(UiText.GENERATE_STORYBOARD.text(language)).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        }
        shot("04c-dialog-remove-character", full = false, prep = Demo::cast) {
            onNodeWithContentDescription(UiText.V3_REMOVE_NAMED.text(language, "name" to "မောင်မောင်")).performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick)
        }
    }

    /** The synced lines of the demo lyrics (no section markers), as the tap panel gets them. */
    private val lines = LYRICS.lines().filter { !it.startsWith("[") }

    @Composable private fun Tap(sync: TapSync, playing: Boolean, position: Long) = InCard {
        LyricTimingCard { TapPanelBody(sync, playing, position, 96_000, {}, {}, {}, {}, {}, {}) }
    }

    @Composable private fun Status(state: LyricSyncUiState, running: Boolean = false) = InCard {
        LyricTimingCard { TimingStatusBody(state, running, locked = running, {}, {}, {}) }
    }

    /** PR 3 (B 2, 🆕-5, samples 03 / 02 / 04b): the tap panel's three states, the card's states, the Suggest dialog. */
    @Test fun lyricsTiming() {
        val starts = listOf(5_000L, 11_000L, 17_000L, 29_000L, 41_000L, 53_000L, 65_000L, 77_000L)
        sheet("03-lyrics-tap", if (font > 1.5f) 4600 else 2300, listOf(
            "Playing · tap when the line starts" to { _ -> Tap(TapSync(lines, starts.take(3)), true, 17_500) },
            "Last line · tap when it ends" to { _ -> Tap(TapSync(lines, starts), true, 93_000) },
            "All lines done · Save is on" to { _ -> Tap(TapSync(lines, starts, lastEndMs = 95_500), false, 96_000) },
        ))
        sheet("02c-timing-states", if (font > 1.5f) 3400 else 1700, listOf(
            "Lyrics timing · not synced" to { _ -> Status(LyricSyncUiState()) },
            "Lyrics timing · synced (tap)" to { _ -> Status(LyricSyncUiState(TimingStatus.SYNCED, 8, LyricTimings.SOURCE_TAP)) },
            "Lyrics timing · outdated (the lyrics changed)" to { _ -> Status(LyricSyncUiState(TimingStatus.OUTDATED, 8, LyricTimings.SOURCE_TAP)) },
            "Lyrics timing · Suggest timings running" to { _ -> Status(LyricSyncUiState(), running = true) },
            "Lyrics timing · desktop (no song player)" to { _ -> InCard { LyricTimingCard { NoPlayer {} } } },
        ))
        shot("04b-dialog-suggest-timings", full = false) {
            onNodeWithText(UiText.SUGGEST_TIMINGS.text(language)).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        }
    }

    /** Clicks the first clickable node showing [text] (the "before" build may add a symbol, Burmese may break syllables). */
    private fun ComposeUiTest.clickText(text: String) {
        onAllNodes(hasClickAction() and (hasText(text, substring = true) or hasText(My.breakSyllables(text), substring = true)))[0]
            .performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    /** The shot sheet as AppShell draws it on a phone (the list page first). */
    private val sheetPage: @Composable (Demo) -> Unit = sheetAt(WIDTH)
    private fun sheetAt(width: Int): @Composable (Demo) -> Unit = { d ->
        ShotSheetScreen(d.storyboard, d.jobs, d.shots, d.refs, d.handoff, readBytes = { runCatching { File(it).readBytes() }.getOrNull() },
            windowWidthDp = width.toFloat(), onGoToInput = {})
    }

    /** PR 4 (§3.2, C, 🆕-5, samples 05 / 06 / 09a): the shot list, All shots open, the empty states, the Regenerate all dialog. */
    @Test fun shotList() {
        shot("05-shot-list", screen = SHOT_SHEET, body = sheetPage, prep = Demo::statuses)
        shot("06a-all-shots-open", screen = SHOT_SHEET, body = sheetPage, prep = Demo::statuses) {
            click("shot-sheet-all")
            clickText(UiText.CREDIT_LOG_COUNT.text(language, "size" to 3))
        }
        shot("06b-filter-empty", screen = SHOT_SHEET, body = sheetPage, prep = Demo::storyboard) { click("shot-filter-ACCEPTED") }
        shot("06c-no-storyboard", screen = SHOT_SHEET, body = sheetPage)
        shot("09a-dialog-regenerate-all", full = false, screen = SHOT_SHEET, body = sheetPage, prep = Demo::statuses) {
            click("shot-sheet-all")
            clickText(UiText.REGENERATE_ALL.text(language))
        }
    }

    /**
     * PR 5 (D outer, 🆕-15, samples 07 / 08 / 10): shot 3 (imported: steps 1–4 done) on its phone sheet; shot 1 (accepted, all
     * five done, no previous) and shot 12 (draft, none done, no next) at the top; the desktop three panes at 1280 dp.
     */
    @Test fun shotSheet() {
        shot("07-shot-sheet", screen = SHOT_SHEET, body = sheetPage, prep = Demo::statuses) { click("shot-sheet-select-3") }
        shot("08a-sheet-accepted-first", full = false, screen = SHOT_SHEET, body = sheetPage, prep = Demo::statuses) { click("shot-sheet-select-1") }
        shot("08b-sheet-draft-last", full = false, screen = SHOT_SHEET, body = sheetPage, prep = Demo::statuses) {
            onNodeWithTag("shot-sheet-select-12").performScrollTo(); click("shot-sheet-select-12")
        }
        shot("10-desktop-shot-sheet", width = 1280, height = 800, full = false, screen = SHOT_SHEET, body = sheetAt(1280), prep = Demo::statuses) {
            click("shot-sheet-select-3")
        }
    }

    /**
     * PR 6 (D inner 1–2, 🆕-5, samples 07 / 08 / 09b / 09c): the Storyboard and Prompt step cards of one shot — shot 3
     * (both approved), shot 8 (draft: the prompt is behind the gate), shot 11 (prompt compiled, not approved), shot 12
     * (storyboard approved, prompt not compiled), shot 4 with the edit form open; the New take and Recompile questions.
     */
    @Test fun sheetCards() {
        val cards = listOf("sheet-step-1", "sheet-step-2")
        fun ComposeUiTest.select(n: Int) { onNodeWithTag("shot-sheet-select-$n").performScrollTo(); click("shot-sheet-select-$n") }
        shot("08c-cards-approved", screen = SHOT_SHEET, body = sheetPage, prep = Demo::statuses, crop = cards) { select(3) }
        shot("08d-cards-draft", screen = SHOT_SHEET, body = sheetPage, prep = Demo::statuses, crop = cards) { select(8) }
        shot("08e-cards-compiled", screen = SHOT_SHEET, body = sheetPage, crop = cards,
            prep = { it.statuses(); it.storyboard.approve(11); it.shots.compilePrompt(11) }) { select(11) }
        shot("08f-cards-not-compiled", screen = SHOT_SHEET, body = sheetPage, crop = cards,
            prep = { it.statuses(); it.storyboard.approve(12) }) { select(12) }
        shot("08g-storyboard-editing", screen = SHOT_SHEET, body = sheetPage, prep = Demo::statuses, crop = listOf("sheet-step-1")) {
            select(4); clickText(UiText.EDIT.text(language))
        }
        shot("09b-dialog-new-take", full = false, screen = SHOT_SHEET, body = sheetPage, prep = Demo::statuses) {
            select(3); clickText(UiText.REGENERATE.text(language))
        }
        shot("09c-dialog-recompile", full = false, screen = SHOT_SHEET, body = sheetPage, prep = Demo::statuses) {
            select(3); clickText(UiText.RECOMPILE.text(language))
        }
    }

    /**
     * PR 7 (D inner 3–4, samples 07 / 08): the References and Flow handoff step cards of one shot — shot 3 (ref approved, a
     * video imported, credits logged), shot 6 with two refs picked but not approved, shot 6 with none, shot 8 (the prompt
     * is not approved: the ref gate), shot 4 sent to Flow (Draft, in the batch sheet), shot 4 after an import on the Final
     * pass (the credit question).
     */
    @Test fun sheetRefsFlow() {
        val cards = listOf("sheet-step-3", "sheet-step-4")
        fun ComposeUiTest.select(n: Int) { onNodeWithTag("shot-sheet-select-$n").performScrollTo(); click("shot-sheet-select-$n") }
        fun ref(rgb: Int) = java.io.ByteArrayOutputStream().also { out ->
            ImageIO.write(java.awt.image.BufferedImage(16, 9, java.awt.image.BufferedImage.TYPE_INT_RGB).apply { setRGB(1, 1, rgb) }, "png", out)
        }.toByteArray()
        shot("08h-refs-flow-imported", screen = SHOT_SHEET, body = sheetPage, prep = Demo::statuses, crop = cards) { select(3) }
        shot("08i-refs-picked", screen = SHOT_SHEET, body = sheetPage, crop = cards, prep = { d ->
            d.statuses()
            listOf(0x336699, 0x996633).forEach { c -> d.refs.importRefBytes(d.current.state.value!!.project, d.current.state.value!!.project.shots.first { it.number == 6 }, ref(c), "png") }
        }) { select(6) }
        shot("08j-refs-none", screen = SHOT_SHEET, body = sheetPage, prep = Demo::statuses, crop = cards) { select(6) }
        shot("08k-refs-gate", screen = SHOT_SHEET, body = sheetPage, prep = Demo::statuses, crop = cards) { select(8) }
        shot("08l-flow-needs-flow", screen = SHOT_SHEET, body = sheetPage, crop = cards, prep = { it.statuses(); it.handoff.copyPrompt(4) }) { select(4) }
        shot("08m-flow-credits", screen = SHOT_SHEET, body = sheetPage, crop = cards, prep = { d ->
            d.statuses(); d.handoff.copyPrompt(4); d.handoff.setPass(4, io.github.alalkipgen.autoflow.core.model.Pass.FINAL)
            d.handoff.importVideoBytes(d.current.state.value!!.project, d.current.state.value!!.project.shots.first { it.number == 4 }, byteArrayOf(4, 1, 2), "mp4")
        }) { select(4) }
    }

    /**
     * PR 8 (D inner 5 + E, samples 07 / 08 / 10): the Videos step card of one shot — shot 3 with a second import on the
     * Final pass (v2 previewed, nothing accepted), the same shot with the Draft v1 used (the Draft hint), shot 6 with no
     * video; the desktop three panes (list · sheet · media pane) at 1280 dp come from [shotSheet].
     */
    @Test fun sheetVideos() {
        val cards = listOf("sheet-step-5")
        fun ComposeUiTest.select(n: Int) { onNodeWithTag("shot-sheet-select-$n").performScrollTo(); click("shot-sheet-select-$n") }
        fun Demo.second() {
            statuses(); handoff.setPass(3, io.github.alalkipgen.autoflow.core.model.Pass.FINAL)
            handoff.importVideoBytes(current.state.value!!.project, current.state.value!!.project.shots.first { it.number == 3 }, byteArrayOf(3, 2, 1), "mp4")
        }
        shot("08n-videos-two", screen = SHOT_SHEET, body = sheetPage, crop = cards, prep = { it.second() }) { select(3) }
        shot("08o-videos-draft-used", screen = SHOT_SHEET, body = sheetPage, crop = cards, prep = { d ->
            d.second(); d.shots.acceptVersion(3, d.current.state.value!!.project.shots.first { it.number == 3 }.videos.first())
        }) { select(3) }
        shot("08p-videos-none", screen = SHOT_SHEET, body = sheetPage, prep = Demo::statuses, crop = cards) { select(6) }
    }

    /** The Inbox as AppShell draws it (one model per demo). */
    private val inboxPage: @Composable (Demo) -> Unit = { d ->
        val model = remember(d) { InboxModel(d.session, d.fs) }
        InboxScreen(model, shared = d.shared)
    }

    /**
     * PR 9 (F, samples 11 / 11b): two shared files waiting over three inbox files, the video's shot chips open; the shared
     * files with no project open; Attach to shot with no storyboard; a file deleted (the notice); the empty inbox.
     */
    @Test fun inbox() {
        shot("11-inbox", screen = "Inbox", body = inboxPage, waiting = 2, prep = { it.statuses(); it.inboxFiles() }) {
            clickText(UiText.ATTACH_SHOT.text(language))
        }
        shot("11b-shared-noproject", screen = "Inbox", waiting = 2, body = { d ->
            val model = remember(d) { InboxModel(d.noSession, d.fs) }
            InboxScreen(model, shared = d.sharedNoProject)
        })
        shot("11b-attach-noshots", screen = "Inbox", body = inboxPage, prep = { it.inboxFiles() }) { clickText(UiText.ATTACH_SHOT.text(language)) }
        shot("11b-deleted", screen = "Inbox", body = inboxPage, prep = { it.statuses(); it.inboxFiles() }) {
            val tag = "inbox-file-ref_heroine_close.png"
            val delete = onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
            runOnIdle { delete.action() }
            waitForIdle()
            click("$tag-remove-menu")
            click("$tag-remove-confirm")
        }
        shot("11b-empty", screen = "Inbox", body = inboxPage, prep = Demo::statuses)
    }

    /** The Music Video Preview page as AppShell draws it, with a stand-in player (a sunset picture; no VLC in the shots). */
    private val previewPage: @Composable (Demo) -> Unit = { d ->
        Column(Modifier.fillMaxSize().paneScroll(rememberScrollState()).padding(screenPadding).testTag("mv-preview-page")) {
            MusicVideoPreview(d.export) { _, _, modifier, _ ->
                Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFFF6C77A), Color(0xFFEFA25E), Color(0xFFB8742E), Color(0xFF6E4520)))))
            }
        }
    }

    /** The clock to [ms] through the scrubber (the owner's drag). */
    private fun ComposeUiTest.seekPreview(ms: Long, durationMs: Long = 96_000L) {
        onNodeWithTag("mv-preview-scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it((ms + .5f) / durationMs) }
        waitForIdle()
    }

    /**
     * Play with the test clock held (the preview's frame clock would otherwise run to the end at once). With Fullscreen
     * open there are two Play buttons (card and window) on the same clock; either one plays.
     */
    private fun ComposeUiTest.playPreview() {
        mainClock.autoAdvance = false
        onAllNodesWithTag("mv-preview-play").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        mainClock.advanceTimeByFrame()
        waitForIdle()
    }

    /**
     * PR 10 (G + 🆕-3, samples 12 / 12b–d): playing on shot 3 at 0:19; a pending shot (8, its card); the hint open, paused
     * on shot 1 (the big Play); nothing to preview yet (the dashed box); Fullscreen portrait and landscape.
     */
    @Test fun preview() {
        // Shot 3's imported video accepted too (sample 12 plays shot 3); shots 4, 6, 8, 9, 11, 12 stay pending.
        val prep: (Demo) -> Unit = { it.statuses(); it.handoff.acceptVideo(3); it.lyricFont() }
        shot("12-preview", screen = MV_PREVIEW, body = previewPage, prep = prep) { seekPreview(19_000); playPreview() }
        shot("12b-pending", screen = MV_PREVIEW, body = previewPage, prep = prep) { seekPreview(58_000) }
        shot("12b-hint-open", screen = MV_PREVIEW, body = previewPage, prep = prep) {
            seekPreview(3_000)
            if (onAllNodesWithTag("mv-preview-hint-toggle").fetchSemanticsNodes().isNotEmpty()) click("mv-preview-hint-toggle")
        }
        shot("12b-empty", screen = MV_PREVIEW, body = previewPage, prep = { it.lyricFont() })
        shot("12c-fullscreen-portrait", screen = MV_PREVIEW, body = previewPage, prep = prep, full = false) {
            seekPreview(19_000); click("mv-preview-fullscreen"); playPreview()
        }
        shot("12d-fullscreen-landscape", width = HEIGHT, height = WIDTH, screen = MV_PREVIEW, body = previewPage, prep = prep, full = false) {
            seekPreview(19_000); click("mv-preview-fullscreen"); playPreview()
        }
    }

    /** The Music Video Export page as AppShell draws it: the Preview card (stand-in player) over the Export cards. */
    private val exportPage: @Composable (Demo) -> Unit = { d ->
        MusicVideoExportScreen(d.export, livePreview = {
            MusicVideoPreview(d.export) { _, _, modifier, _ ->
                Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFFF6C77A), Color(0xFFEFA25E), Color(0xFFB8742E), Color(0xFF6E4520)))))
            }
        })
    }

    /**
     * PR 11 (H + 🆕-4, samples 13 / 14): the page on shot 3 at 0:19 (6 videos pending, the song, 8 synced lines); a render
     * running at 62 % with a low battery; the last Export read back; not available here + no song; every video
     * accepted with no Burmese font and lyric timings older than the lyrics.
     */
    @Test fun export() {
        val base: (Demo) -> Unit = { it.statuses(); it.handoff.acceptVideo(3); it.lyricFont(); it.synced(); it.queued = true }
        shot("13-export", screen = "Export", body = exportPage, prep = base) { seekPreview(19_000) }
        shot("14-rendering-low-battery", screen = "Export", body = exportPage, prep = { d ->
            base(d); d.batteryPercent = 12; d.export.render(Quality.EXPORT); d.renderRun.value = RenderState.Running(.62, null)
        }) { seekPreview(19_000) }
        shot("14b-last-render", screen = "Export", body = exportPage, prep = { d -> base(d); d.lastExport() }) { seekPreview(19_000) }
        shot("14c-unavailable-no-song", screen = "Export", body = exportPage, prep = { d ->
            base(d); d.queued = false; File(d.dir, "source/song.mp3").delete()
        }) { seekPreview(19_000) }
        shot("14d-no-font-stale", screen = "Export", body = exportPage, prep = { d -> d.allAccepted(); d.synced(stale = true); d.queued = true }) {
            seekPreview(19_000)
        }
    }

    /** The song's beats (92 BPM): 8 of the 11 cuts after the first lie 180 ms off a beat and move, 3 sit on one. */
    private fun Demo.beats() {
        val cuts = (1..11).map { it * 8_000L }
        session.writeBeats(io.github.alalkipgen.autoflow.core.workflow.SongBeats(session.songSha256()!!, 92.0,
            cuts.mapIndexed { i, c -> if (i % 3 == 2) c else c + 180 }))
    }

    /** Snap cuts to beat on, waiting for the job (its beats are read on another thread). */
    private fun Demo.snap() {
        storyboard.setSnapToBeat(true)
        runBlocking { withTimeout(10_000) { while (jobs.state.value.running != null || jobs.state.value.last == null) delay(20) } }
        Thread.sleep(200)
    }

    /**
     * PR 12 (I + §8, sample 16): the Classic steps page; the classic References screen (shot 1 approved, shot 2 imported,
     * shot 3 behind the prompt gate); the classic sidebar's lock on desktop; Snap cuts to beat done (All shots and the
     * sheet's Storyboard card) and failed without ffmpeg; the Character Bible's problem lines.
     */
    @Test fun classic() {
        shot("16-classic-steps", full = false, screen = MV_CLASSIC, body = { MvClassicSteps {} })
        shot("16b-classic-references", height = if (font > 1.5f) 1900 else 1150, full = false, screen = "References", body = { d ->
            io.github.alalkipgen.autoflow.ui.references.ReferencesScreen(d.refs, readBytes = { runCatching { File(it).readBytes() }.getOrNull() })
        }, prep = { d ->
            d.storyboard()
            val project = { d.current.state.value!!.project }
            fun shot(n: Int) = project().shots.first { it.number == n }
            for (n in 1..2) { d.storyboard.approve(n); d.shots.compilePrompt(n); d.shots.approvePrompt(n) }
            for (n in 1..2) d.refs.importRefBytes(project(), shot(n), java.io.ByteArrayOutputStream().also { o ->
                ImageIO.write(java.awt.image.BufferedImage(16, 9, java.awt.image.BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, n * 0x202020) }, "png", o)
            }.toByteArray(), "png")
            d.refs.approveRef(1)
        })
        classicLock("16c-classic-lock")
        shot("06d-snap-done", screen = SHOT_SHEET, body = sheetPage, crop = listOf("all-shots-storyboard"), prep = { d ->
            d.storyboard(); d.beats(); d.snap()
        }) { click("shot-sheet-all") }
        shot("08n-snap-sheet", screen = SHOT_SHEET, body = sheetPage, crop = listOf("sheet-step-1"), prep = { d ->
            d.storyboard(); d.beats(); d.snap()
        }) { click("shot-sheet-select-3") }
        shot("08o-snap-no-ffmpeg", screen = SHOT_SHEET, body = sheetPage, crop = listOf("sheet-step-1"), prep = { d ->
            d.storyboard(); d.snap()
        }) { click("shot-sheet-select-3") }
        val hero = Character("HERO", "မောင်မောင်", "a young Burmese man in his 20s, short black hair, white shirt, green longyi")
        sheet("02c-character-problems", if (font > 1.5f) 3000 else 1600, listOf(
            "Characters · problems (amber)" to { d ->
                InCard { CharactersCard(d.input.state.value.copy(characters = listOf(hero, Character("HERO"))), d.input, false) }
            },
        ))
    }

    /** The classic (v1) desktop shell with no project: the sidebar's locked Shots clicked, its lock note open. */
    private fun classicLock(name: String) {
        if (out == null) return
        runDesktopComposeUiTest(width = (840 * SCALE).toInt(), height = (640 * SCALE).toInt()) {
            Demo().use { d ->
                val model = ShellModel(d.noSession)
                setContent {
                    Locals(840) {
                        val status = io.github.alalkipgen.autoflow.ui.settings.providerStatus(true, io.github.alalkipgen.autoflow.ui.settings.KeyState.MISSING, language)
                        AppShell(status, selected = "Dashboard", onSelect = {}, locks = model.current(language).locks) { AfText("Dashboard") }
                    }
                }
                waitForIdle()
                onNode(hasContentDescription(My.screen("Shots", language)) and isSelectable()).performScrollTo().performClick()
                waitForIdle()
                write(name, onAllNodes(isRoot())[0].captureToImage().toAwtImage())
            }
        }
    }
}
