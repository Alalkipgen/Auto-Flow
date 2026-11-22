package io.github.alalkipgen.autoflow.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiTimingSample
import io.github.alalkipgen.autoflow.core.recap.AiTimingStage
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.BackupProgress
import io.github.alalkipgen.autoflow.platform.BackupProject
import io.github.alalkipgen.autoflow.platform.BackupResult
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.ProjectBackup
import io.github.alalkipgen.autoflow.platform.RestoredProject
import io.github.alalkipgen.autoflow.platform.SecretNames
import io.github.alalkipgen.autoflow.platform.SecureStorage
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Dest
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Shell
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P9-17a screenshots (Settings sample README §6): every image of the sample — the main page, the nine pages, the
 * desktop Tools page and the §3 variants — on a 360 dp phone at density 2, font 1.0 and 2.0, English and Burmese, the
 * bundled Pyidaungsu, with the sample's demo data (37 calls today, keys ••••7Kq2 / ••••c9d4, 7 projects, "Myanmar
 * Sagar", 9:16 · 8s, 12 flags with 6 on, version 0.4.2 (f3d49eb)), at the full page height like the sample. Runs only
 * when `AUTOFLOW_GLASS_SHOTS_DIR` is set; writes `<language>/settings-<name>-<font>.png` and checks that no text is cut,
 * clipped or truncated.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class SettingsShotsTest(val language: UiLanguage, val font: Float) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}")
        fun cases() = listOf(UiLanguage.ENGLISH, UiLanguage.BURMESE).flatMap { l -> listOf(1f, 2f).map { arrayOf<Any>(l, it) } }
        private const val SCALE = 2f
        private const val WIDTH = 360
        /** The sample's pages are at least 780 dp tall (1560 px at @2x). */
        private const val HEIGHT = 780
        private val FLAGS = mapOf(
            "studioV2" to true, "quickRecap" to true, "tools.subtitles" to true, "tools.voiceAudio" to true,
            "labs.voiceClone" to false, "labs.dubbing" to true, "labs.vocalSeparation" to false, "labs.smartReframe" to false,
            "labs.autoCaptionPlacement" to false, "labs.wordKaraoke" to false, "labs.wholeMovieTranscribe" to false, "labs.edgeVoices" to true,
        )
        private val TIMINGS = listOf(
            AiTimingStage.QUEUE to listOf(101L), AiTimingStage.PACER to List(5) { 10_166L + if (it == 0) 4 else 0 },
            AiTimingStage.UPLOAD to List(5) { 9L }, AiTimingStage.FIRST_BYTE to List(5) { 3_173L },
            AiTimingStage.RESPONSE to List(5) { 3_186L + if (it == 0) 1 else 0 }, AiTimingStage.PARSE to List(10) { if (it < 6) 7L else 6L },
            AiTimingStage.SAVE to List(5) { if (it < 3) 62L else 61L },
        )
    }
    private val out = System.getenv("AUTOFLOW_GLASS_SHOTS_DIR")?.let(::File)
    private val modelsJson = listOf(File("../config/models.json"), File("config/models.json")).first { it.exists() }.readText()

    /** A key store that holds keys it cannot read back (README §3 "key saved but unreadable"). */
    private object Unreadable : SecureStorage {
        override fun get(name: String): PlatformResult<String?> = PlatformResult.Failed("cannot decrypt")
        override fun put(name: String, value: String): PlatformResult<Unit> = PlatformResult.Failed("cannot encrypt")
        override fun remove(name: String): PlatformResult<Unit> = PlatformResult.Ok(Unit)
    }

    /** Backup that stops at 42 % until [gate] completes ([done] = already finished). */
    private class DemoBackup(done: Boolean) : ProjectBackup {
        val gate = CompletableDeferred<PlatformResult<BackupResult>>().apply {
            if (done) complete(PlatformResult.Ok(BackupResult("Download/AutoFlow/AutoFlow-backup-2025-06-01.zip", 48_200_000, 7, 212, "content://backup")))
        }
        override suspend fun backUp(projects: List<BackupProject>, onProgress: (BackupProgress) -> Unit): PlatformResult<BackupResult> {
            onProgress(BackupProgress(420, 1_000))
            return gate.await()
        }
        override fun share(result: BackupResult): PlatformResult<Unit> = PlatformResult.Ok(Unit)
        override suspend fun restore(zipPath: String, destDir: String): PlatformResult<List<RestoredProject>> = PlatformResult.Ok(emptyList())
    }

    /** The sample's demo state; [desktop] = Windows tools (not bundled), [secrets] / [fonts] for the variants. */
    private inner class Demo(desktop: Boolean = false, secrets: SecureStorage? = null, fonts: Boolean = true, done: Boolean = false) {
        val fs = InMemoryFileSystem()
        private val store = SettingsStore(fs, "/app/settings.json").apply {
            update {
                it.copy(uiLanguage = language.code, useFakeProviders = false, defaultRatio = Ratio.R9_16, defaultClipMs = 8_000,
                    fontPaths = if (fonts) listOf("/storage/emulated/0/Download/Fonts/Myanmar Sagar.ttf") else emptyList(),
                    featureOverrides = FLAGS, ffmpegPath = if (desktop) "C:\\ffmpeg\\bin\\ffmpeg.exe" else null)
            }
        }
        private val keys = secrets ?: MemorySecureStorage().apply {
            values[SecretNames.GEMINI_API_KEY] = "demo-not-a-real-key-7Kq2"
            values[SecretNames.CHATBOX_LICENSE_KEY] = "demo-license-c9d4"
        }
        private val usage = UsageCounter(fs, "/app/usage.json").apply {
            runBlocking { repeat(30) { record("gemini-3.5-flash") }; repeat(7) { record("gemini-2.5-flash-preview-tts") } }
        }
        private val ffmpeg = ToolRunner { cmd, _, _, _, _ ->
            val text = when (cmd.last()) {
                "-version" -> "ffmpeg version 9.0.2\nconfiguration: --enable-gpl --enable-libass --enable-libx264"
                "-encoders" -> " V....D libx264              libx264 H.264\n A....D aac                  AAC"
                else -> ""
            }
            ToolExec.Done(0, text, emptyList(), 5, false)
        }
        private val builtIn = BuiltInFonts(fs, "/app/fonts", object : BundledConfig {
            override fun modelsJson() = "{}"
            override fun bundledFont(name: String) = byteArrayOf(1)
        })
        val model = SettingsModel(
            store, keys, ProviderStatusModel(store, keys), usage, loadModelConfig(fs, null) { modelsJson },
            if (desktop) FakeTools(ffmpeg = "C:\\ffmpeg\\bin\\ffmpeg.exe") else FakeTools(ffmpeg = "/data/app/lib/arm64/libffmpeg.so", bundled = true),
            FakePicker(), { PlatformResult.Ok(Unit) }, { AiResult.Ok(Unit, Usage(model = "gemini-3.5-flash", latencyMs = 1)) },
            ffmpegRunner = ffmpeg, testTtsCall = { AiResult.Ok(byteArrayOf()) }, builtInFonts = builtIn,
            testChatboxCall = { AiResult.Ok(Unit, Usage(model = "gemini-2.5-flash", latencyMs = 1)) },
        )
        val backup = DemoBackup(done)
        val backupModel: BackupModel
        init {
            val projects = ProjectStore(fs)
            val index = ProjectIndex(fs, "/app/projects.json")
            listOf("Dune: Part Two", "Kung Fu Panda 4", "Bagan Vlog", "Rain Song", "Interstellar", "Inle Lake", "Thingyan").forEachIndexed { i, name ->
                val dir = "/app/projects/p$i.autoflow"
                index.record(dir, projects.create(dir, name, Mode.MOVIE_RECAP).getOrNull()!!)
            }
            backupModel = BackupModel(backup, index, fs, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        }
        private val runner = object : AiJobRunner {
            override val jobs = MutableStateFlow(listOf(AiJob("speak", AiJobKind.TTS_ALL, "p0.autoflow", state = AiJobState.DONE,
                timings = TIMINGS.flatMap { (stage, ms) ->
                    val sized = stage == AiTimingStage.UPLOAD || stage == AiTimingStage.RESPONSE
                    ms.mapIndexed { i, it -> AiTimingSample(stage, it, bytes = if (sized) 280L + if (i == 0) 1 else 0 else null) }
                },
                timingsUpdatedAt = 1L)))
            override suspend fun enqueue(job: AiJob) = job.id
            override suspend fun cancel(id: String) = Unit
            override suspend fun refresh() = Unit
        }
        val content: @Composable () -> Unit = {
            StudioV2Shell(StudioV2Dest.HOME, {}, 2, WIDTH.toFloat(), ambient = true) {
                SettingsScreen(model, info = AppInfo("0.4.2 (f3d49eb)") {}, backup = backupModel, onOpenLexicon = {}, runner = runner, onBack = {})
            }
        }
    }

    private fun UiLanguage.code() = if (this == UiLanguage.BURMESE) "my" else "en"

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    /**
     * Two passes like GlassShotsTest: the first measures the page's scroll range (the tallest scroller, not the JSON box)
     * at 780 dp after [open], the second
     * draws the page at its full height, writes the PNG and checks the texts.
     */
    private fun shot(name: String, demo: () -> Demo = { Demo() }, open: ComposeUiTest.(Demo) -> Unit = {}) {
        val dir = out ?: return
        fun ComposeUiTest.show(): Demo {
            val d = demo()
            setContent {
                CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(SCALE, font), LocalUiLanguage provides language,
                    LocalUiFontFamily provides BurmeseTestFont.family, content = d.content)
            }
            waitForIdle()
            open(d)
            waitForIdle()
            return d
        }
        var extra = 0
        runDesktopComposeUiTest(width = (WIDTH * SCALE).toInt(), height = (HEIGHT * SCALE).toInt()) {
            show()
            extra = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNodes()
                .maxByOrNull { it.boundsInRoot.height }?.config?.get(SemanticsProperties.VerticalScrollAxisRange)?.maxValue()?.toInt() ?: 0
        }
        runDesktopComposeUiTest(width = (WIDTH * SCALE).toInt(), height = (HEIGHT * SCALE).toInt() + extra) {
            show()
            val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
            ImageIO.write(image, "png", File(dir, "${language.code()}/settings-$name-$font.png").apply { parentFile.mkdirs() })
            assertEquals(emptyList(), textFitProblems((WIDTH * SCALE).toInt(), font, strict = true, minNodes = 3), "$name ${language.code()} $font")
        }
    }

    @Test fun main() = shot("0-main")
    @Test fun language() = shot("1-language") { click("settings-row-language") }
    @Test fun aiKeys() = shot("2-ai-keys") { click("settings-row-gemini") }
    @Test fun modelConfig() {
        shot("3-model-config") { click("settings-row-models") }
        shot("3-model-config-open") {
            click("settings-row-models")
            onNodeWithContentDescription(UiText.V3_MORE_OPTIONS.text(language)).performSemanticsAction(SemanticsActions.OnClick)
        }
    }
    @Test fun exportDefaults() = shot("4-export-defaults") { click("settings-row-defaults") }
    @Test fun fonts() = shot("5-fonts") { click("settings-row-fonts") }
    @Test fun tools() {
        shot("6-tools") { click("settings-row-tools") }
        shot("6b-tools-desktop", { Demo(desktop = true) }) { click("settings-row-tools") }
    }
    @Test fun backup() = shot("7-backup") { click("settings-row-backup") }
    @Test fun previewFeatures() = shot("8-preview-features") { click("settings-row-labs") }
    @Test fun diagnostics() = shot("9-diagnostics") { click("settings-row-diagnostics") }

    /** README §3: Gemini key MISSING · UNREADABLE (Chatbox follows the same rules) · Fonts empty · Backup 42 % · after a backup. */
    @Test fun variants() {
        shot("10a-key-missing", { Demo(secrets = MemorySecureStorage()) }) { click("settings-row-gemini") }
        shot("10b-key-unreadable", { Demo(secrets = Unreadable) }) { click("settings-row-gemini") }
        shot("10c-fonts-empty", { Demo(fonts = false) }) { click("settings-row-fonts") }
        shot("10d-backup-running") {
            click("settings-row-backup")
            onNodeWithText(UiText.BACKUP_ALL.text(language)).performSemanticsAction(SemanticsActions.OnClick)
        }
        shot("10e-backup-done", { Demo(done = true) }) {
            click("settings-row-backup")
            onNodeWithText(UiText.BACKUP_ALL.text(language)).performSemanticsAction(SemanticsActions.OnClick)
        }
    }
}
