package io.github.alalkipgen.autoflow.ui.i18n

import androidx.compose.ui.test.*
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.settings.*
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Dest
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Shell
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class UiLanguageVisibleTest {
    @Test fun realLanguageClicksUpdateSettingsAndNavigationInBothDirections() = runDesktopComposeUiTest(width = 412, height = 760) {
        val fs = InMemoryFileSystem()
        val store = SettingsStore(fs, "/app/settings.json")
        val secrets = MemorySecureStorage()
        val model = SettingsModel(store, secrets, ProviderStatusModel(store, secrets), UsageCounter(fs, "/app/usage.json"),
            loadModelConfig(fs, null) { TEST_MODELS_JSON }, FakeTools(), FakePicker(), { PlatformResult.Ok(Unit) }, { AiResult.Ok(Unit) })
        setContent {
            AppLanguage(store.state) {
                StudioV2Shell(StudioV2Dest.HOME, {}, 0, 412f) { SettingsScreen(model) }
            }
        }
        onNodeWithText("Language").assertIsDisplayed()
        onNodeWithContentDescription("Home").assertIsDisplayed()
        // P9-17a: the main page's Language row opens the choices; the page title follows the language.
        onNodeWithTag("settings-row-language").performScrollTo().assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithTag("language-my").performScrollTo().assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithText(My.breakSyllables("ဘာသာစကား")).assertIsDisplayed()
        onNodeWithContentDescription("ပင်မ").assertIsDisplayed()
        onNodeWithText("Language").assertDoesNotExist()
        assertEquals("my", store.state.value.uiLanguage)
        assertEquals("my", SettingsStore(fs, "/app/settings.json").state.value.uiLanguage)
        onNodeWithTag("language-en").performScrollTo().assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithText("Language").assertIsDisplayed()
        onNodeWithContentDescription("Home").assertIsDisplayed()
        onNodeWithText(My.breakSyllables("ဘာသာစကား")).assertDoesNotExist()
        assertEquals("en", SettingsStore(fs, "/app/settings.json").state.value.uiLanguage)
    }
}
