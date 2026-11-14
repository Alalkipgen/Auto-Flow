package io.github.alalkipgen.autoflow.core.recap

import kotlinx.coroutines.delay

/**
 * Token-bucket RPM pacer (5A-5, studio-v2.md §5).
 *
 * Tracks the timestamps of recent requests in a rolling 60-second window.
 * [pace] suspends until there is a free slot under [rpm].
 * [recordRetryDelay] overrides the next wait with the server-supplied backoff.
 */
class RpmPacer(val rpm: Int) {
    private val windowMs = 60_000L
    private val timestamps = ArrayDeque<Long>()
    private var retryDelayMs: Long = 0L
    private var clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() }

    /** Inject a custom clock for testing. */
    internal fun withClock(c: () -> Long): RpmPacer { clock = c; return this }

    /** Call before each API request; suspends if the RPM cap has been reached. */
    suspend fun pace() {
        // Server-side retry-delay wins.
        if (retryDelayMs > 0) {
            delay(retryDelayMs)
            retryDelayMs = 0L
        }
        while (true) {
            val now = clock()
            val cutoff = now - windowMs
            while (timestamps.isNotEmpty() && timestamps.first() < cutoff) timestamps.removeFirst()
            if (timestamps.size < rpm) {
                timestamps.addLast(now)
                return
            }
            // Wait until the oldest slot drops out of the window.
            val waitMs = timestamps.first() + windowMs - now + 1L
            delay(waitMs)
        }
    }

    /**
     * Record a retry delay from a 429 response (Retry-After header or JSON `retryDelay`).
     * The next [pace] call will honour this delay before pacing normally.
     */
    fun recordRetryDelay(ms: Long) {
        retryDelayMs = maxOf(retryDelayMs, ms)
    }
}
