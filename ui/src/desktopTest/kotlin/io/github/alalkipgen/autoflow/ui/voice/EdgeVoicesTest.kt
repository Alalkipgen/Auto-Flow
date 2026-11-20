package io.github.alalkipgen.autoflow.ui.voice

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.ai.config.EdgeTtsConfig
import io.github.alalkipgen.autoflow.ai.config.EdgeVoice
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.flags.FeatureFlags
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.media.MediaTools
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.*

/** V-1b (D-86): Edge voices sit next to the Gemini ones in the voice lock; Gemini voices never keep the Edge model. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class EdgeVoicesTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    private val edge = EdgeTtsConfig(model = "edge-tts", endpoint = "wss://e", trustedClientToken = "T",
        voices = listOf(EdgeVoice("my-MM-ThihaNeural", "Thiha", "male", "my"), EdgeVoice("my-MM-NilarNeural", "Nilar", "female", "my")))
    private val thiha = VoiceLock(EdgeVoices.PROVIDER, "edge-tts", "my-MM-ThihaNeural", "my")

    @Test fun offeredOnlyWhenSetUpAndFlagged() {
        val on = FeatureFlags(labs = io.github.alalkipgen.autoflow.core.flags.Labs(edgeVoices = true))
        assertEquals(edge, EdgeVoices.offered(edge) { on })
        assertNull(EdgeVoices.offered(edge) { FeatureFlags() }, "flag labs.edgeVoices off")
        assertNull(EdgeVoices.offered(EdgeTtsConfig()) { on }, "tts.edge missing in config/models.json")
        assertNull(EdgeVoices.offered(null) { on })
        assertEquals("Edge TTS", EdgeVoices.ttsName(thiha))
        assertEquals("Gemini TTS", EdgeVoices.ttsName(VoiceLock("gemini", "tts-a", "Kore", "my")))
        assertEquals("tts-a", EdgeVoices.geminiModel(thiha, listOf("tts-a", "tts-b")), "a Gemini voice after an Edge voice takes the first Gemini model")
        assertEquals("tts-b", EdgeVoices.geminiModel(VoiceLock("gemini", "tts-b", "Kore", "my"), listOf("tts-a", "tts-b")))
    }

    /** F-3: an Edge job keeps Edge's own pace (`tts.edge.rpm`), a Gemini job Gemini's; the wait shown is whole minutes. */
    @Test fun edgeJobsKeepTheirOwnPace() {
        assertEquals(20, EdgeTtsConfig().rpm)
        assertEquals(20, EdgeVoices.rpm(thiha, 20))
        assertEquals(1, EdgeVoices.rpm(thiha, 0), "never zero")
        assertEquals(VoiceSpeakJob.RPM, EdgeVoices.rpm(VoiceLock("gemini", "tts-a", "Kore", "my"), 20))
        assertEquals(VoiceSpeakJob.RPM, EdgeVoices.rpm(null, 20))
        assertEquals(1, EdgeVoices.minutes(1, 20))
        assertEquals(1, EdgeVoices.minutes(20, 20))
        assertEquals(3, EdgeVoices.minutes(41, 20))
        assertEquals(14, EdgeVoices.minutes(41, 3))
    }

    @Test fun pickingEdgeThenGeminiVoices() = runDesktopComposeUiTest(width = 412, height = 900) {
        val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
        val index = ProjectIndex(fs, "/app/projects.json")
        val session = ProjectSession(fs, store, index, current)
        val dir = "/p/Voice.autoflow"
        val created = (store.create(dir, "Voice", Mode.VOICE_AUDIO) as CoreResult.Ok).value
        val voice = VoiceTool(task = VoiceTool.TASK_TEXT, text = "One.", lock = VoiceLock("gemini", "tts-a", "Charon", "my"))
        current.set(OpenProject(dir, (store.save(dir, created.copy(voice = voice)) as CoreResult.Ok).value))
        val model = VoiceAudioModel(session, DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), FakePicker(), current),
            ToolsConfig(), MainScope(), saveDelayMs = 0, models = listOf("tts-a", "tts-b"), edgeVoices = { edge })
        fun saved() = assertNotNull(store.open(dir).getOrNull()?.voice).lock
        fun tap(tag: String) = onNodeWithTag(tag).performScrollTo().performMouseInput { click() }
        setContent { VoiceAudioScreen(model, session, onBack = {}) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("voice-edge-Thiha").fetchSemanticsNodes().isNotEmpty() }
        tap("voice-edge-Thiha")
        assertEquals(thiha, saved())
        tap("voice-edge-Nilar")
        assertEquals(thiha.copy(voice = "my-MM-NilarNeural"), saved())
        tap("voice-voice-Kore")
        assertEquals(VoiceLock("gemini", "tts-a", "Kore", "my"), saved())
    }

    @Test fun mp3IsDecodedWithFfmpegThroughTwoFixedFiles() = runTest {
        val fs = InMemoryFileSystem()
        val seen = mutableListOf<List<String>>()
        var exit = 0
        val runner = ToolRunner { command, _, _, _, _ -> seen += command; fs.writeDurably(command.last(), byteArrayOf(7)); ToolExec.Done(exit, "", listOf("bad"), 1, timedOut = false) }
        val locator = object : ToolLocator { override fun findFfmpeg() = "ffmpeg"; override fun findVlc(): String? = null; override fun isVlcDir(dir: String) = false }
        val media = MediaTools(SettingsStore(fs, "/app/s.json"), locator, runner)
        val decoder = EdgeMp3Decoder(fs, "/app/edge-tts") { media }
        assertContentEquals(byteArrayOf(7), decoder.wav(byteArrayOf(1, 2)))
        assertContentEquals(byteArrayOf(7), decoder.wav(byteArrayOf(3)))
        assertContentEquals(byteArrayOf(3), fs.read("/app/edge-tts/edge.mp3"))
        assertEquals(setOf("/app/edge-tts/edge.wav"), seen.map { it.last() }.toSet(), "the same two files every time")
        assertTrue(seen.first().containsAll(listOf("-ar", "24000", "pcm_s16le")))
        exit = 1
        assertNull(decoder.wav(byteArrayOf(1)), "ffmpeg failed")
        assertNull(EdgeMp3Decoder(fs, "/app/edge-tts") { null }.wav(byteArrayOf(1)), "no ffmpeg")
    }
}
