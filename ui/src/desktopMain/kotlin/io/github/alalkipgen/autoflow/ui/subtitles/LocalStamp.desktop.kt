package io.github.alalkipgen.autoflow.ui.subtitles

internal actual fun localOffsetMs(epochMs: Long): Int = java.util.TimeZone.getDefault().getOffset(epochMs)
