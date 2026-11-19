package io.github.alalkipgen.autoflow.ui.jobs

import io.github.alalkipgen.autoflow.core.recap.AiRunTiming
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

@OptIn(ExperimentalUuidApi::class)
internal fun foregroundRunId(): String = Uuid.random().toString()

/** Show the provider's real stage/retry timer only as long as this request is live. */
internal suspend fun <T> withGeminiProgress(id: String, block: suspend CoroutineScope.() -> T): T {
    val trace = AiRunTiming(id = id)
    trace.stage(null)
    return try { withContext(trace, block) } finally { trace.clear() }
}
