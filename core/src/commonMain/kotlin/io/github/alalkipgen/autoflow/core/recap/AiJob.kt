package io.github.alalkipgen.autoflow.core.recap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Persistent AI job record (5A-5, studio-v2.md §5).
 * Stored at `filesDir/ai-jobs/<id>.json`.
 */
@Serializable
@SerialName("AiJob")
data class AiJob(
    val id: String,
    val kind: AiJobKind,
    /** Project folder path relative to `filesDir`. */
    val project: String,
    /** Item keys (segment indices, scene indices, etc.) still to be processed. */
    val items: List<String> = emptyList(),
    val state: AiJobState = AiJobState.QUEUED,
    val error: String? = null,
    /** Measured ms per item (for ETA). Updated after each successful item. */
    val msPerItem: Long? = null,
    /** RPM limit for this job's model. */
    val rpm: Int = 60,
    /** Digest of immutable enqueue inputs; remaining [items] shrink during execution. */
    val inputKey: String? = null,
    val initialItemCount: Int? = null,
    val savedClips: Int? = null,
    val projectIdentity: String? = null,
    val timings: List<AiTimingSample> = emptyList(),
    val timingsUpdatedAt: Long? = null,
)

@Serializable
@SerialName("AiJobKind")
enum class AiJobKind {
    @SerialName("TTS_ALL") TTS_ALL,
    @SerialName("TRANSCRIBE") TRANSCRIBE,
    @SerialName("QUICK_RECAP") QUICK_RECAP,
    @SerialName("PUBLISH_KIT") PUBLISH_KIT,
    @SerialName("SCRIPT") SCRIPT,
    /** Whole-video transcription of a Subtitles project (S-5, D-80): items `k<start>-<end>`. */
    @SerialName("MEDIA_TRANSCRIBE") MEDIA_TRANSCRIBE,
    /** Translation of a Subtitles project's lines to Burmese (S-6, D-81): items `b<first cue id>`. */
    @SerialName("SUB_TRANSLATE") SUB_TRANSLATE,
    /** Text → voice Speak of a Voice & Audio project (V-1e): items = part ids `p<n>`. */
    @SerialName("TEXT_TTS") TEXT_TTS,
}

@Serializable
@SerialName("AiJobState")
enum class AiJobState {
    @SerialName("QUEUED") QUEUED,
    @SerialName("RUNNING") RUNNING,
    @SerialName("PAUSED") PAUSED,
    @SerialName("DONE") DONE,
    @SerialName("FAILED") FAILED,
    @SerialName("CANCELLED") CANCELLED;

    val finished: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
}

/** ETA in seconds for [job], given remaining items. */
fun AiJob.etaSeconds(): Long? {
    val remaining = items.size.toLong()
    if (remaining == 0L) return 0L
    val byMeasured = msPerItem?.let { remaining * it / 1000L }
    val byRpm = if (rpm > 0) remaining * 60L / rpm else null
    return when {
        byMeasured != null && byRpm != null -> maxOf(byMeasured, byRpm)
        byMeasured != null -> byMeasured
        byRpm != null -> byRpm
        else -> null
    }
}
