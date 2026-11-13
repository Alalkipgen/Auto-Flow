package io.github.alalkipgen.autoflow.ui.settings

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.flags.FeatureFlags
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.media.FfmpegCapabilities
import io.github.alalkipgen.autoflow.media.FfmpegCheck
import io.github.alalkipgen.autoflow.media.OverlayBackend
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
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
    /** Last ffmpeg version check (PLAN 1D), e.g. `ffmpeg 7.0.2 · libass ✓ → text overlays: libass`; null = not run. */
    val ffmpegCheck: String? = null,
    /** True when the check found a problem (no libass or ffmpeg did not answer). */
    val ffmpegCheckWarn: Boolean = false,
    val models: LoadedModelConfig,
    val message: String? = null,
    /** WAV of the last "Test Gemini TTS (S5)", for ▶. */
    val ttsSample: String? = null,
    /** Feature flags with user overrides applied. */
    val features: FeatureFlags = FeatureFlags(),
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
    /** Runs ffmpeg for the version check; null = no check (tests, previews). */
    private val ffmpegRunner: ToolRunner? = null,
    /** S5 (D-34): one real Gemini TTS request; Ok = WAV bytes. Null = no button. */
    private val testTtsCall: (suspend () -> AiResult<ByteArray>)? = null,
    /** Text of the bundled `config/features.json`; null if the platform does not ship it. */
    private val bundledFeatures: String? = null,
    /** Keeps the S5 sample for ▶; returns its path or null. */
    private val saveTtsSample: (ByteArray) -> String? = { null },
) {
    val canTestTts: Boolean get() = testTtsCall != null

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

    /** Settings → Test Gemini TTS (S5): speaks one Burmese sentence and names the model that answered. */
    suspend fun testTts() {
        val call = testTtsCall ?: return
        _state.value = _state.value.copy(testing = true, message = null)
        var sample: String? = null
        val text = when (val r = call()) {
            is AiResult.Ok -> {
                sample = saveTtsSample(r.value)
                "Gemini TTS works" + (r.usage?.model?.let { " ($it)" } ?: "") + (if (sample != null) ". Tap ▶ to hear the Burmese sentence." else "")
            }
            is AiResult.RateLimited -> "Gemini TTS: the free-tier limit is reached for now" + (r.message.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            is AiResult.Failed -> "Gemini TTS: ${r.msg}"
            is AiResult.Deferred -> r.instruction
        }
        refreshUsage()
        done(text)
        _state.value = _state.value.copy(ttsSample = sample)
    }

    suspend fun refreshUsage() {
        _state.value = _state.value.copy(usageToday = usage.today())
    }

    /** Enables or disables a preview-feature flag override.  When [on] matches the bundled default, the override is removed. */
    fun setFeature(key: String, on: Boolean) = change {
        val bundledDefault = FeatureFlags.parse(bundledFeatures).asMap()[key] ?: false
        val newOverrides = if (on == bundledDefault) it.featureOverrides - key else it.featureOverrides + (key to on)
        it.copy(featureOverrides = newOverrides)
    }

    fun setUseFakes(fake: Boolean) = change { it.copy(useFakeProviders = fake) }

    fun setDefaultRatio(ratio: Ratio) = change { it.copy(defaultRatio = ratio) }

    fun setDefaultClipMs(ms: Long) = change { it.copy(defaultClipMs = ms) }

    fun setFfmpegPath(path: String?) {
        change { it.copy(ffmpegPath = path?.trim()?.ifEmpty { null }) }
        _state.value = _state.value.copy(ffmpegCheck = null, ffmpegCheckWarn = false)
    }

    /**
     * ffmpeg version check (PLAN 1D): `-version` / `-filters` / `-encoders` of the configured ffmpeg (else the one on
     * PATH; on Android the bundled one) → version and overlay backend (libass, else the PNG fallback).
     */
    suspend fun checkFfmpeg() {
        val runner = ffmpegRunner ?: return
        val path = settings.state.value.ffmpegPath.takeUnless { tools.bundled } ?: tools.findFfmpeg()
        if (path == null) return showCheck("ffmpeg not found: set its path or press Detect.", warn = true)
        showCheck("Checking ffmpeg…", warn = false)
        when (val r = FfmpegCheck.run(runner, path, path.substringBeforeLast('/', "."))) {
            is FfmpegCheck.Failed -> showCheck(r.message, warn = true)
            is FfmpegCheck.Ok -> showCheck(describe(r.caps), warn = !r.caps.hasAss || !r.caps.hasLibx264)
        }
    }

    private fun showCheck(text: String, warn: Boolean) {
        _state.value = _state.value.copy(ffmpegCheck = text, ffmpegCheckWarn = warn)
    }

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
        _state.value = snapshot(message).copy(
            usageToday = _state.value.usageToday,
            ffmpegCheck = _state.value.ffmpegCheck,
            ffmpegCheckWarn = _state.value.ffmpegCheckWarn,
        )
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
            features = FeatureFlags.load(bundledFeatures, s.featureOverrides),
        )
    }

    private fun PlatformResult<Unit>.text(ok: String?): String? = when (this) {
        is PlatformResult.Ok -> ok
        is PlatformResult.Failed -> reason
        is PlatformResult.Unsupported -> reason
    }

    companion object {
        /** One line for Settings → Tools. */
        fun describe(c: FfmpegCapabilities): String {
            fun mark(ok: Boolean) = if (ok) "✓" else "✗"
            val overlays = if (c.overlayBackend == OverlayBackend.LIBASS) "libass" else "PNG fallback (no libass)"
            return "ffmpeg ${c.version} · libass ${mark(c.hasAss)} · libx264 ${mark(c.hasLibx264)} · aac ${mark(c.hasAac)}" +
                " → text overlays: $overlays"
        }

        const val VLC_DOWNLOAD_URL = "https://www.videolan.org/vlc/"
        val CLIP_LENGTHS_MS = listOf(8_000L, 10_000L, 5_000L)
    }
}
