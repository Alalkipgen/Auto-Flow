package io.github.alalkipgen.autoflow.ui.voice

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.ai.fake.FakeTtsProvider
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.RpmPacer
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.voice.VoiceTakes
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.jobs.DesktopAiJobRunner
import io.github.alalkipgen.autoflow.ui.jobs.RecapJobProcessor
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.*

/** V-1e on screen: Speak n parts → the confirm dialog (requests, voice) → Start → every part Voiced with play and speak again; the job opens nothing else. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class VoiceSpeakUiTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    private fun ComposeUiTest.text(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

    @Test fun speakConfirmStartVoicesEveryPart() = runDesktopComposeUiTest(width = 412, height = 900) {
        val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
        val index = ProjectIndex(fs, "/app/projects.json")
        val session = ProjectSession(fs, store, index, current)
        val config = ToolsConfig(tts = ToolsConfig.Tts(maxCharsPerPart = 10))
        val dir = "/p/Voice.autoflow"
        val voice = VoiceTakes.plan(VoiceTool(task = VoiceTool.TASK_TEXT, text = "First one.\nSecond.", lock = VoiceLock("gemini", "tts-a", "Puck", "my")),
            10, Lexicon.EMPTY)
        val created = (store.create(dir, "Voice", Mode.VOICE_AUDIO) as CoreResult.Ok).value
        current.set(OpenProject(dir, (store.save(dir, created.copy(voice = voice)) as CoreResult.Ok).value))
        val job = VoiceSpeakJob(session, fs, FakeTtsProvider(), config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = DesktopAiJobRunner(AiJobStore(fs, "/files/ai-jobs"), scope, RecapJobProcessor({ null }, { null }, { null }, { job }), pacer = { RpmPacer(1_000) })
        val model = VoiceAudioModel(session, DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), FakePicker(), current),
            config, MainScope(), models = listOf("tts-a"))
        val speak = VoiceSpeakModel(session, job, runner, MainScope())
        try {
            setContent { VoiceAudioScreen(model, session, onBack = {}, speak = speak) }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("voice-speak").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("voice-redo-p1").assertDoesNotExist()
            // 🆕 VA-6: the sticky caption under Speak n parts.
            assertEquals(My.breakSyllables(UiText.VA_PARTS_CAPTION.en.replace("{done}", "0").replace("{n}", "2")), text("voice-parts-caption"))
            onNodeWithTag("voice-speak").performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("voice-speak-confirm").fetchSemanticsNodes().isNotEmpty() }
            // 🆕 VA-3 / §8.1: the dialog names the voice as the voice card does ("Puck · Gemini"), never the raw model id.
            assertEquals(My.breakSyllables(UiText.VA_SPEAK_CONFIRM.en.replace("{n}", "2").replace("{voice}", "Puck · Gemini").replace("{minutes}", "1")),
                text("voice-speak-facts"))
            onNodeWithText(My.breakSyllables(UiText.VA_SPEAK_TITLE.en.replace("{n}", "2")), useUnmergedTree = true).assertExists()
            onNodeWithTag("voice-speak-back").assertExists()
            onNodeWithTag("voice-speak-start").performScrollTo().performMouseInput { click() }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("voice-redo-p2").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("voice-redo-p1").assertExists()
            assertEquals(My.breakSyllables(UiText.VA_VOICED.en), text("voice-part-p1-status"))
            assertTrue((store.open(dir) as CoreResult.Ok).value.voice!!.parts.all { it.take != null })
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("voice-speak-notice").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(My.breakSyllables(UiText.VA_SPEAK_DONE.en.replace("{n}", "2")), text("voice-speak-notice"))
            onNodeWithTag("voice-speak").assertIsNotEnabled()
            assertEquals(My.breakSyllables(UiText.VA_PARTS_CAPTION.en.replace("{done}", "2").replace("{n}", "2")), text("voice-parts-caption"))
        } finally { scope.cancel() }
    }
}
