package io.github.alalkipgen.autoflow.core.model

import io.github.alalkipgen.autoflow.core.recap.CaptionStyle
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The owner approved the dub script whose spoken lines hash to [hash] (`DubRules.hash`), at [at] (epoch ms). */
@Serializable
@SerialName("DubApproval")
data class DubApproval(val hash: String, val at: Long)

/**
 * A line's dub voice (D-2): the `au` take [au], spoken for take key [key] (`DubRules.takeKey`), with [ms] of voice after
 * the [leadMs] of silence before it (trailing silence not counted) — what the fit (`DubFit`) measures.
 */
@Serializable
@SerialName("DubTake")
data class DubTake(val au: ArtifactId, val key: String, val ms: Long, val leadMs: Long = 0)

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
 * spoken lines (`spokenMy`) live on the `st` cues. [takes]: cue id → its [DubTake] (D-2). [captionStyle]: how the burned
 * Burmese subtitles look (D-106, Movie Recap's [CaptionStyle]; the default = the boxed white Medium of before, and a
 * project that keeps it writes nothing new).
 */
@Serializable
@SerialName("DubbingTool")
data class DubbingTool(
    val schemaVersion: Int = 1,
    val scriptApproved: DubApproval? = null,
    val lock: VoiceLock? = null,
    val tone: String? = null,
    val takes: Map<String, DubTake> = emptyMap(),
    val original: DubOriginal = DubOriginal(),
    val burnSubtitles: Boolean = false,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val captionStyle: CaptionStyle = DEFAULT_CAPTIONS,
) {
    companion object {
        /** The burned subtitles before D-106: white, Medium, on the see-through dark box. */
        val DEFAULT_CAPTIONS = CaptionStyle(box = true)
    }
}
