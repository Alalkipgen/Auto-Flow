package io.github.alalkipgen.autoflow.core.model

import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The owner approved the dub script whose spoken lines hash to [hash] (`DubRules.hash`), at [at] (epoch ms). */
@Serializable
@SerialName("DubApproval")
data class DubApproval(val hash: String, val at: Long)

/** Original sound under the dub (D-83): [LOWER] to [volume] (music and effects stay) or [MUTE] (everything goes). */
@Serializable
@SerialName("DubOriginal")
data class DubOriginal(val mode: String = LOWER, val volume: Double = 0.15) {
    companion object {
        const val LOWER = "lower"
        const val MUTE = "mute"
    }
}

/**
 * `project.dubbing` of a [Mode.DUBBING] project (specs/dubbing.md §3, D-83). The video and the current `st` version live
 * in `project.subtitles` as in a Subtitles project (D-88), so Video / Translate reuse the Subtitles screens; the
 * spoken lines (`spokenMy`) live on the `st` cues. [takes]: cue id → `au` take (D-2).
 */
@Serializable
@SerialName("DubbingTool")
data class DubbingTool(
    val schemaVersion: Int = 1,
    val scriptApproved: DubApproval? = null,
    val lock: VoiceLock? = null,
    val tone: String? = null,
    val takes: Map<String, ArtifactId> = emptyMap(),
    val original: DubOriginal = DubOriginal(),
    val burnSubtitles: Boolean = false,
)
