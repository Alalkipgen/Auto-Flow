package io.github.alalkipgen.autoflow.core.recap

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
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
class AiRunTiming(previous: List<AiTimingSample> = emptyList(), private val id: String? = null) : AbstractCoroutineContextElement(Key) {
    private val events = MutableStateFlow(previous)
    val samples: List<AiTimingSample> get() = events.value
    fun add(sample: AiTimingSample) = events.update { it + sample }

    data class Live(val started: TimeMark, val stage: AiTimingStage? = null, val retryUntil: TimeMark? = null)

    private val started = TimeSource.Monotonic.markNow()
    fun stage(stage: AiTimingStage?, wait: Long? = null) {
        val key = id ?: return
        live.update { it + (key to Live(started, stage, wait?.let { ms -> TimeSource.Monotonic.markNow() + ms.milliseconds })) }
    }
    fun currentStage(): Live? = id?.let { live.value[it] }
    fun clear() { id?.let { key -> live.update { it - key } } }
    fun restore(previous: Live?) {
        val key = id ?: return
        live.update { if (previous == null) it - key else it + (key to previous) }
    }

    companion object Key : CoroutineContext.Key<AiRunTiming> {
        private val live = MutableStateFlow<Map<String, Live>>(emptyMap())
        val active = live.asStateFlow()
        private val queued = MutableStateFlow<Map<String, TimeMark>>(emptyMap())
        internal fun queued(id: String) { queued.update { it + (id to TimeSource.Monotonic.markNow()) } }
        internal fun dequeueMs(id: String): Long? = queued.getAndUpdate { it - id }[id]?.elapsedNow()?.inWholeMilliseconds
        fun forgetQueue(id: String) { queued.update { it - id } }
    }
}

suspend inline fun <T> timedAiStage(stage: AiTimingStage, status: Int? = null, retryDelayMs: Long? = null, block: () -> T): T {
    val trace = coroutineContext[AiRunTiming] ?: return block()
    val mark = TimeSource.Monotonic.markNow()
    val previous = trace.currentStage()
    trace.stage(stage, retryDelayMs)
    return try { block() } finally {
        trace.restore(previous)
        trace.add(AiTimingSample(stage, mark.elapsedNow().inWholeMilliseconds, httpStatus = status, retryDelayMs = retryDelayMs))
    }
}
