package io.github.alalkipgen.autoflow.ai.retry

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** D-15 resilience knobs. Defaults are the decision values; tests may shrink them. */
data class RetryPolicy(
    val maxTries: Int = 5,
    val baseDelayMs: Long = 1_000,
    val maxRetryAfterMs: Long = 60_000,
    val attemptTimeoutMs: Long = 120_000,
) {
    /** Wait after the [failedTries]-th failed try of one model: base, 2·base, 4·base … */
    fun backoffMs(failedTries: Int): Long = baseDelayMs shl (failedTries - 1).coerceIn(0, 20)
}

/** One request to one model, classified for [ModelChain]. Providers map HTTP results onto these. */
sealed interface Attempt<out T> {
    data class Success<out T>(val value: T, val promptTokens: Int? = null, val outputTokens: Int? = null) : Attempt<T>

    /** 500/503 or an unusable answer: retry the same model with backoff. */
    data class Transient(val code: String, val msg: String) : Attempt<Nothing>

    /** 429: waited for in-call only when [retryAfterMs] ≤ [RetryPolicy.maxRetryAfterMs], else next model. */
    data class Limited(val retryAfterMs: Long?, val msg: String) : Attempt<Nothing>

    /** Model missing, retired or not allowed for this key: go to the next candidate at once. */
    data class Unavailable(val code: String, val msg: String) : Attempt<Nothing>

    /** Bad key, bad request, blocked content, no network: stop; another model will not help. */
    data class Fatal(val code: String, val msg: String) : Attempt<Nothing>
}

/**
 * Runs one stage call over the ordered `candidates` list from `config/models.json` (D-15): backoff on transient
 * errors, short 429 waits, next candidate on long 429 / unavailable / timeout. Each request is counted in
 * [usage]. Cancellation propagates unchanged, so a cancelled call never yields a partial result.
 */
class ModelChain(
    private val policy: RetryPolicy = RetryPolicy(),
    private val usage: UsageCounter? = null,
) {
    suspend fun <T> run(candidates: List<String>, attempt: suspend (model: String) -> Attempt<T>): AiResult<T> {
        if (candidates.isEmpty()) return AiResult.Failed("no_model", "No candidate models configured")
        var attempts = 0
        var limited: Attempt.Limited? = null
        var last: Attempt<Nothing>? = null
        for (model in candidates) {
            var tries = 0
            while (tries < policy.maxTries) {
                tries++
                attempts++
                usage?.record(model)
                val mark = TimeSource.Monotonic.markNow()
                val outcome = withTimeoutOrNull(policy.attemptTimeoutMs) { attempt(model) }
                    ?: Attempt.Unavailable("timeout", "$model gave no answer within ${policy.attemptTimeoutMs / 1000} s")
                when (outcome) {
                    is Attempt.Success -> return AiResult.Ok(
                        outcome.value,
                        Usage(model, attempts, mark.elapsedNow().inWholeMilliseconds, outcome.promptTokens, outcome.outputTokens),
                    )
                    is Attempt.Fatal -> return AiResult.Failed(outcome.code, outcome.msg)
                    is Attempt.Unavailable -> {
                        last = outcome
                        break
                    }
                    is Attempt.Transient -> {
                        last = outcome
                        if (tries < policy.maxTries) delay(policy.backoffMs(tries))
                    }
                    is Attempt.Limited -> {
                        val wait = outcome.retryAfterMs
                        if (wait != null && wait <= policy.maxRetryAfterMs && tries < policy.maxTries) {
                            delay(wait)
                        } else {
                            limited = listOfNotNull(limited, outcome).minBy { it.retryAfterMs ?: Long.MAX_VALUE }
                            break
                        }
                    }
                }
            }
        }
        limited?.let { return AiResult.RateLimited(it.retryAfterMs, it.msg) }
        return when (val l = last) {
            is Attempt.Transient -> AiResult.Failed(l.code, l.msg)
            is Attempt.Unavailable -> AiResult.Failed(l.code, l.msg)
            else -> AiResult.Failed("failed", "All candidate models failed")
        }
    }
}
