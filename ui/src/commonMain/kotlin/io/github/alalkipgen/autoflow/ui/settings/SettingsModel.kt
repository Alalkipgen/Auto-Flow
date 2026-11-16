package io.github.alalkipgen.autoflow.ui.settings

import io.github.alalkipgen.autoflow.ui.i18n.*
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
    val ffmpegNotice: UiNotice? = null,
    /** True when the check found a problem (no libass or ffmpeg did not answer). */
    val ffmpegCheckWarn: Boolean = false,
    val models: LoadedModelConfig,
    val message: String? = null,
    val notice: UiNotice? = null,
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
    private val builtInFonts: BuiltInFonts? = null,
) {
    val appSettings: StateFlow<AppSettings> get() = settings.state

    fun setUiLanguage(language: UiLanguage) {
        when (val result = settings.update { it.copy(uiLanguage = language.code) }) {
            is PlatformResult.Ok -> _state.value = _state.value.copy(settings = settings.state.value)
            is PlatformResult.Failed -> done(result.reason)
            is PlatformResult.Unsupported -> done(result.reason)
        }
    }

    val builtInFontPaths: List<String> get() = builtInFonts?.paths.orEmpty()
    val canTestTts: Boolean get() = testTtsCall != null

    private val _state = MutableStateFlow(snapshot(message = settings.problem ?: models.problem))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private fun language() = UiLanguage.fromCode(settings.state.value.uiLanguage)

    fun saveKey(input: String) {
        if (input.isBlank()) report(UiNotice(UiText.KEY_ENTER_FIRST))
        else result(secrets.put(SecretNames.GEMINI_API_KEY, input.trim()), UiNotice(UiText.KEY_SAVED))
    }

    fun removeKey() = result(secrets.remove(SecretNames.GEMINI_API_KEY), UiNotice(UiText.KEY_REMOVED))

    suspend fun testKey() {
        _state.value = _state.value.copy(testing = true, message = null, notice = null)
        val text = when (val r = testKeyCall()) {
            is AiResult.Ok -> UiNotice(UiText.KEY_WORKS, "model" to (r.usage?.model?.let { " ($it)" } ?: ""))
            is AiResult.RateLimited -> UiNotice(UiText.KEY_RATE_LIMIT)
            is AiResult.Failed -> UiNotice(UiText.RESULT_DETAIL, "detail" to r.msg)
            is AiResult.Deferred -> UiNotice(UiText.RESULT_DETAIL, "detail" to r.instruction)
        }
        refreshUsage()
        report(text)
    }

    /** Settings → Test Gemini TTS (S5): speaks one Burmese sentence and names the model that answered. */
    suspend fun testTts() {
        val call = testTtsCall ?: return
        _state.value = _state.value.copy(testing = true, message = null, notice = null)
        var sample: String? = null
        val text = when (val r = call()) {
            is AiResult.Ok -> {
                sample = saveTtsSample(r.value)
                UiNotice(if (sample != null) UiText.TTS_WORKS_PLAY else UiText.TTS_WORKS, "model" to (r.usage?.model?.let { " ($it)" } ?: ""))
            }
            is AiResult.RateLimited -> UiNotice(UiText.TTS_RATE_LIMIT, "detail" to (r.message.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""))
            is AiResult.Failed -> UiNotice(UiText.TTS_FAILED, "detail" to r.msg)
            is AiResult.Deferred -> UiNotice(UiText.RESULT_DETAIL, "detail" to r.instruction)
        }
        refreshUsage()
        report(text)
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

    /** Reopening Settings after Self-test must show overrides saved by the other settings model. */
    fun refresh() { _state.value = snapshot(message = settings.problem ?: models.problem) }

    fun setUseFakes(fake: Boolean) = change { it.copy(useFakeProviders = fake) }

    fun setDefaultRatio(ratio: Ratio) = change { it.copy(defaultRatio = ratio) }

    fun setDefaultClipMs(ms: Long) = change { it.copy(defaultClipMs = ms) }

    fun setFfmpegPath(path: String?) {
        change { it.copy(ffmpegPath = path?.trim()?.ifEmpty { null }) }
        _state.value = _state.value.copy(ffmpegCheck = null, ffmpegNotice = null, ffmpegCheckWarn = false)
    }

    /**
     * ffmpeg version check (PLAN 1D): `-version` / `-filters` / `-encoders` of the configured ffmpeg (else the one on
     * PATH; on Android the bundled one) → version and overlay backend (libass, else the PNG fallback).
     */
    suspend fun checkFfmpeg() {
        val runner = ffmpegRunner ?: return
        val path = settings.state.value.ffmpegPath.takeUnless { tools.bundled } ?: tools.findFfmpeg()
        if (path == null) return showCheck(UiNotice(UiText.FFMPEG_SET_PATH), warn = true)
        showCheck(UiNotice(UiText.FFMPEG_CHECKING), warn = false)
        when (val r = FfmpegCheck.run(runner, path, path.substringBeforeLast('/', "."))) {
            is FfmpegCheck.Failed -> showCheck(UiNotice(UiText.RESULT_DETAIL, "detail" to r.message), warn = true)
            is FfmpegCheck.Ok -> showCheck(describeNotice(r.caps), warn = !r.caps.hasAss || !r.caps.hasLibx264)
        }
    }

    private fun showCheck(text: UiNotice, warn: Boolean) {
        _state.value = _state.value.copy(ffmpegCheck = text.text(UiLanguage.ENGLISH), ffmpegNotice = text, ffmpegCheckWarn = warn)
    }

    fun detectFfmpeg() {
        val found = tools.findFfmpeg() ?: return report(UiNotice(UiText.FFMPEG_NOT_FOUND))
        setFfmpegPath(found)
    }

    suspend fun browseFfmpeg() = picked(picker.pickFiles(UiText.CHOOSE_FFMPEG.text(language()))) { setFfmpegPath(it.first()) }

    fun setVlcPath(dir: String?) {
        val d = dir?.trim()?.ifEmpty { null }
        if (d != null && !tools.isVlcDir(d)) return report(UiNotice(UiText.VLC_DIR_INVALID, "dir" to d))
        change { it.copy(vlcPath = d) }
    }

    suspend fun browseVlc() = picked(picker.pickFolder(UiText.CHOOSE_VLC_FOLDER.text(language()))) { setVlcPath(it) }

    fun openVlcDownload() = done(urls.open(VLC_DOWNLOAD_URL).text(null))

    suspend fun addFonts() = picked(picker.pickFiles(UiText.ADD_FONTS_PICKER.text(language()), setOf("ttf", "otf"), multiple = true)) { fonts ->
        change { s -> s.copy(fontPaths = (s.fontPaths + fonts).distinct()) }
    }

    fun removeFont(path: String) = change { it.copy(fontPaths = it.fontPaths - path) }

    suspend fun browseModelsConfig() = picked(picker.pickFiles(UiText.CHOOSE_MODELS.text(language()), setOf("json"))) { setModelsConfigPath(it.first()) }

    fun setModelsConfigPath(path: String?) = change(note = UiNotice(UiText.MODEL_RESTART)) {
        it.copy(modelsConfigPath = path?.trim()?.ifEmpty { null })
    }

    private fun change(note: UiNotice? = null, edit: (AppSettings) -> AppSettings) = result(settings.update(edit), note)

    private fun result(result: PlatformResult<Unit>, ok: UiNotice?) {
        when (result) {
            is PlatformResult.Ok -> if (ok == null) done(null) else report(ok)
            is PlatformResult.Failed -> done(result.reason)
            is PlatformResult.Unsupported -> done(result.reason)
        }
    }

    private fun report(notice: UiNotice) = done(notice.text(UiLanguage.ENGLISH), notice)

    private fun <T> picked(result: PlatformResult<T?>, use: (T) -> Unit) {
        when (result) {
            is PlatformResult.Ok -> result.value?.takeUnless { it is List<*> && it.isEmpty() }?.let(use)
            is PlatformResult.Failed -> done(result.reason)
            is PlatformResult.Unsupported -> done(result.reason)
        }
    }

    private fun done(message: String?, notice: UiNotice? = null) {
        status.refreshKey()
        _state.value = snapshot(message, notice).copy(
            usageToday = _state.value.usageToday,
            ffmpegCheck = _state.value.ffmpegCheck,
            ffmpegNotice = _state.value.ffmpegNotice,
            ffmpegCheckWarn = _state.value.ffmpegCheckWarn,
        )
    }

    private fun snapshot(message: String?, notice: UiNotice? = null): SettingsUiState {
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
            notice = notice,
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
        fun describe(c: FfmpegCapabilities, language: UiLanguage = UiLanguage.ENGLISH): String = describeNotice(c).text(language)

        private fun describeNotice(c: FfmpegCapabilities): UiNotice {
            fun mark(ok: Boolean) = if (ok) "✓" else "✗"
            return UiNotice(UiText.FFMPEG_DESCRIPTION, "version" to c.version, "ass" to mark(c.hasAss),
                "x264" to mark(c.hasLibx264), "aac" to mark(c.hasAac),
                "overlays" to if (c.overlayBackend == OverlayBackend.LIBASS) "libass" else UiNotice(UiText.PNG_FALLBACK))
        }

        const val VLC_DOWNLOAD_URL = "https://www.videolan.org/vlc/"
        val CLIP_LENGTHS_MS = listOf(8_000L, 10_000L, 5_000L)
    }
}
