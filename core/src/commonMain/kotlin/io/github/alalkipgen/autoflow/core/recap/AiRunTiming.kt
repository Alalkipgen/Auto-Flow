package io.github.alalkipgen.autoflow.core.recap

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

@Serializable
enum class AiTimingStage { QUEUE, MEDIA_PREP, PACER, UPLOAD, FIRST_BYTE, RESPONSE, RETRY_WAIT, PARSE, SAVE, UI_UPDATE }

/** Counts, durations and HTTP status only: no provider content, URL, file path or credentials. */
@Serializable
data class AiTimingSample(
    val stage: AiTimingStage,
    val ms: Long,
    val bytes: Long? = null,
    val httpStatus: Int? = null,
    val retryDelayMs: Long? = null,
)

/** One capture per execution. Queue marks are intentionally absent after process death. */
class AiRunTiming(previous: List<AiTimingSample> = emptyList()) : AbstractCoroutineContextElement(Key) {
    private val events = MutableStateFlow(previous)
    val samples: List<AiTimingSample> get() = events.value
    fun add(sample: AiTimingSample) = events.update { it + sample }

    companion object Key : CoroutineContext.Key<AiRunTiming> {
        private val queued = MutableStateFlow<Map<String, TimeMark>>(emptyMap())
        internal fun queued(id: String) { queued.update { it + (id to TimeSource.Monotonic.markNow()) } }
        internal fun dequeueMs(id: String): Long? = queued.getAndUpdate { it - id }[id]?.elapsedNow()?.inWholeMilliseconds
        fun forgetQueue(id: String) { queued.update { it - id } }
    }
}

suspend inline fun <T> timedAiStage(stage: AiTimingStage, status: Int? = null, retryDelayMs: Long? = null, block: () -> T): T {
    val trace = coroutineContext[AiRunTiming] ?: return block()
    val mark = TimeSource.Monotonic.markNow()
    return try { block() } finally {
        trace.add(AiTimingSample(stage, mark.elapsedNow().inWholeMilliseconds, httpStatus = status, retryDelayMs = retryDelayMs))
    }
}
