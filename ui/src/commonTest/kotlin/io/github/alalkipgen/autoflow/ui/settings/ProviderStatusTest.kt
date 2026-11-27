package io.github.alalkipgen.autoflow.ui.settings

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.SecretNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class ProviderStatusTest {
    private val fs = InMemoryFileSystem()

    @Test
    fun statusFollowsModeAndKey() = runTest {
        val settings = SettingsStore(fs, "/a/settings.json")
        val secrets = MemorySecureStorage()
        val model = ProviderStatusModel(settings, secrets)
        assertEquals("AI ● Fake (offline) · Flow ● Manual", model.status.first())

        settings.update { it.copy(useFakeProviders = false) }
        assertEquals("Gemini API ● No key · Flow ● Manual", model.status.first())

        secrets.put(SecretNames.GEMINI_API_KEY, "test-key-not-real")
        model.refreshKey()
        assertEquals("Gemini API ● Free tier · Flow ● Manual", model.status.first())
        assertEquals(model.current(), model.status.first())
    }

    @Test
    fun hostSendsTextAndTranscribeWhereSettingsSay() = runTest {
        val settings = SettingsStore(fs, "/c/settings.json")
        val secrets = MemorySecureStorage()
        val host = AppAiHost(settings, secrets, { PlatformResult.Ok(Unit) }, { PlatformResult.Ok(Unit) })
        assertEquals("gemini", host.textService())
        assertEquals("gemini", host.transcribeService())
        assertNull(host.chatboxKey())
        secrets.put(SecretNames.CHATBOX_LICENSE_KEY, "LIC")
        settings.update { it.copy(textService = "chatbox", useFakeProviders = false) }
        assertEquals("chatbox", host.textService())
        assertEquals("gemini", host.transcribeService())
        assertEquals("LIC", host.chatboxKey())
        val status = ProviderStatusModel(settings, secrets)
        assertEquals("Chatbox AI ● License key · Flow ● Manual", status.current())
        secrets.remove(SecretNames.CHATBOX_LICENSE_KEY)
        status.refreshKey()
        assertEquals("Chatbox AI ● No key · Flow ● Manual", status.status.first())
    }

    @Test
    fun nonWindowsRealModeSaysWindowsOnly() {
        assertEquals("Gemini API ● Windows only · Flow ● Manual", providerStatus(false, MemorySecureStorage(unsupported = true).geminiKeyState()))
    }

    @Test
    fun hostReadsTheKeyFromSecureStorageAndModeFromSettings() = runTest {
        val settings = SettingsStore(fs, "/a/settings.json")
        val secrets = MemorySecureStorage()
        val copied = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val host = AppAiHost(settings, secrets, { copied += it; PlatformResult.Ok(Unit) }, { opened += it; PlatformResult.Ok(Unit) })
        assertTrue(host.useFakeProviders())
        assertNull(host.apiKey())
        secrets.put(SecretNames.GEMINI_API_KEY, "test-key-not-real")
        assertEquals("test-key-not-real", host.apiKey())
        settings.update { it.copy(useFakeProviders = false) }
        assertEquals(false, host.useFakeProviders())
        host.copyToClipboard("prompt")
        host.openUrl("https://aistudio.google.com/")
        assertEquals(listOf("prompt"), copied)
        assertEquals(listOf("https://aistudio.google.com/"), opened)
    }
}
