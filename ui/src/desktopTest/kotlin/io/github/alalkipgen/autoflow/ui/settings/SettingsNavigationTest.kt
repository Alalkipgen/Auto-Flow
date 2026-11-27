package io.github.alalkipgen.autoflow.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.SecretNames
import io.github.alalkipgen.autoflow.platform.SecureStorage
import io.github.alalkipgen.autoflow.ui.TestBackDispatcher
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import androidx.compose.ui.semantics.SemanticsProperties
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P9-17a (Settings sample README §2, MAIN-PAGE.md §5): the main page's four groups, row → page → back (at the same
 * scroll position, also by system Back), the Chatbox AI row landing on its card, the key chip's four states, the
 * Language radio list and the Models page's closed "More options".
 */
@OptIn(ExperimentalTestApi::class)
class SettingsNavigationTest {
    private val fs = InMemoryFileSystem()
    private val settings = SettingsStore(fs, "/a/settings.json")
    private val chatboxJson = """{"text": {"candidates": ["text-a"]}, "chatbox": {"baseUrl": "https://ai.chatboxai.app/v1", "text": ["gemini-2.5-flash"]}}"""

    private fun model(secrets: SecureStorage = MemorySecureStorage(), json: String = TEST_MODELS_JSON) = SettingsModel(
        settings, secrets, ProviderStatusModel(settings, secrets), UsageCounter(fs, "/a/usage.json"),
        loadModelConfig(fs, null) { json }, FakeTools(ffmpeg = "C:/ffmpeg/bin/ffmpeg.exe"), FakePicker(), { PlatformResult.Ok(Unit) },
        { AiResult.Ok(Unit, Usage(model = "gemini-x", latencyMs = 1)) },
        bundledFeatures = """{"schemaVersion":1,"studioV2":true,"quickRecap":false}""",
        testChatboxCall = { AiResult.Ok(Unit, Usage(model = "gemini-2.5-flash", latencyMs = 1)) },
    )

    private fun ComposeUiTest.top(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y
    private fun ComposeUiTest.bottom(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.bottom
    private fun ComposeUiTest.title(text: String) =
        onNode(hasText(text) and hasAnyAncestor(hasTestTag("settings-page-title"))).assertIsDisplayed()
    private fun ComposeUiTest.open(row: String) {
        onNodeWithTag("settings-row-$row").performScrollTo().assertIsDisplayed().performMouseInput { click() }
        waitForIdle()
    }
    private fun ComposeUiTest.back() {
        onNodeWithTag("settings-page-back").assertIsDisplayed().performMouseInput { click() }
        onNodeWithTag("settings-heading").assertIsDisplayed()
    }

    @Test
    fun mainPageShowsFourGroupsInOrderThenTheAboutFooter() = runDesktopComposeUiTest(width = 412, height = 900) {
        val m = model(json = chatboxJson)
        setContent { SettingsScreen(m, info = AppInfo("0.1.0"), onOpenLexicon = {}, backup = null) }
        val labels = listOf(UiText.SETTINGS_GENERAL, UiText.V3_AI_KEYS, UiText.SETTINGS_EXPORT, UiText.SETTINGS_STORAGE)
            .map { onNodeWithText(it.en).fetchSemanticsNode().positionInRoot.y }
        assertEquals(labels.sorted(), labels)
        val rows = listOf("language", "gemini", "chatbox", "models").map { "settings-row-$it" } + "settings-lexicon" +
            listOf("defaults", "fonts", "tools", "labs", "diagnostics").map { "settings-row-$it" } + "settings-about"
        val tops = rows.map { top(it) }
        assertEquals(tops.sorted(), tops, rows.toString())
        assertTrue(tops[1] > labels[1] && tops[4] < labels[2] && tops[5] > labels[2] && tops[8] > labels[3])
        onNodeWithTag("settings-row-language").assert(hasText("English"))
        val flags = m.state.value.features.asMap()
        assertTrue(flags.getValue("studioV2"))
        onNodeWithTag("settings-row-labs").assert(hasText("${flags.values.count { it }} of ${flags.size} on"))
        onNodeWithTag("settings-row-backup").assertDoesNotExist()
        onNodeWithTag("subscreen-back").assertDoesNotExist()
    }

    @Test
    fun aRowOpensItsPageAndBackReturnsToTheSameScrollPosition() = runDesktopComposeUiTest(width = 360, height = 560) {
        var leaves = 0
        setContent { SettingsScreen(model(), info = AppInfo("0.1.0"), onBack = { leaves++ }) }
        onNodeWithTag("settings-row-diagnostics").performScrollTo().assertIsDisplayed()
        val before = top("settings-row-diagnostics")
        assertTrue(top("settings-row-language") < 0f, "the main page is scrolled")
        open("diagnostics")
        title(UiText.V3_DIAGNOSTICS.en)
        onNodeWithTag("settings-diagnostics").assertIsDisplayed()
        onNodeWithTag("subscreen-back").assertDoesNotExist()
        back()
        assertEquals(before, top("settings-row-diagnostics"), 1f)
        onNodeWithTag("settings-row-diagnostics").assertIsDisplayed()
        onNodeWithTag("subscreen-back").performMouseInput { click() }
        assertEquals(1, leaves)
    }

    @Test
    fun theChatboxRowOpensTheAiPageWithTheChatboxCardOnTop() = runDesktopComposeUiTest(width = 360, height = 560) {
        setContent { SettingsScreen(model(json = chatboxJson)) }
        open("gemini")
        val pad = top("settings-ai") - bottom("settings-page-title")
        assertTrue(top("settings-chatbox") - bottom("settings-page-title") > pad + 100f, "the Chatbox card is below the Gemini card")
        back()
        open("chatbox")
        title(UiText.V3_AI_KEYS.en)
        onNodeWithTag("settings-chatbox").assertIsDisplayed()
        assertEquals(pad, top("settings-chatbox") - bottom("settings-page-title"), 2f)
        // Back and in again through the Gemini row: the AI page starts at its top.
        back()
        open("gemini")
        assertEquals(pad, top("settings-ai") - bottom("settings-page-title"), 1f)
    }

    @Test
    fun theKeyChipFollowsTheKeyStateOnTheRowAndTheCard() = runDesktopComposeUiTest(width = 412, height = 1400) {
        val saved = MemorySecureStorage().apply { values[SecretNames.GEMINI_API_KEY] = "abcd-1234"; values[SecretNames.CHATBOX_LICENSE_KEY] = "LIC-9876" }
        val unreadable = object : SecureStorage {
            override fun get(name: String): PlatformResult<String?> = PlatformResult.Failed("cannot decrypt")
            override fun put(name: String, value: String): PlatformResult<Unit> = PlatformResult.Failed("cannot encrypt")
            override fun remove(name: String): PlatformResult<Unit> = PlatformResult.Ok(Unit)
        }
        var current by mutableStateOf(model(MemorySecureStorage(), chatboxJson))
        setContent { SettingsScreen(current) }
        fun chips(gemini: UiText?, chatbox: UiText?) {
            listOf("gemini-key-chip" to gemini, "chatbox-key-chip" to chatbox).forEach { (tag, text) ->
                if (text == null) onNodeWithTag(tag).assertDoesNotExist() else onNodeWithTag(tag).assertTextEquals(text.en)
            }
        }
        // MISSING: Not saved (neutral), the old "…: not saved" line as its description.
        chips(UiText.KEY_NOT_SAVED_SHORT, UiText.KEY_NOT_SAVED_SHORT)
        onNodeWithTag("gemini-key-chip").assert(hasContentDescription(UiText.KEY_NOT_SAVED.en))
        onNodeWithTag("chatbox-key-chip").assert(hasContentDescription(UiText.CHATBOX_KEY_NOT_SAVED.en))
        open("gemini")
        chips(UiText.KEY_NOT_SAVED_SHORT, UiText.KEY_NOT_SAVED_SHORT)
        back()
        // SET: Saved, the masked key in a pill on the card.
        current = model(saved, chatboxJson)
        waitForIdle()
        chips(UiText.V3_SAVED, UiText.V3_SAVED)
        open("gemini")
        chips(UiText.V3_SAVED, UiText.V3_SAVED)
        onNodeWithText("••••1234").assertExists()
        onNodeWithText("••••9876").assertExists()
        back()
        // UNREADABLE: Save again (error) and the problem line on the card.
        current = model(unreadable, chatboxJson)
        waitForIdle()
        chips(UiText.KEY_SAVE_AGAIN, UiText.KEY_SAVE_AGAIN)
        open("gemini")
        chips(UiText.KEY_SAVE_AGAIN, UiText.KEY_SAVE_AGAIN)
        onNodeWithText(UiText.KEY_UNREADABLE.en).assertExists()
        onNodeWithText(UiText.CHATBOX_KEY_PROBLEM.en).assertExists()
        back()
        // UNSUPPORTED (desktop off Windows): no chip; the row says why.
        current = model(MemorySecureStorage(unsupported = true), chatboxJson)
        waitForIdle()
        chips(null, null)
        onNodeWithTag("settings-row-gemini").assert(hasText(UiText.KEY_UNSUPPORTED.en))
        open("gemini")
        chips(null, null)
        onNodeWithText(UiText.KEY_UNSUPPORTED.en).assertExists()
    }

    @Test
    fun systemBackReturnsToTheMainPageBeforeItLeaves() = runDesktopComposeUiTest(width = 360, height = 640) {
        val back = TestBackDispatcher()
        setContent { SettingsScreen(model(), backHandler = back.handler) }
        assertFalse(back.enabled)
        open("tools")
        onNodeWithTag("settings-tools").assertIsDisplayed()
        assertTrue(back.enabled)
        runOnIdle { back.press() }
        onNodeWithTag("settings-heading").assertIsDisplayed()
        onNodeWithTag("settings-tools").assertDoesNotExist()
        assertFalse(back.enabled)
    }

    @Test
    fun theLanguagePageSwitchesTheAppLanguage() = runDesktopComposeUiTest(width = 360, height = 640) {
        setContent { SettingsScreen(model()) }
        open("language")
        onNodeWithTag("language-en").assertIsSelected()
        onNodeWithTag("language-my").assertIsNotSelected().performMouseInput { click() }
        waitForIdle()
        assertEquals("my", settings.state.value.uiLanguage)
        onNodeWithTag("language-my").assertIsSelected()
        onNodeWithTag("language-en").assertIsNotSelected()
        title(My.breakSyllables(UiText.LANGUAGE.text(UiLanguage.BURMESE)))
        onNodeWithTag("settings-page-back").performMouseInput { click() }
        onNodeWithTag("settings-row-language").assert(hasText(My.breakSyllables(UiLanguage.BURMESE.nativeName)))
    }

    @Test
    fun theModelsPageKeepsTheOverrideUnderAClosedMoreOptions() = runDesktopComposeUiTest(width = 360, height = 640) {
        setContent { SettingsScreen(model()) }
        onNodeWithTag("settings-row-models").assert(hasText(UiText.BUNDLED_CONFIG.en))
        open("models")
        onNodeWithText("In use: ${LoadedModelConfig.BUNDLED}").assertIsDisplayed()
        onNodeWithText(UiText.MODELS_OVERRIDE.en).assertDoesNotExist()
        onNodeWithContentDescription(UiText.V3_MORE_OPTIONS.en).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "collapsed")).performMouseInput { click() }
        onNodeWithText(UiText.MODELS_OVERRIDE.en).performScrollTo().assertIsDisplayed()
        onNodeWithText(UiText.BROWSE.en).performScrollTo().assertIsDisplayed()
        onNodeWithText(UiText.APPLY.en).assertDoesNotExist()
        onNodeWithContentDescription(UiText.V3_MORE_OPTIONS.en).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "expanded"))
    }
}
