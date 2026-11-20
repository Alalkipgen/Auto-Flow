package io.github.alalkipgen.autoflow.ui.voice

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.flags.FeatureFlags
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.store.ProjectStore
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
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** V-1c in the real app graph: flag → Home tile → chooser → text → Continue makes the project → text saved → Home Continue. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class VoiceAudioRouteTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    private fun ComposeUiTest.type(tag: String, text: String) = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag))).performTextInput(text)
    private fun ComposeUiTest.tap(tag: String) = onNodeWithTag(tag).performScrollTo().performMouseInput { click() }

    @Test fun tileChooserTextContinueMakesTheProjectAndHomeContinuesIt() = runDesktopComposeUiTest(width = 412, height = 760) {
        val root = Files.createTempDirectory("p7-voice-route").toFile()
        try {
            val fs = JvmFs()
            val home = File(root, "projects").apply { mkdirs() }.invariantSeparatorsPath
            val appDirs = AppDirs(File(root, "app").invariantSeparatorsPath, home)
            val app = koinApplication {
                modules(fakePlatformModule(), module {
                    single<FileSystem> { fs }; single { appDirs }; single<FilePicker> { FakePicker() }
                }, uiModule, aiModule)
            }
            try {
                app.koin.get<SettingsStore>().update {
                    it.copy(featureOverrides = mapOf(FeatureFlags.STUDIO_V2 to true, FeatureFlags.TOOLS_VOICE_AUDIO to true))
                }
                setContent { KoinIsolatedContext(app) { App() } }
                tap("home-voice-tool")
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("voice-task-text").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("voice-task-clean").assertIsEnabled() // V-2b: opens the picker
                onNodeWithTag("voice-task-video").assertIsEnabled() // V-3b: opens the picker
                assertTrue(app.koin.get<ProjectIndex>().entries.value.isEmpty(), "nothing is created by the chooser")
                tap("voice-task-text")
                onNodeWithTag("voice-continue").assertIsNotEnabled()
                tap("voice-back")
                onNodeWithTag("voice-task-text").assertExists()
                tap("voice-task-text")
                type("voice-text", "မင်္ဂလာပါ။ Hello there.")
                onNodeWithTag("voice-count").assertTextEquals(My.breakSyllables("23 characters · 1 parts"))
                assertNull(app.koin.get<CurrentProject>().state.value)
                onNodeWithTag("voice-continue").performMouseInput { click() }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("voice-part-p1").fetchSemanticsNodes().isNotEmpty() }
                val open = assertNotNull(app.koin.get<CurrentProject>().state.value)
                assertEquals(Mode.VOICE_AUDIO, open.project.mode)
                assertTrue(open.project.name.startsWith("Voice · "), open.project.name)
                val voice = assertNotNull(open.project.voice)
                assertEquals(VoiceTool.TASK_TEXT to "မင်္ဂလာပါ။ Hello there.", voice.task to voice.text)
                assertEquals(listOf("p1"), voice.parts.map { it.id })
                assertEquals(app.koin.get<VoiceAudioModel>().defaultLock(), voice.lock)

                type("voice-text", " Two.")
                val store = ProjectStore(fs)
                waitUntil(timeoutMillis = 5_000) { (store.open(open.dir) as? CoreResult.Ok)?.value?.voice?.text == "မင်္ဂလာပါ။ Hello there. Two." }
                assertEquals(listOf(Mode.VOICE_AUDIO), app.koin.get<ProjectIndex>().entries.value.map { it.mode })

                onNodeWithContentDescription(UiText.HOME.en).assertIsDisplayed().performMouseInput { click() }
                onNodeWithTag("home-mode-VOICE_AUDIO").performScrollTo().assertIsDisplayed()
                onNodeWithTag("home-continue-step").performScrollTo().assertTextEquals(UiText.V3_VOICE_AUDIO.en)
                onNodeWithTag("recent-${open.project.name}").performScrollTo().performMouseInput { click() }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("voice-text").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("မင်္ဂလာပါ။ Hello there. Two.").assertExists()
                onNodeWithTag("voice-continue").assertDoesNotExist()
            } finally { app.close() }
        } finally { root.deleteRecursively() }
    }

    @Test fun chooserAndTextStepFitAtSmallAndWideSizesInBothLanguages() {
        for ((width, font) in listOf(360 to 2f, 1440 to 1f)) for (language in UiLanguage.entries) for (withProject in listOf(false, true)) {
            runDesktopComposeUiTest(width = width, height = 760) {
                val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
                val index = ProjectIndex(fs, "/app/projects.json")
                val session = ProjectSession(fs, store, index, current)
                if (withProject) {
                    val dir = "/p/Voice.autoflow"
                    val created = (store.create(dir, "Voice · 2026-11-20 10:00", Mode.VOICE_AUDIO) as CoreResult.Ok).value
                    current.set(OpenProject(dir, (store.save(dir, created.copy(voice = VoiceTool(task = VoiceTool.TASK_TEXT,
                        text = "ဒီနေ့ ရာသီဥတု ကောင်းတယ်။ ".repeat(40)))) as CoreResult.Ok).value))
                }
                val model = VoiceAudioModel(session, DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), FakePicker(), current),
                    ToolsConfig(), MainScope(), models = listOf("gemini-2.5-flash-preview-tts"))
                if (!withProject) model.begin()
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
                        LocalUiFontFamily provides BurmeseTestFont.family) { VoiceAudioScreen(model, session, onBack = {}) }
                }
                if (withProject) {
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("voice-part-p1").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("voice-count").assertTextEquals(My.breakSyllables(UiText.VA_COUNT.text(language, "chars" to 1000, "n" to 2)))
                } else {
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("voice-task-text").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(emptyList(), textFitProblems(width, font), "$width × $font × $language chooser")
                    onNodeWithTag("voice-task-text").performScrollTo().performMouseInput { click() }
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("voice-continue").fetchSemanticsNodes().isNotEmpty() }
                }
                assertEquals(emptyList(), textFitProblems(width, font), "$width × $font × $language text, project=$withProject")
            }
        }
    }
}
