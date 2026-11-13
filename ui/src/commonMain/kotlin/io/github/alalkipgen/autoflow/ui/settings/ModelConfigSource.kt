package io.github.alalkipgen.autoflow.ui.settings

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.config.ModelConfig
import io.github.alalkipgen.autoflow.core.io.FileSystem

/** The `config/models.json` (and optionally `config/features.json`) shipped with the app; bound by the app module. */
fun interface BundledConfig {
    fun modelsJson(): String

    /** Returns the text of the bundled `config/features.json`, or null if the file is not present. */
    fun featuresJson(): String? = null
}

/** The model config in use, its text and where it came from, for the read-only view in Settings. */
data class LoadedModelConfig(val config: ModelConfig, val text: String, val source: String, val problem: String?) {
    companion object {
        const val BUNDLED = "bundled config/models.json"
    }
}

/** [overridePath] (Settings) wins when it reads and parses; otherwise the bundled file, with [LoadedModelConfig.problem]. */
fun loadModelConfig(fs: FileSystem, overridePath: String?, bundled: BundledConfig): LoadedModelConfig {
    var problem: String? = null
    if (!overridePath.isNullOrBlank()) {
        val text = try {
            fs.read(overridePath).decodeToString()
        } catch (e: Exception) {
            problem = "Cannot read $overridePath (${e::class.simpleName}); using the bundled models.json"
            null
        }
        if (text != null) {
            when (val r = ModelConfig.parse(text)) {
                is AiResult.Ok -> return LoadedModelConfig(r.value, text, overridePath, null)
                is AiResult.Failed -> problem = "${r.msg}; using the bundled models.json"
                else -> problem = "Unexpected result reading $overridePath; using the bundled models.json"
            }
        }
    }
    val text = bundled.modelsJson()
    return when (val r = ModelConfig.parse(text)) {
        is AiResult.Ok -> LoadedModelConfig(r.value, text, LoadedModelConfig.BUNDLED, problem)
        else -> LoadedModelConfig(ModelConfig(), text, LoadedModelConfig.BUNDLED, problem ?: "The bundled models.json is invalid")
    }
}
