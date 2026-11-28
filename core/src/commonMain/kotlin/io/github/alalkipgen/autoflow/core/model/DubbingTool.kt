package io.github.alalkipgen.autoflow.core.model

import io.github.alalkipgen.autoflow.core.recap.CaptionStyle
import io.github.alalkipgen.autoflow.core.recap.MaskStrength
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

/** Where a dub's blur box starts (D-107): a strip, a logo corner, or a box placed anywhere ([CUSTOM], as many as needed). */
@Serializable
@SerialName("DubMaskKind")
enum class DubMaskKind(val rect: List<Float>) {
    /** The original video's own subtitles. */
    @SerialName("BOTTOM") BOTTOM(listOf(0f, 0.8f, 1f, 0.17f)),
    @SerialName("TOP") TOP(listOf(0f, 0.03f, 1f, 0.12f)),
    @SerialName("LOGO_TL") LOGO_TL(listOf(0.02f, 0.03f, 0.22f, 0.13f)),
    @SerialName("LOGO_TR") LOGO_TR(listOf(0.76f, 0.03f, 0.22f, 0.13f)),
    @SerialName("LOGO_BL") LOGO_BL(listOf(0.02f, 0.84f, 0.22f, 0.13f)),
    @SerialName("LOGO_BR") LOGO_BR(listOf(0.76f, 0.84f, 0.22f, 0.13f)),
    @SerialName("CUSTOM") CUSTOM(listOf(0.3f, 0.3f, 0.4f, 0.2f)),
    ;

    /** Full-width strips move only up / down. */
    val isStrip: Boolean get() = this == BOTTOM || this == TOP
}

/** A box over the video (D-107): [rect] = `[x, y, w, h]` in 0–1 of the picture; [fill] null = blurred, else `#RRGGBB`. */
@Serializable
@SerialName("DubMask")
data class DubMask(val kind: DubMaskKind, val rect: List<Float>, val fill: String? = null) {
    val centerY: Float get() = rect.getOrElse(1) { 0f } + rect.getOrElse(3) { 0f } / 2
}

/**
 * `project.dubbing` of a [Mode.DUBBING] project (specs/dubbing.md §3, D-83). The video and the current `st` version live
 * in `project.subtitles` as in a Subtitles project (D-88), so Video / Translate reuse the Subtitles screens; the
 * spoken lines (`spokenMy`) live on the `st` cues. [takes]: cue id → its [DubTake] (D-2). [captionStyle]: how the burned
 * Burmese subtitles look (D-106, Movie Recap's [CaptionStyle]; the default = the boxed white Medium of before, and a
 * project that keeps it writes nothing new). [masks]: the blur / colour boxes over the video's own subtitles, logos and
 * other things (D-107), blurred at [blurStrength]; [captionsOnBox] = the Burmese subtitles sit on the lowest box in the
 * lower half (the old subtitles' band). None of these is written while it keeps its default.
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
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val masks: List<DubMask> = emptyList(),
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val blurStrength: MaskStrength = MaskStrength.STRONG,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val captionsOnBox: Boolean = true,
) {
    companion object {
        /** The burned subtitles before D-106: white, Medium, on the see-through dark box. */
        val DEFAULT_CAPTIONS = CaptionStyle(box = true)
    }
}
