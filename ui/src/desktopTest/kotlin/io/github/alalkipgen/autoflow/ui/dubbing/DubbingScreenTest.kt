package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.flags.FeatureFlags
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.di.uiModule
import io.github.alalkipgen.autoflow.ui.fakePlatformModule
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.shell.App
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitleEditorModel
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlesModel
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.AfSize
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** D-1b: flag → Home Dubbing tile → picker → Dubbing project on Video; Dub script rows, edit, approve, locks. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class DubbingScreenTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }
    private val chosen = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "selected")
    private val tab = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)

    /** P9-13a: the step bar is the chooser — six (D-105) ≥ 48 dp tabs named by their step, only the shown one selected. */
    private fun ComposeUiTest.assertStepTabs(language: UiLanguage, shown: DubStep) = DubStep.entries.forEach { s ->
        onNodeWithTag("dub-step-${s.name}").assertHeightIsAtLeast(AfSize.Touch).assert(tab)
            .assert(hasContentDescription(s.text.text(language))).assert(if (s == shown) chosen else !chosen)
    }
    private fun shots() = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "dubbing").apply { mkdirs() }

    @Test fun tileCreatesADubbingProjectThatOpensOnVideo() = runDesktopComposeUiTest(width = 412, height = 760) {
        val root = Files.createTempDirectory("p7-dubbing-route").toFile()
        try {
            val fs = JvmFs()
            val appDirs = AppDirs(File(root, "app").invariantSeparatorsPath, File(root, "projects").apply { mkdirs() }.invariantSeparatorsPath)
            val picked = File(root, "Download/Rain clip.mp4").apply { parentFile.mkdirs(); writeText("not decodable here") }
            val app = koinApplication {
                modules(fakePlatformModule(), module {
                    single<FileSystem> { fs }; single { appDirs }
                    single<FilePicker> { FakePicker(files = listOf(picked.invariantSeparatorsPath)) }
                }, uiModule, aiModule)
            }
            try {
                app.koin.get<SettingsStore>().update {
                    it.copy(featureOverrides = mapOf(FeatureFlags.STUDIO_V2 to true, FeatureFlags.LABS_DUBBING to true))
                }
                setContent { KoinIsolatedContext(app) { App() } }
                onNodeWithTag("home-dubbing").performScrollTo().assertIsEnabled().performMouseInput { click() }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-notice").fetchSemanticsNodes().isNotEmpty() }
                val open = assertNotNull(app.koin.get<CurrentProject>().state.value)
                assertEquals(Mode.DUBBING, open.project.mode)
                assertNotNull(open.project.dubbing)
                assertTrue(open.project.name.startsWith("Rain clip · "), open.project.name)
                onNodeWithTag("project-progress").assertIsDisplayed()
                assertStepTabs(UiLanguage.ENGLISH, DubStep.VIDEO)
                onNodeWithTag("subtitles-notice").assertTextEquals(UiText.MOVIE_UNREADABLE.en) // no ffprobe here: says why
                onNodeWithTag("dub-step-SCRIPT").performMouseInput { click() }
                onNodeWithTag("dub-lock").assertIsDisplayed()
                onNodeWithTag("dub-go-VIDEO").performMouseInput { click() }
                onNodeWithTag("dub-step-VIDEO").assert(chosen)
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots(), "01-dubbing-412-video.png"))
                onNodeWithTag("project-back").performMouseInput { click() }
                onNodeWithTag("dub-step-VIDEO").assertDoesNotExist()
            } finally { app.close() }
        } finally { root.deleteRecursively() }
    }

    @Test fun dubScriptEditsAndApprovesAtSmallAndWideSizesInBothLanguages() {
        for ((width, font) in listOf(360 to 2f, 1440 to 1f)) for (language in UiLanguage.entries) {
            runDesktopComposeUiTest(width = width, height = 760) {
                val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
                val index = ProjectIndex(fs, "/app/projects.json")
                val dir = "/p/Dub.autoflow"
                val doc = SubtitleDoc(source = "srt", nextId = 3, cues = listOf(
                    SubtitleCue("c1", 1_000, 3_000, "We have to leave tonight.", textMy = "ဒီည ထွက်သွားရမယ်။"),
                    SubtitleCue("c2", 4_000, 6_000, "Before the rain starts.", textMy = "မိုးမရွာခင် သွားရမယ်။")))
                val created = (store.create(dir, "Dub", Mode.DUBBING) as CoreResult.Ok).value
                val st = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(),
                    ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
                val p = (store.save(dir, created.copy(dubbing = DubbingTool(), subtitles = SubtitlesTool(
                    video = SourceVideo("source/movie.mp4", 9, 65_000, 1920, 1080, 25.0), current = st))) as CoreResult.Ok).value
                current.set(OpenProject(dir, p))
                val session = ProjectSession(fs, store, index, current)
                val subtitles = SubtitlesModel(session, fs, DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), FakePicker(), current),
                    null, MainScope(), probe = { null })
                val script = DubScriptModel(session, fs)
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
                        LocalUiFontFamily provides BurmeseTestFont.family) {
                        DubbingScreen(script, subtitles, session, SubtitleEditorModel(session, fs, MainScope()), onBack = {},
                            player = { _, _, m, _ -> androidx.compose.foundation.layout.Box(m) })
                    }
                }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("dub-row-c1").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("dub-step-SCRIPT").assert(chosen) // every line translated → lands on Dub script
                assertStepTabs(language, DubStep.SCRIPT)
                onNodeWithContentDescription("${UiText.V3_DUBBING.text(language)} · Dub").assertIsDisplayed() // the mode before the project
                // P9-13a: a cue card — "#1" and its times in the head, no title row; Approve and Save carry the check.
                onNode(hasText("#1") and hasAnyAncestor(hasTestTag("dub-row-head-c1"))).assertIsDisplayed()
                onNode(hasText(io.github.alalkipgen.autoflow.core.subtitles.TimeText.let { "${it.show(1_000)} → ${it.show(3_000)}" }) and hasAnyAncestor(hasTestTag("dub-row-head-c1"))).assertExists()
                onNode(hasTestTag("button-icon") and hasAnyAncestor(hasTestTag("dub-approve")), useUnmergedTree = true).assertExists()
                onNodeWithTag("dub-slot-c1").assertTextEquals(My.breakSyllables(UiText.DUB_SLOT.text(language, "s" to 2.9)))
                onNodeWithTag("dub-approve").assertIsEnabled().performMouseInput { click() }
                waitUntil(timeoutMillis = 5_000) { current.state.value!!.project.dubbing!!.scriptApproved != null }
                // D-105: approved → the bar's button is Next: Voice (no disabled Approve), and "approved" is said once.
                onNodeWithTag("dub-approve").assertDoesNotExist()
                onNodeWithTag("dub-notice").assertDoesNotExist()
                onAllNodesWithText(My.breakSyllables(UiText.DUB_APPROVED.text(language)), useUnmergedTree = true).assertCountEquals(1)
                onNode(hasText(My.breakSyllables(UiText.NEXT_STEP.text(language, "step" to UiText.DUB_STEP_VOICE.text(language))))
                    and hasAnyAncestor(hasTestTag("dub-next-VOICE")), useUnmergedTree = true).assertExists()
                onNodeWithTag("dub-next-VOICE").assertIsEnabled().performMouseInput { click() }
                onNodeWithTag("dub-step-VOICE").assert(chosen)
                onNodeWithTag("dub-planned-VOICE").assertIsDisplayed().assertIsNotEnabled() // honest: D-2 not built
                onNodeWithTag("dub-step-SCRIPT").performMouseInput { click() }
                onNodeWithTag("dub-rows").performScrollToKey("c2")
                onNode(hasContentDescription(UiText.DUB_SPOKEN.text(language)) and hasAnyAncestor(hasTestTag("dub-spoken-c2")))
                    .performTextReplacement("မိုးမရွာခင်")
                onNode(hasTestTag("button-icon") and hasAnyAncestor(hasTestTag("dub-save-c2")), useUnmergedTree = true).assertExists()
                onNodeWithTag("dub-save-c2").performScrollTo().performMouseInput { click() }
                waitUntil(timeoutMillis = 5_000) { current.state.value!!.project.dubbing!!.scriptApproved == null }
                onNodeWithTag("dub-approve").assertIsEnabled() // the change cleared the approval
                onNodeWithTag("dub-use-subtitle-c2").performScrollTo().assertIsDisplayed()
                onNode(hasTestTag("button-icon") and hasAnyAncestor(hasTestTag("dub-use-subtitle-c2")), useUnmergedTree = true).assertExists()
                assertEquals(emptyList(), textFitProblems(width, font, strict = true))
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots(), "02-script-$width-${language.name.lowercase()}.png"))
            }
        }
    }

    @Test fun voiceStepSpeaksEveryLineAndShowsTheFitAtSmallAndWideSizesInBothLanguages() {
        for ((width, font) in listOf(360 to 2f, 1440 to 1f)) for (language in UiLanguage.entries) {
            runDesktopComposeUiTest(width = width, height = 760) {
                val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
                val index = ProjectIndex(fs, "/app/projects.json")
                val dir = "/p/Dub.autoflow"
                val doc = SubtitleDoc(source = "srt", nextId = 3, cues = listOf(
                    SubtitleCue("c1", 1_000, 3_000, "We have to leave tonight.", textMy = "ဒီည ထွက်သွားရမယ်။"),
                    SubtitleCue("c2", 4_000, 6_000, "Before the rain starts.", textMy = "မိုးမရွာခင် သွားရမယ်။")))
                val created = (store.create(dir, "Dub", Mode.DUBBING) as CoreResult.Ok).value
                val st = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(),
                    ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
                val tool = io.github.alalkipgen.autoflow.core.dubbing.DubRules.approve(DubbingTool(), 65_000, 600_000, doc, 1)!!
                val p = (store.save(dir, created.copy(dubbing = tool, subtitles = SubtitlesTool(
                    video = SourceVideo("source/movie.mp4", 9, 65_000, 1920, 1080, 25.0), current = st))) as CoreResult.Ok).value
                current.set(OpenProject(dir, p))
                val session = ProjectSession(fs, store, index, current)
                val subtitles = SubtitlesModel(session, fs, DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), FakePicker(), current),
                    null, MainScope(), probe = { null })
                val speak = DubSpeakJob(session, fs, io.github.alalkipgen.autoflow.ai.fake.FakeTtsProvider(msPerChar = 100))
                val jobScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
                val runner = io.github.alalkipgen.autoflow.ui.jobs.DesktopAiJobRunner(io.github.alalkipgen.autoflow.core.recap.AiJobStore(fs, "/files/ai-jobs"),
                    jobScope, io.github.alalkipgen.autoflow.ui.jobs.RecapJobProcessor({ null }, { null }, { null }, { null }, { speak }),
                    pacer = { io.github.alalkipgen.autoflow.core.recap.RpmPacer(1_000) })
                val voice = DubVoiceModel(session, speak, runner, jobScope, models = listOf("tts-a")) // not the Swing main: the start must not wait for it
                try {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
                            LocalUiFontFamily provides BurmeseTestFont.family) {
                            DubbingScreen(DubScriptModel(session, fs), subtitles, session, SubtitleEditorModel(session, fs, MainScope()), onBack = {},
                                player = { _, _, m, _ -> androidx.compose.foundation.layout.Box(m) }, voice = voice)
                        }
                    }
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("dub-voice-rows").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("dub-step-VOICE").assert(chosen) // approved → lands on Voice
                    onNodeWithTag("dub-voice-rows").performScrollToKey("c1")
                    onNodeWithTag("dub-fit-c1").assertTextEquals(My.breakSyllables(UiText.V3_NO_VOICE.text(language)))
                    // Desktop (Group 4 H): the voice card and Speak are on the right pane, not in the lines list.
                    if (width < 840) onNodeWithTag("dub-voice-rows").performScrollToKey("settings")
                    onNodeWithTag("dub-voice-Kore").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { current.state.value!!.project.dubbing!!.lock?.voice == "Kore" }
                    onNodeWithTag("dub-original-mute").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { current.state.value!!.project.dubbing!!.original.mode == "mute" }
                    onNodeWithTag("dub-speak").assertIsEnabled().performMouseInput { click() }
                    if (width < 840) onNodeWithTag("dub-voice-rows").performScrollToKey("speak")
                    onNodeWithTag("dub-speak-confirm").assertIsDisplayed()
                    onNodeWithTag("dub-speak-start").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 10_000) { current.state.value!!.project.dubbing!!.takes.size == 2 }
                    waitUntil(timeoutMillis = 10_000) { voice.state.value.ok } // the job finished: nothing else runs
                    onNodeWithTag("dub-voice-rows").performScrollToKey("c1")
                    onNodeWithTag("dub-fit-c1").assertTextEquals(My.breakSyllables(UiText.V3_FITS.text(language)))
                    onNodeWithTag("dub-voice-rows").performScrollToKey("c2")
                    onNodeWithTag("dub-fit-c2").assertTextEquals(My.breakSyllables(UiText.DUB_USES_PAUSE.text(language)))
                    // Desktop: the selected line opens in place with Speak this line again.
                    if (width >= 840) onNodeWithTag("dub-voice-head-c2").performMouseInput { click() }
                    onNodeWithTag("dub-redo-c2").assertIsEnabled()
                    onNodeWithTag("dub-next-STYLE").assertIsDisplayed()
                    assertEquals(emptyList(), textFitProblems(width, font))
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots(), "03-voice-$width-${language.name.lowercase()}.png"))
                } finally { jobScope.cancel() }
            }
        }
    }

    @Test fun shortenOffersGeminiLinesForTooLongTakesAndUseClearsTheApprovalAtBothSizesAndLanguages() {
        for ((width, font) in listOf(360 to 2f, 1440 to 1f)) for (language in UiLanguage.entries) {
            runDesktopComposeUiTest(width = width, height = 760) {
                val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
                val index = ProjectIndex(fs, "/app/projects.json")
                val dir = "/p/Dub.autoflow"
                val lock = io.github.alalkipgen.autoflow.core.recap.VoiceLock("gemini", "tts-a", "Kore", "my")
                val doc = SubtitleDoc(source = "srt", nextId = 3, cues = listOf(
                    SubtitleCue("c1", 1_000, 2_000, "We have to leave tonight.", textMy = "ဒီည ကျွန်တော်တို့ ထွက်သွားကြရမယ်။"),
                    SubtitleCue("c2", 2_500, 4_000, "Now.", textMy = "အခု။")))
                val created = (store.create(dir, "Dub", Mode.DUBBING) as CoreResult.Ok).value
                val st = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(),
                    ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
                val rules = io.github.alalkipgen.autoflow.core.dubbing.DubRules
                val takes = doc.cues.associate { c -> c.id to io.github.alalkipgen.autoflow.core.model.DubTake(
                    io.github.alalkipgen.autoflow.core.model.ArtifactId("au_00000000000${c.id.last()}"),
                    rules.takeKey(c, io.github.alalkipgen.autoflow.core.recap.Lexicon.EMPTY, lock, null), if (c.id == "c1") 3_000 else 600) }
                val tool = rules.approve(DubbingTool(lock = lock, takes = takes), 65_000, 600_000, doc, 1)!!
                val p = (store.save(dir, created.copy(dubbing = tool, subtitles = SubtitlesTool(
                    video = SourceVideo("source/movie.mp4", 9, 65_000, 1920, 1080, 25.0), current = st))) as CoreResult.Ok).value
                current.set(OpenProject(dir, p))
                val session = ProjectSession(fs, store, index, current)
                val subtitles = SubtitlesModel(session, fs, DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), FakePicker(), current),
                    null, MainScope(), probe = { null })
                val gemini = object : io.github.alalkipgen.autoflow.ai.TextProvider {
                    override suspend fun json(prompt: String, schema: io.github.alalkipgen.autoflow.ai.JsonSchema, system: String?) =
                        io.github.alalkipgen.autoflow.ai.AiResult.Ok(kotlinx.serialization.json.Json.parseToJsonElement(
                            """{"lines":[{"id":"c1","spokenMy":"ဒီည ထွက်ရမယ်။"}]}"""), io.github.alalkipgen.autoflow.ai.Usage("t"))
                }
                val shortScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
                val shorten = DubShortenModel(io.github.alalkipgen.autoflow.ai.dubbing.DubShortener(gemini), shortScope)
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
                        LocalUiFontFamily provides BurmeseTestFont.family) {
                        DubbingScreen(DubScriptModel(session, fs), subtitles, session, SubtitleEditorModel(session, fs, MainScope()), onBack = {},
                            player = { _, _, m, _ -> androidx.compose.foundation.layout.Box(m) },
                            voice = DubVoiceModel(session, null, null, MainScope(), models = listOf("tts-a")), shorten = shorten)
                    }
                }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("dub-voice-rows").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("dub-voice-rows").performScrollToKey("c1")
                onNodeWithTag("dub-fit-c1").assertTextEquals(My.breakSyllables(UiText.V3_TOO_LONG.text(language, "seconds" to 1.6)))
                onNodeWithTag("dub-voice-rows").performScrollToKey("shorten")
                onNodeWithTag("dub-shorten").performScrollTo().assertIsEnabled().performMouseInput { click() }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("dub-shorten-row-c1").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("dub-shorten-new-c1").assertTextEquals(My.breakSyllables("ဒီည ထွက်ရမယ်။"))
                onNodeWithTag("dub-shorten-row-c2").assertDoesNotExist() // c2 fits: never sent
                onNodeWithTag("dub-shorten-apply").assertIsNotEnabled()
                onNodeWithTag("dub-shorten-use-c1").performScrollTo().performMouseInput { click() }
                assertEquals(emptyList(), textFitProblems(width, font))
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots(), "04-shorten-$width-${language.name.lowercase()}.png"))
                onNodeWithTag("dub-shorten-apply").performScrollTo().assertIsEnabled().performMouseInput { click() }
                waitUntil(timeoutMillis = 5_000) { current.state.value!!.project.dubbing!!.scriptApproved == null }
                onNodeWithTag("dub-step-SCRIPT").assert(chosen) // back to Dub script to approve again
                onNodeWithTag("dub-notice").assertTextEquals(My.breakSyllables(UiText.DUB_SHORTEN_USED.text(language, "n" to 1)))
                onNodeWithTag("dub-approve").assertIsEnabled()
            }
        }
    }

    @Test fun exportMixesStepByStepAndShowsTheFilesAtBothSizesAndLanguages() {
        for ((width, font) in listOf(360 to 2f, 1440 to 1f)) for (language in UiLanguage.entries) {
            runDesktopComposeUiTest(width = width, height = 760) {
                val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
                val index = ProjectIndex(fs, "/app/projects.json")
                val dir = "/p/Dub.autoflow"
                val lock = io.github.alalkipgen.autoflow.core.recap.VoiceLock("gemini", "tts-a", "Kore", "my")
                val doc = SubtitleDoc(source = "srt", nextId = 3, cues = listOf(
                    SubtitleCue("c1", 1_000, 3_000, "We have to leave tonight.", textMy = "ဒီည ထွက်သွားရမယ်။"),
                    SubtitleCue("c2", 4_000, 6_000, "Before the rain starts.", textMy = "မိုးမရွာခင် သွားရမယ်။")))
                val created = (store.create(dir, "Dub", Mode.DUBBING) as CoreResult.Ok).value
                val art = ArtifactStore(fs, dir)
                val st = (art.put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(), ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
                val rules = io.github.alalkipgen.autoflow.core.dubbing.DubRules
                val takes = doc.cues.associate { c -> c.id to io.github.alalkipgen.autoflow.core.model.DubTake(
                    (art.put("RIFF-${c.id}".encodeToByteArray(), ArtifactKind.AUDIO, "wav") as CoreResult.Ok).value.id,
                    rules.takeKey(c, io.github.alalkipgen.autoflow.core.recap.Lexicon.EMPTY, lock, null), 1_500) }
                val tool = rules.approve(DubbingTool(lock = lock, takes = takes), 65_000, 600_000, doc, 1)!!
                val p = (store.save(dir, created.copy(dubbing = tool, subtitles = SubtitlesTool(
                    video = SourceVideo("source/movie.mp4", 9, 65_000, 1920, 1080, 25.0), current = st))) as CoreResult.Ok).value
                current.set(OpenProject(dir, p))
                val session = ProjectSession(fs, store, index, current)
                val subtitles = SubtitlesModel(session, fs, DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), FakePicker(), current),
                    null, MainScope(), probe = { null })
                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
                val queue = DubExportModelTest.Queue()
                // D-108: Set watermark… picks a real PNG (the preview decodes and draws it).
                val logo = java.io.ByteArrayOutputStream().also { out ->
                    ImageIO.write(java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB).apply {
                        createGraphics().apply { color = java.awt.Color(0, 220, 120); fillRect(0, 0, 64, 32); dispose() }
                    }, "png", out)
                }.toByteArray()
                fs.createDirectories("/pick"); fs.writeDurably("/pick/logo.png", logo)
                val export = DubExportModel(session, fs, io.github.alalkipgen.autoflow.ui.media.MediaTasks(queue, scope), scope,
                    picker = FakePicker(files = listOf("/pick/logo.png")))
                try {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
                            LocalUiFontFamily provides BurmeseTestFont.family) {
                            DubbingScreen(DubScriptModel(session, fs), subtitles, session, SubtitleEditorModel(session, fs, MainScope()), onBack = {},
                                player = { _, _, m, _ -> androidx.compose.foundation.layout.Box(m) },
                                voice = DubVoiceModel(session, null, null, scope, models = listOf("tts-a")), export = export)
                        }
                    }
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("dub-next-STYLE").fetchSemanticsNodes().isNotEmpty() }
                    // F-6 (D-95): Voice and Export show the dubbed video before any render, both spoken lines in it.
                    // Group 4 🆕-1: the scope caption names the voices; the long hint opens behind ⓘ.
                    onNodeWithTag("dub-preview-scope").assertTextEquals(My.breakSyllables(UiText.V3_DUB_PREVIEW_SCOPE.text(language, "n" to 2)))
                    onNodeWithTag("dub-preview-hint").assertDoesNotExist()
                    onNodeWithTag("dub-preview-info").performScrollTo().performMouseInput { click() }
                    onNodeWithTag("dub-preview-hint").assertTextEquals(My.breakSyllables(UiText.DUB_PREVIEW_HINT.text(language, "n" to 2)))
                    onNodeWithTag("dub-preview").performScrollTo().assertIsDisplayed()
                    // D-105: Voice → Style (the Burmese subtitles in the video) → Export.
                    onNodeWithTag("dub-next-STYLE").performMouseInput { click() }
                    onNodeWithTag("dub-step-STYLE").assert(chosen)
                    onNodeWithTag("dub-preview").assertIsDisplayed()
                    onNodeWithTag("dub-style-captions").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("dub-back-VOICE").assertIsEnabled()
                    onNodeWithTag("dub-burn-lines-1").assertDoesNotExist()
                    onNodeWithTag("dub-burn").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("dub-burn-lines-1").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("dub-burn-lines-1").performScrollTo().assert(chosen) // 1920×1080: 1 short line
                    onNodeWithTag("dub-burn-length-SHORT").performScrollTo().assert(chosen)
                    onNodeWithTag("dub-burn-size-MEDIUM").performScrollTo().assert(chosen)
                    onNodeWithTag("dub-burn-lines-2").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { export.fit().lines == 2 }
                    onNodeWithTag("dub-burn-lines-2").assert(chosen)
                    assertEquals(emptyList(), textFitProblems(width, font))
                    // D-106: Movie Recap's caption style on the Dubbing project (saved; the preview draws it).
                    val styleOf = { current.state.value!!.project.dubbing!!.captionStyle }
                    onNodeWithTag("dub-caption-preset-status").performScrollTo()
                        .assertTextEquals(My.breakSyllables(UiText.V3_CAPTION_PRESET_STATUS.text(language, "name" to UiText.V3_CAPTION_BOXED.text(language))))
                    onNodeWithTag("dub-caption-preset-BOXED").assert(chosen)
                    onNodeWithTag("dub-caption-outline-3").assertDoesNotExist() // the box has no outline
                    onNodeWithTag("dub-caption-preset-OUTLINE").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { !styleOf().box && styleOf().strokeWidth == 3 }
                    onNodeWithTag("dub-caption-outline-3").performScrollTo().assert(chosen)
                    onNodeWithTag("dub-caption-colour-Yellow").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { styleOf().colour == "#FFE14D" }
                    onNodeWithTag("dub-caption-preset-status")
                        .assertTextEquals(My.breakSyllables(UiText.V3_CAPTION_PRESET_STATUS.text(language, "name" to UiText.V3_CAPTION_CUSTOM.text(language))))
                    onNodeWithTag("dub-caption-outline-colour-Red").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { styleOf().strokeColour == "#FF4444" }
                    onNodeWithTag("dub-burn-size-XL").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { styleOf().customSizeFactor == 1.4f }
                    onNodeWithTag("dub-burn-size-MEDIUM").assert(!chosen)
                    assertEquals(emptyList(), textFitProblems(width, font))
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots(), "04b-style-captions-$width-${language.name.lowercase()}.png"))
                    onNodeWithTag("dub-caption-preset-BOXED").performScrollTo().performMouseInput { click() }
                    onNodeWithTag("dub-burn-size-MEDIUM").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { styleOf() == io.github.alalkipgen.autoflow.core.model.DubbingTool.DEFAULT_CAPTIONS }
                    // D-107: Hide things — the bottom strip blurred, one more box filled white; drawn on the preview, then removed.
                    val masksOf = { current.state.value!!.project.dubbing!!.masks }
                    onNodeWithTag("dub-preview-boxes").assertDoesNotExist()
                    onNodeWithTag("dub-hide-BOTTOM").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { masksOf().map { it.kind } == listOf(io.github.alalkipgen.autoflow.core.model.DubMaskKind.BOTTOM) }
                    onNodeWithTag("dub-hide-BOTTOM").assert(chosen)
                    onNodeWithTag("dub-preview-boxes").assertExists()
                    onNodeWithTag("dub-hide-captions-on-box").performScrollTo().assertIsOn()
                    onNodeWithTag("dub-hide-add").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { masksOf().size == 2 }
                    onNodeWithTag("dub-box-1-fill").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { masksOf()[1].fill == "#000000" }
                    onNodeWithTag("dub-box-1-fill-White").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { masksOf()[1].fill == "#FFFFFF" }
                    onNodeWithTag("dub-box-1-wider").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { masksOf()[1].rect[2] > .4f }
                    onNodeWithTag("dub-hide-touch").performScrollTo()
                        .assertTextEquals(My.breakSyllables(UiText.MASK_GESTURE_HINT.text(language, "maskLabelp" to UiText.DUB_BOX_N.text(language, "n" to 1))))
                    assertEquals(emptyList(), textFitProblems(width, font))
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots(), "04c-style-hide-$width-${language.name.lowercase()}.png"))
                    onNodeWithTag("dub-box-1-remove").performScrollTo().performMouseInput { click() }
                    onNodeWithTag("dub-hide-BOTTOM").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { masksOf().isEmpty() }
                    onNodeWithTag("dub-preview-boxes").assertDoesNotExist()
                    // D-108: Watermark — Set watermark…, its place; drawn on the preview; Remove.
                    val markOf = { current.state.value!!.project.dubbing!!.watermark }
                    onNodeWithTag("dub-preview-watermark").assertDoesNotExist()
                    onNodeWithTag("dub-watermark-set").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { markOf() != null }
                    onNodeWithTag("dub-watermark-file").performScrollTo().assertTextEquals(markOf()!!.file.substringAfterLast('/'))
                    onNodeWithTag("dub-watermark-notice")
                        .assertTextEquals(My.breakSyllables(UiText.WATERMARK_SET.text(language, "name" to markOf()!!.file.substringAfterLast('/'))))
                    onNodeWithTag("dub-watermark-at-BOTTOM_RIGHT").performScrollTo().assert(chosen)
                    onNodeWithTag("dub-watermark-at-TOP_LEFT").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { markOf()?.anchor == io.github.alalkipgen.autoflow.core.recap.WatermarkAnchor.TOP_LEFT }
                    onNodeWithTag("dub-watermark-at-TOP_LEFT").assert(chosen)
                    onNodeWithTag("watermark-width-value").performScrollTo()
                        .assertTextEquals(My.breakSyllables(UiText.WATERMARK_WIDTH_VALUE.text(language, "n" to 22)))
                    onNodeWithTag("dub-preview-watermark").assertExists()
                    assertEquals(emptyList(), textFitProblems(width, font))
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots(), "04d-style-watermark-$width-${language.name.lowercase()}.png"))
                    onNodeWithTag("dub-watermark-remove").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { markOf() == null }
                    onNodeWithTag("dub-preview-watermark").assertDoesNotExist()
                    onNodeWithTag("dub-watermark-set").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("dub-burn").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("dub-burn-lines-1").fetchSemanticsNodes().isEmpty() }
                    onNodeWithTag("dub-burn").performScrollTo().performMouseInput { click() } // on again: Export says so
                    waitUntil(timeoutMillis = 5_000) { current.state.value!!.project.dubbing!!.burnSubtitles }
                    onNodeWithTag("dub-next-EXPORT").assertIsEnabled().performMouseInput { click() }
                    onNodeWithTag("dub-step-EXPORT").assert(chosen)
                    onNodeWithTag("dub-preview").assertIsDisplayed()
                    onNodeWithTag("dub-play").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("dub-export-original").performScrollTo()
                    onNodeWithTag("dub-export-blocks").assertDoesNotExist()
                    onNodeWithTag("dub-export-original").assertTextEquals(My.breakSyllables(UiText.DUB_ORIGINAL.text(language) + ": " + UiText.V3_DUB_LOWER.text(language, "percent" to 15)))
                    onNodeWithTag("dub-export-burn").assertTextEquals(My.breakSyllables(UiText.DUB_BURN_ON.text(language)))
                    onNodeWithTag("dub-burn").assertDoesNotExist() // chosen on Style, not here
                    onNodeWithTag("dub-export-go-style").performScrollTo().performMouseInput { click() }
                    onNodeWithTag("dub-step-STYLE").assert(chosen)
                    onNodeWithTag("dub-burn").performScrollTo().performMouseInput { click() } // off for this export
                    waitUntil(timeoutMillis = 5_000) { !current.state.value!!.project.dubbing!!.burnSubtitles }
                    onNodeWithTag("dub-next-EXPORT").performMouseInput { click() }
                    onNodeWithTag("dub-export-burn").performScrollTo().assertTextEquals(My.breakSyllables(UiText.DUB_BURN_OFF.text(language)))
                    onNodeWithTag("dub-save-wav").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { export.state.value.wav }
                    onNodeWithTag("dub-export-go").assertIsEnabled().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { queue.jobs.size == 1 }
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("dub-export-job").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("dub-export-job").performScrollTo()
                    onNodeWithTag("dub-export-go").assertIsNotEnabled()
                    assertEquals(emptyList(), textFitProblems(width, font))
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots(), "05-export-running-$width-${language.name.lowercase()}.png"))
                    for (i in 0 until 4) { waitUntil(timeoutMillis = 5_000) { queue.jobs.size == i + 1 }; queue.done(i) }
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("dub-export-done").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("dub-export-done").performScrollTo()
                    onNodeWithTag("dub-export-srt").assertExists()
                    onNodeWithTag("dub-export-wav").assertExists()
                    onNodeWithTag("dub-export-folder").assertExists() // no file sharing here: Open folder
                    assertEquals(emptyList(), textFitProblems(width, font))
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots(), "06-export-done-$width-${language.name.lowercase()}.png"))
                } finally { scope.cancel() }
            }
        }
    }
}
