package io.github.alalkipgen.autoflow.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.SecretNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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

    @Test
    fun keyIsSavedMaskedTestedAndRemoved() = runComposeUiTest {
        setContent { SettingsScreen(model) }
        onNodeWithText("Gemini API key: not saved").assertExists()
        onNodeWithText("Test").assertIsNotEnabled()
        onNodeWithContentDescription("Paste a Gemini API key").performTextInput("test-key-not-real")
        onNodeWithText("Save").performClick()
        waitForIdle()
        assertEquals("test-key-not-real", secrets.values[SecretNames.GEMINI_API_KEY])
        onNodeWithText("Key saved").assertExists()
        onNodeWithText("Gemini API key: saved ••••real").assertExists()
        onNodeWithText("test-key-not-real", substring = true).assertDoesNotExist()

        onNodeWithText("Test").assertIsEnabled().performClick()
        waitForIdle()
        onNodeWithText("Key works (gemini-x)").assertExists()
        onNodeWithText("Remove").performClick()
        waitForIdle()
        onNodeWithText("Gemini API key: not saved").assertExists()
    }

    @Test
    fun modeDefaultsAndToolsChangeSettings() = runComposeUiTest {
        setContent { SettingsScreen(model) }
        onNodeWithText("Use fake AI providers (offline, no key needed)").performClick()
        onNodeWithText("16:9").performClick()
        onNodeWithText("10s").performClick()
        onNodeWithText("Detect").performScrollTo().performClick()
        waitForIdle()
        val s = settings.state.value
        assertFalse(s.useFakeProviders)
        assertEquals(Ratio.R16_9, s.defaultRatio)
        assertEquals(10_000L, s.defaultClipMs)
        assertEquals("C:/ffmpeg/bin/ffmpeg.exe", s.ffmpegPath)
        onNodeWithText("C:/ffmpeg/bin/ffmpeg.exe").assertExists()

        onNodeWithText("VLC 3.x not found: video playback is off.").performScrollTo()
        onNodeWithText("Install VLC").performClick()
        assertEquals(listOf(SettingsModel.VLC_DOWNLOAD_URL), opened)
    }

    @Test
    fun typedPathAppliesAndModelConfigIsShown() = runComposeUiTest {
        tools.vlcDirs += "D:/VLC"
        setContent { SettingsScreen(model) }
        onNodeWithContentDescription("Auto-detect").performScrollTo().performTextInput("D:/VLC")
        onNodeWithText("Apply").performClick()
        waitForIdle()
        assertEquals("D:/VLC", settings.state.value.vlcPath)
        onNodeWithText("Using VLC in D:/VLC").assertExists()
        onNodeWithText("In use: ${LoadedModelConfig.BUNDLED}").performScrollTo().assertExists()
    }
}
