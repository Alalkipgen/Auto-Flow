package io.github.alalkipgen.autoflow.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.ambientBackground
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.inbox.SharedInbox
import io.github.alalkipgen.autoflow.ui.input.InputModel
import io.github.alalkipgen.autoflow.ui.input.InputScreen
import io.github.alalkipgen.autoflow.ui.jobs.MusicGeminiJobCard
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
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
        exit: Boolean = false, open: ComposeUiTest.() -> Unit = {}) {
        if (out == null) return
        var extra = 0
        fun ComposeUiTest.load(d: Demo) {
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
    private fun sheet(name: String, tall: Int, states: List<Pair<String, @Composable (Demo) -> Unit>>, waiting: Int = 0, running: Boolean = false) {
        if (out == null) return
        runDesktopComposeUiTest(width = (WIDTH * SCALE).toInt(), height = (tall * SCALE).toInt()) {
            Demo(waiting).use { d ->
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
}
