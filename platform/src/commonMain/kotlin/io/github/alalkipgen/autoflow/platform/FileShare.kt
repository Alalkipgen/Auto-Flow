package io.github.alalkipgen.autoflow.platform

/**
 * Hands a finished file (the Export screen's mp4 in `<project>/exports/`) to other apps: [open] in a viewer (video
 * player), [share] through the share sheet (Telegram, Drive, Quick Share…). Android binds it; desktop has none yet.
 */
interface FileShare {
    fun open(path: String, mime: String): PlatformResult<Unit>

    fun share(path: String, mime: String, title: String): PlatformResult<Unit>
}
