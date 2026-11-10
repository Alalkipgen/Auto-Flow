package io.github.alalkipgen.autoflow.core.store

import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Advances one second per call so timestamps are distinct and predictable. */
class TestClock(private var next: Instant = Instant.parse("2026-11-10T08:00:00Z")) : Clock {
    override fun now(): Instant = next.also { next += 1.seconds }
}
