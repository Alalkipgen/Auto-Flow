package io.github.alalkipgen.autoflow.platform

/**
 * Hands a finished file (the Export screen's mp4 in `<project>/exports/`) to other apps: [open] in a viewer (video
 * player), [share] through the share sheet (Telegram, Drive, Quick Share…), [saveToGallery] as a copy in the shared
 * videos folder. Android binds it with a share sheet; the desktop opens files and folders but cannot share ([sharesFiles]).
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

    /** False where there is no share sheet (desktop): screens show the file's folder and [openFolder] instead. */
    val sharesFiles: Boolean get() = true

    /** Copies the file into the shared Downloads folder (Android: `Download/AutoFlow/[displayName]`); returns where. */
    suspend fun saveToDownloads(path: String, mime: String, displayName: String): PlatformResult<String> =
        PlatformResult.Unsupported("Saving to Downloads is not available here; the file is in the project's exports folder.")

    /** Shows a folder in the system file manager (desktop). */
    fun openFolder(path: String): PlatformResult<Unit> =
        PlatformResult.Unsupported("Opening folders is not available here: $path")
}
