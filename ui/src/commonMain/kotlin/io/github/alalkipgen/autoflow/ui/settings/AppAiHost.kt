package io.github.alalkipgen.autoflow.ui.settings

import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ai.registry.AiHost
import io.github.alalkipgen.autoflow.platform.Clipboard
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.SecretNames
import io.github.alalkipgen.autoflow.platform.SecureStorage
import io.github.alalkipgen.autoflow.platform.UrlOpener
import io.github.alalkipgen.autoflow.platform.getOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/** [AiHost] from the app's settings and :platform services. The key is read from SecureStorage on every call. */
class AppAiHost(
    private val settings: SettingsStore,
    private val secrets: SecureStorage,
    private val clipboard: Clipboard,
    private val urls: UrlOpener,
    /** Edge voices (D-86): mp3 → WAV with ffmpeg; null = cannot decode here. */
    private val mp3Decoder: (suspend (ByteArray) -> ByteArray?)? = null,
) : AiHost {
    override fun useFakeProviders(): Boolean = settings.state.value.useFakeProviders

    override suspend fun apiKey(): String? = secrets.get(SecretNames.GEMINI_API_KEY).getOrNull()?.takeIf { it.isNotBlank() }

    override fun copyToClipboard(text: String) {
        clipboard.copyText(text)
    }

    override fun openUrl(url: String) {
        urls.open(url)
    }

    override suspend fun mp3ToWav(mp3: ByteArray): ByteArray? = mp3Decoder?.invoke(mp3)

    override fun textService(): String = settings.state.value.textService

    override fun transcribeService(): String = settings.state.value.transcribeService

    override suspend fun chatboxKey(): String? = secrets.get(SecretNames.CHATBOX_LICENSE_KEY).getOrNull()?.takeIf { it.isNotBlank() }
}

enum class KeyState { SET, MISSING, UNREADABLE, UNSUPPORTED }

fun SecureStorage.geminiKeyState(): KeyState = get(SecretNames.GEMINI_API_KEY).toKeyState()

fun SecureStorage.chatboxKeyState(): KeyState = get(SecretNames.CHATBOX_LICENSE_KEY).toKeyState()

internal fun PlatformResult<String?>.toKeyState(): KeyState = when (this) {
    is PlatformResult.Ok -> if (value.isNullOrBlank()) KeyState.MISSING else KeyState.SET
    is PlatformResult.Failed -> KeyState.UNREADABLE
    is PlatformResult.Unsupported -> KeyState.UNSUPPORTED
}

/**
 * Title-bar right side (DESIGN.md §2): AI provider state, then Flow, which is always manual (D-04). [chatbox] is the
 * Chatbox AI key's state when Settings sends text calls there (F-4), else null.
 */
fun providerStatus(useFakes: Boolean, key: KeyState, language: UiLanguage = UiLanguage.ENGLISH, chatbox: KeyState? = null): String {
    val ai = when {
        useFakes -> UiText.AI_FAKE.text(language)
        chatbox == KeyState.SET -> UiText.AI_CHATBOX.text(language)
        chatbox != null -> UiText.AI_CHATBOX_NO_KEY.text(language)
        key == KeyState.SET -> UiText.AI_FREE.text(language)
        key == KeyState.UNSUPPORTED -> UiText.AI_WINDOWS.text(language)
        else -> UiText.AI_NO_KEY.text(language)
    }
    return "$ai · ${UiText.FLOW_MANUAL.text(language)}"
}

/** Live title-bar status. Call [refreshKey] after the key is saved or removed. */
class ProviderStatusModel(private val settings: SettingsStore, private val secrets: SecureStorage) {
    private val key = MutableStateFlow(secrets.geminiKeyState())
    private val chatbox = MutableStateFlow(secrets.chatboxKeyState())

    val status: Flow<String> = combine(settings.state, key, chatbox) { s, k, c -> text(s, k, c) }

    fun current(): String = text(settings.state.value, key.value, chatbox.value)

    private fun text(s: AppSettings, k: KeyState, c: KeyState) =
        providerStatus(s.useFakeProviders, k, UiLanguage.fromCode(s.uiLanguage), c.takeIf { s.textService == io.github.alalkipgen.autoflow.ai.registry.AiService.CHATBOX })

    fun refreshKey() {
        key.value = secrets.geminiKeyState()
        chatbox.value = secrets.chatboxKeyState()
    }
}
