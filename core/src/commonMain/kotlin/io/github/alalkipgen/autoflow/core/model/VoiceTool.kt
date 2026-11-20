package io.github.alalkipgen.autoflow.core.model

import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One part of the Text → voice text (specs/quick-tools.md §6): [hash] of its text after the Lexicon with the voice
 * lock and tone (the take key); [take] is its `au` artifact while that key still matches.
 */
@Serializable
@SerialName("VoicePart")
data class VoicePart(val id: String, val hash: String, val take: ArtifactId? = null)

/** Clean up sound (V-2): which of the three steps run; at least one is on. */
@Serializable
@SerialName("CleanChoice")
data class CleanChoice(val pauses: Boolean = true, val noise: Boolean = true, val loudness: Boolean = true) {
    val any: Boolean get() = pauses || noise || loudness
}

/** `project.voice` of a [Mode.VOICE_AUDIO] project (D-77). Tasks and their fields arrive with V-1…V-3. */
@Serializable
@SerialName("VoiceTool")
data class VoiceTool(
    val schemaVersion: Int = 1,
    /** [TASK_TEXT], [TASK_CLEAN] or [TASK_VIDEO_SOUND]; null until the first real step. */
    val task: String? = null,
    val text: String = "",
    val lock: VoiceLock? = null,
    val tone: String? = null,
    val parts: List<VoicePart> = emptyList(),
    /** The joined voice of every current take (`au`), null until joined. */
    val joined: ArtifactId? = null,
    /** Clean up sound / Video sound: the picked audio or video, copied into `source/` (width 0 = audio only). */
    val source: SourceVideo? = null,
    val clean: CleanChoice = CleanChoice(),
) {
    companion object {
        const val TASK_TEXT = "text"
        const val TASK_CLEAN = "clean"
        const val TASK_VIDEO_SOUND = "videoSound"
    }
}
