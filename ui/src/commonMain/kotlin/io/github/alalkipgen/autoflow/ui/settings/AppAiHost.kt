package io.github.alalkipgen.autoflow.ui.settings

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
) : AiHost {
    override fun useFakeProviders(): Boolean = settings.state.value.useFakeProviders

    override suspend fun apiKey(): String? = secrets.get(SecretNames.GEMINI_API_KEY).getOrNull()?.takeIf { it.isNotBlank() }

    override fun copyToClipboard(text: String) {
        clipboard.copyText(text)
    }

    override fun openUrl(url: String) {
        urls.open(url)
    }
}

enum class KeyState { SET, MISSING, UNREADABLE, UNSUPPORTED }

fun SecureStorage.geminiKeyState(): KeyState = get(SecretNames.GEMINI_API_KEY).toKeyState()

internal fun PlatformResult<String?>.toKeyState(): KeyState = when (this) {
    is PlatformResult.Ok -> if (value.isNullOrBlank()) KeyState.MISSING else KeyState.SET
    is PlatformResult.Failed -> KeyState.UNREADABLE
    is PlatformResult.Unsupported -> KeyState.UNSUPPORTED
}

/** Title-bar right side (DESIGN.md §2): AI provider state, then Flow, which is always manual (D-04). */
fun providerStatus(useFakes: Boolean, key: KeyState): String {
    val ai = when {
        useFakes -> "AI ● Fake (offline)"
        key == KeyState.SET -> "Gemini API ● Free tier"
        key == KeyState.UNSUPPORTED -> "Gemini API ● Windows only"
        else -> "Gemini API ● No key"
    }
    return "$ai · Flow ● Manual"
}

/** Live title-bar status. Call [refreshKey] after the key is saved or removed. */
class ProviderStatusModel(private val settings: SettingsStore, private val secrets: SecureStorage) {
    private val key = MutableStateFlow(secrets.geminiKeyState())

    val status: Flow<String> = combine(settings.state, key) { s, k -> providerStatus(s.useFakeProviders, k) }

    fun current(): String = providerStatus(settings.state.value.useFakeProviders, key.value)

    fun refreshKey() {
        key.value = secrets.geminiKeyState()
    }
}
