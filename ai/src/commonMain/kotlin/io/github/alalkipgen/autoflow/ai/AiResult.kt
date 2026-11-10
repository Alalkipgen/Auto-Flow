package io.github.alalkipgen.autoflow.ai

/** Result of every AI call (specs/ai-providers.md). No exceptions cross the :ai boundary. */
sealed interface AiResult<out T> {
    data class Ok<out T>(val value: T, val usage: Usage? = null) : AiResult<T>

    /** Quota or rate limit hit and not worth waiting for in-call; [retryAfterMs] when the API said so. */
    data class RateLimited(val retryAfterMs: Long?, val message: String = "") : AiResult<Nothing>

    /** [code] is a short machine code (e.g. `http_503`, `timeout`, `invalid_json`, `no_api_key`); [msg] is for humans. */
    data class Failed(val code: String, val msg: String) : AiResult<Nothing>

    /** Manual handoff started; the result arrives later as an imported artifact (D-14). */
    data class Deferred(val instruction: String) : AiResult<Nothing>
}

/** What a successful call cost. [model] is the model id that answered (after fallbacks). */
data class Usage(
    val model: String,
    val attempts: Int = 1,
    val latencyMs: Long = 0,
    val promptTokens: Int? = null,
    val outputTokens: Int? = null,
)
