package io.github.alalkipgen.autoflow.ui.settings

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.SecretNames
import io.github.alalkipgen.autoflow.platform.SecureStorage
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.platform.UrlOpener
import io.github.alalkipgen.autoflow.platform.getOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the Settings screen shows. [message] is the result of the last action. */
data class SettingsUiState(
    val settings: AppSettings,
    val keyState: KeyState,
    /** `••••abcd` for a saved key; never the whole key. */
    val maskedKey: String?,
    val testing: Boolean = false,
    val usageToday: Map<String, Int> = emptyMap(),
    /** The VLC folder playback will use: the configured one if valid, else the detected one (D-16). */
    val vlcDir: String?,
    /** ffmpeg ships inside the app (Android, D-23): no path or VLC rows; [ffmpegFound] says if the install has it. */
    val toolsBundled: Boolean = false,
    val ffmpegFound: Boolean = true,
    val models: LoadedModelConfig,
    val message: String? = null,
)

/** Settings screen logic. Actions return quickly except the `suspend` ones, which the screen launches. */
class SettingsModel(
    private val settings: SettingsStore,
    private val secrets: SecureStorage,
    private val status: ProviderStatusModel,
    private val usage: UsageCounter,
    private val models: LoadedModelConfig,
    private val tools: ToolLocator,
    private val picker: FilePicker,
    private val urls: UrlOpener,
    private val testKeyCall: suspend () -> AiResult<Unit>,
) {
    private val _state = MutableStateFlow(snapshot(message = settings.problem ?: models.problem))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun saveKey(input: String) = done(
        if (input.isBlank()) "Enter a key first" else secrets.put(SecretNames.GEMINI_API_KEY, input.trim()).text("Key saved"),
    )

    fun removeKey() = done(secrets.remove(SecretNames.GEMINI_API_KEY).text("Key removed"))

    suspend fun testKey() {
        _state.value = _state.value.copy(testing = true, message = null)
        val text = when (val r = testKeyCall()) {
            is AiResult.Ok -> "Key works" + (r.usage?.model?.let { " ($it)" } ?: "")
            is AiResult.RateLimited -> "Key accepted, but the free-tier limit is reached for now"
            is AiResult.Failed -> r.msg
            is AiResult.Deferred -> r.instruction
        }
        refreshUsage()
        done(text)
    }

    suspend fun refreshUsage() {
        _state.value = _state.value.copy(usageToday = usage.today())
    }

    fun setUseFakes(fake: Boolean) = change { it.copy(useFakeProviders = fake) }

    fun setDefaultRatio(ratio: Ratio) = change { it.copy(defaultRatio = ratio) }

    fun setDefaultClipMs(ms: Long) = change { it.copy(defaultClipMs = ms) }

    fun setFfmpegPath(path: String?) = change { it.copy(ffmpegPath = path?.trim()?.ifEmpty { null }) }

    fun detectFfmpeg() {
        val found = tools.findFfmpeg() ?: return done("ffmpeg was not found on PATH. Choose ffmpeg.exe instead.")
        setFfmpegPath(found)
    }

    suspend fun browseFfmpeg() = picked(picker.pickFiles("Choose ffmpeg")) { setFfmpegPath(it.first()) }

    fun setVlcPath(dir: String?) {
        val d = dir?.trim()?.ifEmpty { null }
        if (d != null && !tools.isVlcDir(d)) return done("No VLC (libvlc) in $d")
        change { it.copy(vlcPath = d) }
    }

    suspend fun browseVlc() = picked(picker.pickFolder("Choose the VLC folder")) { setVlcPath(it) }

    fun openVlcDownload() = done(urls.open(VLC_DOWNLOAD_URL).text(null))

    suspend fun addFonts() = picked(picker.pickFiles("Add fonts", setOf("ttf", "otf"), multiple = true)) { fonts ->
        change { s -> s.copy(fontPaths = (s.fontPaths + fonts).distinct()) }
    }

    fun removeFont(path: String) = change { it.copy(fontPaths = it.fontPaths - path) }

    suspend fun browseModelsConfig() = picked(picker.pickFiles("Choose models.json", setOf("json"))) { setModelsConfigPath(it.first()) }

    fun setModelsConfigPath(path: String?) = change(note = "Restart Auto Flow to load the model config") {
        it.copy(modelsConfigPath = path?.trim()?.ifEmpty { null })
    }

    private fun change(note: String? = null, edit: (AppSettings) -> AppSettings) = done(settings.update(edit).text(note))

    private fun <T> picked(result: PlatformResult<T?>, use: (T) -> Unit) {
        when (result) {
            is PlatformResult.Ok -> result.value?.takeUnless { it is List<*> && it.isEmpty() }?.let(use)
            is PlatformResult.Failed -> done(result.reason)
            is PlatformResult.Unsupported -> done(result.reason)
        }
    }

    private fun done(message: String?) {
        status.refreshKey()
        _state.value = snapshot(message).copy(usageToday = _state.value.usageToday)
    }

    private fun snapshot(message: String?): SettingsUiState {
        val s = settings.state.value
        val key = secrets.get(SecretNames.GEMINI_API_KEY)
        return SettingsUiState(
            settings = s,
            keyState = key.toKeyState(),
            maskedKey = key.getOrNull()?.takeIf { it.isNotBlank() }?.let { "••••" + it.takeLast(4) },
            vlcDir = s.vlcPath?.takeIf(tools::isVlcDir) ?: tools.findVlc(),
            toolsBundled = tools.bundled,
            ffmpegFound = !tools.bundled || tools.findFfmpeg() != null,
            models = models,
            message = message,
        )
    }

    private fun PlatformResult<Unit>.text(ok: String?): String? = when (this) {
        is PlatformResult.Ok -> ok
        is PlatformResult.Failed -> reason
        is PlatformResult.Unsupported -> reason
    }

    companion object {
        const val VLC_DOWNLOAD_URL = "https://www.videolan.org/vlc/"
        val CLIP_LENGTHS_MS = listOf(8_000L, 10_000L, 5_000L)
    }
}
