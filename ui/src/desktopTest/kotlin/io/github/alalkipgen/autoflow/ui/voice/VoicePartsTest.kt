package io.github.alalkipgen.autoflow.ui.voice

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.getOrNull
import androidx.compose.ui.semantics.getOrNull
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.library.Library
import io.github.alalkipgen.autoflow.core.library.LibraryStore
import io.github.alalkipgen.autoflow.core.library.SavedVoice
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.voice.VoiceTakes
import io.github.alalkipgen.autoflow.ui.i18n.My
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

/** V-1d: parts list keeps unchanged takes; voice lock (saved voices first) and tone are in the take key. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class VoicePartsTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    private val lock = VoiceLock("gemini", "tts-a", "Charon", "my")
    private val narrator = VoiceLock("gemini", "tts-b", "Aoede", "my")

    private fun ComposeUiTest.tap(tag: String) = onNodeWithTag(tag).performScrollTo().performMouseInput { click() }
    private fun ComposeUiTest.status(id: String) = onNodeWithTag("voice-part-$id-status").fetchSemanticsNode()
        .config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text }

    @Test fun editingVoiceAndToneOnlyDropTheTakesTheyChange() = runDesktopComposeUiTest(width = 412, height = 900) {
        val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
        val index = ProjectIndex(fs, "/app/projects.json")
        val session = ProjectSession(fs, store, index, current)
        val config = ToolsConfig(tts = ToolsConfig.Tts(maxCharsPerPart = 10))
        val dir = "/p/Voice.autoflow"
        val planned = VoiceTakes.plan(VoiceTool(task = VoiceTool.TASK_TEXT, text = "First one.\nSecond.", lock = lock), 10, Lexicon.EMPTY)
        val voiced = planned.copy(parts = planned.parts.mapIndexed { i, p -> p.copy(take = ArtifactId("au_00000000000$i")) })
        val created = (store.create(dir, "Voice", Mode.VOICE_AUDIO) as CoreResult.Ok).value
        current.set(OpenProject(dir, (store.save(dir, created.copy(voice = voiced)) as CoreResult.Ok).value))
        val library = LibraryStore(fs, "/lib").apply { save(Library(voices = listOf(SavedVoice("v1", "Narrator", narrator)))) }
        val model = VoiceAudioModel(session, DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), FakePicker(), current),
            config, MainScope(), saveDelayMs = 0, library = library, models = listOf("tts-a", "tts-b"))
        fun saved() = assertNotNull(store.open(dir).getOrNull()?.voice)
        setContent { VoiceAudioScreen(model, session, onBack = {}) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("voice-part-p2").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(My.breakSyllables(UiText.VA_VOICED.en), status("p1"))
        assertEquals(My.breakSyllables(UiText.VA_VOICED.en), status("p2"))

        onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("voice-text"))).performTextReplacement("First one.\nSecond.\nThird.")
        waitUntil(timeoutMillis = 5_000) { saved().parts.size == 3 }
        assertEquals(listOf("p1", "p2", "p3"), saved().parts.map { it.id })
        assertEquals(listOf(true, true, false), saved().parts.map { it.take != null })
        waitUntil(timeoutMillis = 5_000) { status("p3") == My.breakSyllables(UiText.VA_NOT_VOICED.en) }
        // VA-4: the take rule behind the parts card's ⓘ.
        val rule = My.breakSyllables(UiText.VA_TAKE_RULE.en)
        assertTrue(onAllNodesWithText(rule).fetchSemanticsNodes().isEmpty(), "the take rule sits behind ⓘ")
        tap("voice-info-parts")
        onNodeWithText(rule).assertExists()
        tap("voice-info-parts")
        assertEquals(My.breakSyllables(UiText.VA_VOICED.en), status("p1"))

        tap("voice-tone-news")
        assertEquals("news", saved().tone)
        assertTrue(saved().parts.all { it.take == null }, "the tone is in every take key")
        tap("voice-tone-news")
        assertNull(saved().tone, "the chosen tone again clears it")

        tap("voice-expand") // VA-2: the voice card is closed while a voice is chosen
        tap("voice-saved-v1")
        assertEquals(narrator, saved().lock)
        tap("voice-voice-Kore")
        assertEquals(narrator.copy(voice = "Kore"), saved().lock)
        tap("voice-model-tts-a")
        assertEquals(narrator.copy(model = "tts-a", voice = "Kore"), saved().lock)
        assertEquals("First one.\nSecond.\nThird.", saved().text)
    }
}
