package io.github.alalkipgen.autoflow.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
        val jobs = StoryboardJobs(StoryboardService(session, StoryboardGenerator(gemini)), scope)
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
    private fun page(d: Demo, width: Int, screen: String = "Input", exit: Boolean = false): @Composable () -> Unit = {
        Locals(width) {
            // The window draws the plain Background behind project pages (no ambient there, as in AppShell).
            StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat(), Modifier.background(AutoFlowColors.Background), editor = true) {
                Column(Modifier.fillMaxSize()) {
                    Bar(d, screen)
                    MusicGeminiJobCard(d.jobs, d.current.state.value, Modifier.fillMaxWidth().padding(screenPadding))
                    Box(Modifier.fillMaxWidth().weight(1f)) { InputScreen(d.input, d.jobs, onGenerated = {}) }
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
        exit: Boolean = false, prep: (Demo) -> Unit = {}, open: ComposeUiTest.() -> Unit = {}) {
        if (out == null) return
        var extra = 0
        fun ComposeUiTest.load(d: Demo) {
            prep(d)
            if (running) d.input.generate {}
            setContent(page(d, width, exit = exit)); waitForIdle(); open(); waitForIdle()
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
                write(name, onAllNodes(isRoot())[0].captureToImage().toAwtImage())
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
}
