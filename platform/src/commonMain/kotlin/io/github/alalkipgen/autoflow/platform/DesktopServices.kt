package io.github.alalkipgen.autoflow.platform

/** Plain-text clipboard. Used for "Copy prompt" / "Copy image prompt" (D-14). */
fun interface Clipboard {
    fun copyText(text: String): PlatformResult<Unit>
}

/** Opens a web page in the user's browser: Flow, AI Studio, the VLC download page (D-16). Only http(s). */
fun interface UrlOpener {
    fun open(url: String): PlatformResult<Unit>
}

/** Native open dialogs. Returned paths use `/` ([toPortablePath]). */
interface FilePicker {
    /** `Ok(null)` = the user cancelled. */
    suspend fun pickFolder(title: String, startDir: String? = null): PlatformResult<String?>

    /**
     * `Ok(emptyList())` = the user cancelled. [extensions] are lower-case without the dot (`"mp4"`); empty = any file.
     * Picked files with other extensions are dropped; if none are left the result is `Failed`.
     */
    suspend fun pickFiles(
        title: String,
        extensions: Set<String> = emptySet(),
        multiple: Boolean = false,
        startDir: String? = null,
    ): PlatformResult<List<String>>
}

/** True for absolute `http`/`https` URLs with a host; the only kind [UrlOpener] opens. */
fun isWebUrl(url: String): Boolean {
    val m = WEB_URL.matchEntire(url.trim()) ?: return false
    return m.groupValues[1].isNotEmpty()
}

/** True when [name] ends in `.<ext>` for one of [extensions] (case-insensitive); always true for an empty set. */
fun hasExtension(name: String, extensions: Set<String>): Boolean {
    if (extensions.isEmpty()) return true
    val ext = name.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase()
    return ext.isNotEmpty() && extensions.any { it.lowercase().removePrefix(".") == ext }
}

private val WEB_URL = Regex("""(?i)https?://([^/?#\s:@]+(?::\d+)?)(?:[/?#]\S*)?""")
