package io.github.alalkipgen.autoflow.ai.manual

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.ImageProvider
import io.github.alalkipgen.autoflow.core.model.Ratio

/**
 * Default [ImageProvider] (D-14): copies the ImageDialect prompt, optionally opens AI Studio, and returns
 * [AiResult.Deferred]. The image comes back later through Import image / Inbox as an `im` artifact.
 * Platform actions are injected so :ai stays free of platform code.
 */
class ManualImageProvider(
    private val copyToClipboard: (String) -> Unit,
    private val openUrl: ((String) -> Unit)? = null,
    private val studioUrl: String,
) : ImageProvider {
    override suspend fun image(prompt: String, ratio: Ratio, refImages: List<ByteArray>): AiResult<ByteArray> {
        if (prompt.isBlank()) return AiResult.Failed("empty_prompt", "The image prompt is empty")
        return try {
            copyToClipboard(prompt)
            openUrl?.invoke(studioUrl)
            AiResult.Deferred(INSTRUCTION)
        } catch (e: Exception) {
            AiResult.Failed("manual_handoff", e.message ?: e.toString())
        }
    }

    companion object {
        const val INSTRUCTION = "Generate the image, then Import image (file/inbox)"
    }
}
