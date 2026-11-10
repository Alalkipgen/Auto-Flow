package io.github.alalkipgen.autoflow.ui.settings

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.platform.PlatformResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** App-wide settings in `~/.autoflow/settings.json`. Never holds the API key (that is SecureStorage, D-19). */
@Serializable
data class AppSettings(
    val schemaVersion: Int = 1,
    /** First run uses offline fakes; the user switches to Real after saving and testing a key. */
    val useFakeProviders: Boolean = true,
    val ffmpegPath: String? = null,
    /** VLC 3.x folder (D-16); null = auto-detect. */
    val vlcPath: String? = null,
    val fontPaths: List<String> = emptyList(),
    /** Used instead of the bundled `config/models.json` when set; read at start-up. */
    val modelsConfigPath: String? = null,
    val defaultRatio: Ratio = Ratio.R9_16,
    val defaultClipMs: Long = 8_000,
)

/**
 * Loads and saves [AppSettings]. Missing file = defaults. An unreadable file also gives defaults, sets [problem] and is
 * left untouched until the next change replaces it. Saves go to `.tmp`, then an atomic rename. Call from one thread
 * (the UI thread).
 */
class SettingsStore(private val fs: FileSystem, private val path: String) {
    private val _state: MutableStateFlow<AppSettings>
    val state: StateFlow<AppSettings>

    var problem: String? = null
        private set

    init {
        _state = MutableStateFlow(read())
        state = _state.asStateFlow()
    }

    fun update(change: (AppSettings) -> AppSettings): PlatformResult<Unit> {
        val next = change(_state.value)
        if (next == _state.value) return PlatformResult.Ok(Unit)
        return try {
            val dir = path.substringBeforeLast('/', "")
            if (dir.isNotEmpty()) fs.createDirectories(dir)
            fs.writeDurably("$path.tmp", json.encodeToString(AppSettings.serializer(), next).encodeToByteArray())
            fs.atomicMove("$path.tmp", path)
            _state.value = next
            problem = null
            PlatformResult.Ok(Unit)
        } catch (e: Exception) {
            PlatformResult.Failed("Cannot save settings: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun read(): AppSettings = try {
        if (fs.exists(path)) json.decodeFromString(AppSettings.serializer(), fs.read(path).decodeToString()) else AppSettings()
    } catch (e: Exception) {
        problem = "settings.json could not be read, so defaults are used: ${e.message ?: e::class.simpleName}"
        AppSettings()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true; prettyPrint = true }
    }
}
