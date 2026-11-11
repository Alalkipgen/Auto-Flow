package io.github.alalkipgen.autoflow.platform

/**
 * Hands a finished file (the Export screen's mp4 in `<project>/exports/`) to other apps: [open] in a viewer (video
 * player), [share] through the share sheet (Telegram, Drive, Quick Share…), [saveToGallery] as a copy in the shared
 * videos folder. Android binds it; desktop has none yet.
 */
interface FileShare {
    fun open(path: String, mime: String): PlatformResult<Unit>

    fun share(path: String, mime: String, title: String): PlatformResult<Unit>

    /**
     * Copies the file into the phone's gallery (Android: `Movies/AutoFlow/[displayName]` through MediaStore, no
     * storage permission; the copy stays after uninstalling). Returns where the copy went, for the screen.
     */
    suspend fun saveToGallery(path: String, mime: String, displayName: String): PlatformResult<String> =
        PlatformResult.Unsupported("Saving to the gallery is not available here; the video is in the project's exports folder.")
}
