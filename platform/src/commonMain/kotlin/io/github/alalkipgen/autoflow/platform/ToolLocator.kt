package io.github.alalkipgen.autoflow.platform

/**
 * Finds ffmpeg (1D) and VLC 3.x (D-16). On the desktop they are external installs; the APK bundles ffmpeg and has
 * no VLC ([bundled], D-23). Returned paths use `/`.
 */
interface ToolLocator {
    /** True when ffmpeg ships inside the app, so there is no path to set and no VLC to install (Android). */
    val bundled: Boolean get() = false

    /** The ffmpeg executable on `PATH`, or null. */
    fun findFfmpeg(): String?

    /** The ffprobe executable when this platform knows it (Android: bundled); null = look next to ffmpeg. */
    fun findFfprobe(): String? = null

    /** A VLC folder containing `libvlc`, or null. The default install is `C:\Program Files\VideoLAN\VLC`. */
    fun findVlc(): String?

    /** True when [dir] holds a VLC install (D-16). */
    fun isVlcDir(dir: String): Boolean
}
