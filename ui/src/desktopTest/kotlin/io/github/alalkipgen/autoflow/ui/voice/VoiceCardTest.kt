package io.github.alalkipgen.autoflow.ui.voice

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.*

/** VA-2: the voice card is closed while a voice is chosen and models exist; open when there is no voice or no Burmese model. VA-4: ⓘ opens the long text. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class VoiceCardTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    private fun ComposeUiTest.open(lock: VoiceLock?, models: List<String>) {
        val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
        val index = ProjectIndex(fs, "/app/projects.json")
        val session = ProjectSession(fs, store, index, current)
        val dir = "/p/Voice.autoflow"
        val created = (store.create(dir, "Voice", Mode.VOICE_AUDIO) as CoreResult.Ok).value
        val voice = VoiceTool(task = VoiceTool.TASK_TEXT, text = "One.", lock = lock)
        current.set(OpenProject(dir, (store.save(dir, created.copy(voice = voice)) as CoreResult.Ok).value))
        val model = VoiceAudioModel(session, DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), FakePicker(), current),
            ToolsConfig(), MainScope(), saveDelayMs = 0, models = models)
        setContent { VoiceAudioScreen(model, session, onBack = {}) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("voice-summary").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.state(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
    private fun ComposeUiTest.shown(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test fun closedWithAVoiceAndModels() = runDesktopComposeUiTest(width = 412, height = 900) {
        open(VoiceLock("gemini", "tts-a", "Kore", "my"), listOf("tts-a"))
        assertFalse(shown("voice-model-tts-a"))
        assertEquals(UiText.V3_COLLAPSED.en, state("voice-expand"))
        onNodeWithTag("voice-expand").performMouseInput { click() }
        waitUntil(timeoutMillis = 5_000) { shown("voice-model-tts-a") }
        assertEquals(UiText.V3_EXPANDED.en, state("voice-expand"))
        // VA-4: the long models text sits behind ⓘ.
        assertEquals(UiText.V3_COLLAPSED.en, state("voice-info-models"))
        onNodeWithTag("voice-info-models").performScrollTo().performMouseInput { click() }
        assertEquals(UiText.V3_EXPANDED.en, state("voice-info-models"))
    }

    @Test fun openWithoutAVoice() = runDesktopComposeUiTest(width = 412, height = 900) {
        open(null, listOf("tts-a"))
        assertTrue(shown("voice-model-tts-a"))
        assertEquals(UiText.V3_EXPANDED.en, state("voice-expand"))
    }

    @Test fun openWithoutModels() = runDesktopComposeUiTest(width = 412, height = 900) {
        open(VoiceLock("gemini", "tts-a", "Kore", "my"), emptyList())
        assertTrue(shown("voice-voice-Kore"))
        assertFalse(shown("voice-model-tts-a"))
    }
}
