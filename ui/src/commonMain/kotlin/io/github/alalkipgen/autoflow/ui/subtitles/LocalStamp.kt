package io.github.alalkipgen.autoflow.ui.subtitles

import kotlin.time.Instant

/** The device time zone's offset from UTC at [epochMs], in milliseconds. */
internal expect fun localOffsetMs(epochMs: Long): Int

/** `yyyy-MM-dd HH:mm` in local time, for automatic tool project names (specs/quick-tools.md §3). */
internal fun localStamp(now: Instant, offsetMs: (Long) -> Int = ::localOffsetMs): String {
    val iso = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + offsetMs(now.toEpochMilliseconds())).toString()
    return iso.take(10) + " " + iso.substring(11, 16)
}
