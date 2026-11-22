package io.github.alalkipgen.autoflow.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.AiRunTiming
import io.github.alalkipgen.autoflow.core.recap.AiTimingStage
import io.github.alalkipgen.autoflow.core.recap.timedAiStage
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.BackupResult
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.SecretNames
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation

@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    private val fs = InMemoryFileSystem()
    private val settings = SettingsStore(fs, "/a/settings.json")
    private val secrets = MemorySecureStorage()
    private val tools = FakeTools(ffmpeg = "C:/ffmpeg/bin/ffmpeg.exe")
    private val opened = mutableListOf<String>()
    private val model = SettingsModel(
        settings, secrets, ProviderStatusModel(settings, secrets), UsageCounter(fs, "/a/usage.json"),
        loadModelConfig(fs, null) { TEST_MODELS_JSON }, tools, FakePicker(), { opened += it; PlatformResult.Ok(Unit) },
        { AiResult.Ok(Unit, Usage(model = "gemini-x", latencyMs = 1)) },
    )

    /** Main page row `settings-row-<row>` → its page (P9-17a). */
    private fun ComposeUiTest.open(row: String) {
        onNodeWithTag("settings-row-$row").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        onNodeWithTag("settings-page-title").assertIsDisplayed()
    }

    /** The page bar's back button → the main page. */
    private fun ComposeUiTest.back() {
        onNodeWithTag("settings-page-back").assertIsDisplayed().performMouseInput { click() }
        onNodeWithTag("settings-heading").assertIsDisplayed()
    }

    @Test
    fun diagnosticsShowWhichCallIsWaitingAndCanBeCancelledFromTheCard() = runComposeUiTest {
        var ttsCalls = 0
        val diag = SettingsModel(settings, secrets, ProviderStatusModel(settings, secrets), UsageCounter(fs, "/a/usage.json"),
            loadModelConfig(fs, null) { TEST_MODELS_JSON }, tools, FakePicker(), { PlatformResult.Ok(Unit) },
            testKeyCall = { timedAiStage(AiTimingStage.RETRY_WAIT, 429, 90_000) { awaitCancellation() } },
            testTtsCall = { ttsCalls++; awaitCancellation() })
        diag.saveKey("fixture-not-a-real-key")
        setContent { SettingsScreen(diag) }
        open("gemini")
        val tag = "gemini-diagnostic-run"
        fun cancel() = onNode(hasText(UiText.CANCEL.en) and hasAnyAncestor(hasTestTag(tag)))
            .performScrollTo().assertIsDisplayed().performMouseInput { click() }
        onNodeWithText(UiText.TEST.en).performScrollTo().performMouseInput { click() }
        onNodeWithText(UiText.JOB_TEST_GEMINI_KEY.en).performScrollTo().assertIsDisplayed()
        onNodeWithText("Gemini limit reached · retrying in", substring = true).performScrollTo().assertIsDisplayed()
        val keyRun = requireNotNull(diag.state.value.geminiTest)
        assertEquals(UiText.JOB_TEST_GEMINI_KEY, keyRun.label)
        cancel()
        onNodeWithTag(tag).assertDoesNotExist()
        assertFalse(keyRun.id in AiRunTiming.active.value)
        onNodeWithText(UiText.TEST_TTS.en).performScrollTo().assertIsDisplayed().assertIsEnabled().performMouseInput { click() }
        onNodeWithTag(tag).assertExists()
        val ttsRun = requireNotNull(diag.state.value.geminiTest)
        assertEquals(UiText.TEST_TTS, ttsRun.label)
        onNodeWithText(UiText.JOB_WAITING_GEMINI.en, substring = true).performScrollTo().assertIsDisplayed()
        runOnIdle { diag.cancelGeminiTest(keyRun.id) }
        assertEquals(ttsRun, diag.state.value.geminiTest)
        cancel()
        onNodeWithTag(tag).assertDoesNotExist()
        assertFalse(ttsRun.id in AiRunTiming.active.value)
        assertEquals(1, ttsCalls)
    }

    @Test
    fun keyIsSavedMaskedTestedAndRemoved() = runComposeUiTest {
        setContent { SettingsScreen(model) }
        onNodeWithTag("gemini-key-chip").assertTextEquals(UiText.KEY_NOT_SAVED_SHORT.en)
        open("gemini")
        // The old "…: not saved" line is now the Not saved chip's description (README §2.2).
        onNodeWithContentDescription("Gemini API key: not saved").assertExists()
        onNodeWithText("Test").assertIsNotEnabled()
        onNodeWithContentDescription("Paste a Gemini API key").performScrollTo().assertIsDisplayed().performTextInput("test-key-not-real")
        onNodeWithText("Save").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        waitForIdle()
        assertEquals("test-key-not-real", secrets.values[SecretNames.GEMINI_API_KEY])
        onNodeWithText("Key saved").assertExists()
        onNodeWithContentDescription("Gemini API key: saved ••••real").assertExists()
        onNodeWithText("••••real").assertIsDisplayed()
        onNodeWithTag("gemini-key-chip").assertTextEquals(UiText.V3_SAVED.en)
        onNodeWithText("test-key-not-real", substring = true).assertDoesNotExist()

        onNodeWithText("Test").assertIsEnabled().performScrollTo().assertIsDisplayed().performMouseInput { click() }
        waitForIdle()
        onNodeWithText("Key works (gemini-x)").assertExists()
        onNodeWithText("Remove").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        waitForIdle()
        onNodeWithContentDescription("Gemini API key: not saved").assertExists()
        back()
        onNodeWithTag("gemini-key-chip").assertTextEquals(UiText.KEY_NOT_SAVED_SHORT.en)
    }

    /** F-4 (D-93): Chatbox AI's license key under the Gemini key; its chips choose who answers text and Transcribe. */
    @Test
    fun chatboxKeyAndServiceChips() = runComposeUiTest {
        val json = """{"text": {"candidates": ["text-a"]}, "chatbox": {"baseUrl": "https://ai.chatboxai.app/v1", "text": ["gemini-2.5-flash"]}}"""
        val m = SettingsModel(settings, secrets, ProviderStatusModel(settings, secrets), UsageCounter(fs, "/a/usage.json"),
            loadModelConfig(fs, null) { json }, tools, FakePicker(), { PlatformResult.Ok(Unit) },
            { AiResult.Ok(Unit, Usage(model = "gemini-x", latencyMs = 1)) },
            testChatboxCall = { AiResult.Ok(Unit, Usage(model = "gemini-2.5-flash", latencyMs = 1)) })
        setContent { SettingsScreen(m) }
        open("chatbox")
        onNodeWithContentDescription(UiText.CHATBOX_KEY_NOT_SAVED.en).performScrollTo().assertIsDisplayed()
        onNodeWithTag("text-service-chatbox").assertIsNotEnabled()
        onNodeWithTag("chatbox-key-test").assertIsNotEnabled()
        onNodeWithContentDescription(UiText.CHATBOX_KEY_PASTE.en).performScrollTo().performTextInput("LIC-abcd-1234")
        onNodeWithTag("chatbox-key-save").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        waitForIdle()
        assertEquals("LIC-abcd-1234", secrets.values[SecretNames.CHATBOX_LICENSE_KEY])
        assertEquals(null, secrets.values[SecretNames.GEMINI_API_KEY])
        onNodeWithContentDescription("Chatbox AI license key: saved ••••1234").assertExists()
        onNodeWithTag("chatbox-key-test").performScrollTo().assertIsEnabled().performMouseInput { click() }
        waitForIdle()
        onNodeWithText("Key works (gemini-2.5-flash)").assertExists()
        onNodeWithTag("text-service-chatbox").performScrollTo().assertIsEnabled().performMouseInput { click() }
        onNodeWithTag("transcribe-service-chatbox").performScrollTo().performMouseInput { click() }
        waitForIdle()
        assertEquals("chatbox", settings.state.value.textService)
        assertEquals("chatbox", settings.state.value.transcribeService)
        onNodeWithTag("transcribe-service-gemini").performScrollTo().performMouseInput { click() }
        waitForIdle()
        assertEquals("gemini", settings.state.value.transcribeService)
        onNodeWithTag("chatbox-key-remove").performScrollTo().performMouseInput { click() }
        waitForIdle()
        onNodeWithContentDescription(UiText.CHATBOX_KEY_NOT_SAVED.en).assertExists()
        assertEquals("gemini", settings.state.value.textService)
    }

    @Test
    fun modeDefaultsAndToolsChangeSettings() = runComposeUiTest {
        setContent { SettingsScreen(model) }
        open("gemini")
        onNodeWithText("Use fake AI providers (offline, no key needed)").performScrollTo().assertIsDisplayed()
            .assertIsOn().performMouseInput { click() }
        back()
        open("defaults")
        onNodeWithText("16:9").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        onNodeWithText("10s").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        back()
        onNodeWithText("16:9 · 10s").assertIsDisplayed()
        open("tools")
        onNodeWithText("Detect").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        waitForIdle()
        val s = settings.state.value
        assertFalse(s.useFakeProviders)
        assertEquals(Ratio.R16_9, s.defaultRatio)
        assertEquals(10_000L, s.defaultClipMs)
        assertEquals("C:/ffmpeg/bin/ffmpeg.exe", s.ffmpegPath)
        onNodeWithText("C:/ffmpeg/bin/ffmpeg.exe").assertExists()

        onNodeWithText("VLC 3.x not found: video playback is off.").performScrollTo()
        onNodeWithText("Install VLC").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        assertEquals(listOf(SettingsModel.VLC_DOWNLOAD_URL), opened)
    }

    @Test
    fun bundledFfmpegHidesThePathsAndVlc() = runComposeUiTest {
        val apk = FakeTools(ffmpeg = "/data/app/lib/libffmpeg.so", bundled = true)
        val bundled = SettingsModel(
            settings, secrets, ProviderStatusModel(settings, secrets), UsageCounter(fs, "/a/usage.json"),
            loadModelConfig(fs, null) { TEST_MODELS_JSON }, apk, FakePicker(), { PlatformResult.Ok(Unit) },
            { AiResult.Ok(Unit, Usage(model = "gemini-x", latencyMs = 1)) },
        )
        setContent { SettingsScreen(bundled) }
        open("tools")
        onNodeWithText("ffmpeg: bundled with the app.").performScrollTo().assertExists()
        onNodeWithContentDescription("Path to ffmpeg.exe").assertDoesNotExist()
        onNodeWithText("Install VLC").assertDoesNotExist()
        onNodeWithText("VLC folder (preview playback)").assertDoesNotExist()
    }

    @Test
    fun typedPathAppliesAndModelConfigIsShown() = runComposeUiTest {
        tools.vlcDirs += "D:/VLC"
        setContent { SettingsScreen(model) }
        open("tools")
        onNodeWithContentDescription("Auto-detect").performScrollTo().performTextInput("D:/VLC")
        onNodeWithText("Apply").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        waitForIdle()
        assertEquals("D:/VLC", settings.state.value.vlcPath)
        onNodeWithText("Using VLC in D:/VLC").assertExists()
        back()
        open("models")
        onNodeWithText("In use: ${LoadedModelConfig.BUNDLED}").performScrollTo().assertExists()
    }

    @Test
    fun aboutShowsTheVersionAndLongPressOpensTheSelfTest() = runComposeUiTest {
        var opens = 0
        setContent { SettingsScreen(model, info = AppInfo("0.1.0 (abc1234)") { opens++ }) }
        onNodeWithText("Auto Flow 0.1.0 (abc1234)").performScrollTo().assertExists()
        onNodeWithText("Long-press the version to open the Self-test.").assertExists()
        onNodeWithTag("about-version").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        waitForIdle()
        assertEquals(0, opens)
        onNodeWithTag("about-version").performTouchInput { longClick() }
        waitForIdle()
        assertEquals(1, opens)
    }

    @Test
    fun withoutAppInfoThereIsNoAboutCard() = runComposeUiTest {
        setContent { SettingsScreen(model) }
        onNodeWithText("About").assertDoesNotExist()
        onNodeWithText("Backup").assertDoesNotExist()
    }

    @Test
    fun backupRunsShowsProgressAndShares() = runComposeUiTest {
        val index = ProjectIndex(fs, "/a/projects.json")
        val store = ProjectStore(fs)
        index.record("/ext/Rain.autoflow", store.create("/ext/Rain.autoflow", "Rain", Mode.MUSIC_VIDEO).getOrNull()!!)
        val fake = FakeBackup()
        val backup = BackupModel(fake, index, fs, CoroutineScope(Dispatchers.Unconfined))
        setContent { SettingsScreen(model, backup = backup) }
        onNodeWithTag("settings-row-backup").assert(hasText("1 project"))
        open("backup")
        onNodeWithText("1 project").performScrollTo().assertExists()
        onNodeWithText("Share…").assertDoesNotExist()
        onNodeWithText("Back up all projects").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        waitForIdle()
        onNodeWithTag("backup-progress").assertExists()
        onNodeWithText("Backing up… 25 %").assertExists()
        fake.gate.complete(PlatformResult.Ok(BackupResult("Download/AutoFlow/AutoFlow-backup-1.zip", 2_000_000, 1, 4, "content://x")))
        waitForIdle()
        onNodeWithText("Saved Download/AutoFlow/AutoFlow-backup-1.zip (2.0 MB, 1 project)").performScrollTo().assertExists()
        onNodeWithText("Share…").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        waitForIdle()
        assertEquals(1, fake.shared.size)
    }
    @Test
    fun previewFeaturesDefaultsPersistAcrossStoreAndScreenReopening() = runComposeUiTest {
        var reopened by androidx.compose.runtime.mutableStateOf(false)
        fun fresh() = SettingsModel(
            SettingsStore(fs, "/a/settings.json"), secrets, ProviderStatusModel(settings, secrets), UsageCounter(fs, "/a/usage.json"),
            loadModelConfig(fs, null) { TEST_MODELS_JSON }, tools, FakePicker(), { PlatformResult.Ok(Unit) }, { AiResult.Ok(Unit) },
            bundledFeatures = """{"schemaVersion":1,"studioV2":false,"quickRecap":false}""",
        )
        val first = fresh()
        var second: SettingsModel? = null
        setContent { PreviewFeaturesCard(if (reopened) second!! else first) }
        assertFalse(first.state.value.features.studioV2)
        onNodeWithText("studioV2").assertIsDisplayed().performMouseInput { click() }
        assertEquals(true, first.state.value.features.studioV2)
        second = fresh()
        reopened = true
        waitForIdle()
        assertEquals(true, second!!.state.value.features.studioV2)
        onNodeWithText("studioV2").assertIsDisplayed().performMouseInput { click() }
        assertFalse(second!!.state.value.features.studioV2)
        assertEquals(emptyMap(), SettingsStore(fs, "/a/settings.json").state.value.featureOverrides)
        assertEquals(false, second!!.state.value.features.labs.wholeMovieTranscribe)
    }

}
