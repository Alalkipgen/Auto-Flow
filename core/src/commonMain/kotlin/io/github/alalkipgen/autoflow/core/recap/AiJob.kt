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
)

@Serializable
@SerialName("AiJobKind")
enum class AiJobKind {
    @SerialName("TTS_ALL") TTS_ALL,
    @SerialName("TRANSCRIBE") TRANSCRIBE,
    @SerialName("QUICK_RECAP") QUICK_RECAP,
    @SerialName("PUBLISH_KIT") PUBLISH_KIT,
}

@Serializable
@SerialName("AiJobState")
enum class AiJobState {
    @SerialName("QUEUED") QUEUED,
    @SerialName("RUNNING") RUNNING,
    @SerialName("PAUSED") PAUSED,
    @SerialName("DONE") DONE,
    @SerialName("FAILED") FAILED,
    @SerialName("CANCELLED") CANCELLED,
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
