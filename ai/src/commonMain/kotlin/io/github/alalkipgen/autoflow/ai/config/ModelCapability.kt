package io.github.alalkipgen.autoflow.ai.config

import io.github.alalkipgen.autoflow.ai.Availability
import io.github.alalkipgen.autoflow.core.model.Ratio

enum class ImageMode {
    /** Copy the ImageDialect prompt, generate in AI Studio/Flow, import the file (D-14). */
    MANUAL,

    /** `GeminiImageProvider`; only when configured and the quota probe did not report `limit: 0`. */
    API,
}

/**
 * What the app may offer right now: `config/models.json` hints combined with runtime probes. The UI reads this
 * instead of model names, so a wrong hint degrades a feature instead of breaking it.
 */
data class ModelCapability(
    val textModels: List<String>,
    val imageMode: ImageMode,
    val imageRatios: List<Ratio>,
    val ttsBurmese: Boolean,
    val ttsReplication: Availability,
) {
    val canGenerateText: Boolean get() = textModels.isNotEmpty()

    companion object {
        /** [imageApiProbe]: result of the image quota probe; anything but AVAILABLE keeps the manual path. */
        fun from(config: ModelConfig, imageApiProbe: Availability = Availability.UNKNOWN) = ModelCapability(
            textModels = config.text.candidates,
            imageMode = if (config.image.provider == ImageModels.GEMINI && config.image.candidates.isNotEmpty() &&
                imageApiProbe == Availability.AVAILABLE
            ) ImageMode.API else ImageMode.MANUAL,
            imageRatios = config.image.supportedRatios,
            ttsBurmese = config.tts.chainFor("my").isNotEmpty(),
            ttsReplication = config.tts.replicationAvailability,
        )
    }
}
