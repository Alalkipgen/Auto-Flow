package io.github.alalkipgen.autoflow.platform

/** Finds external tools the app does not bundle: ffmpeg (1D) and VLC 3.x (D-16). Returned paths use `/`. */
interface ToolLocator {
    /** The ffmpeg executable on `PATH`, or null. */
    fun findFfmpeg(): String?

    /** A VLC folder containing `libvlc`, or null. The default install is `C:\Program Files\VideoLAN\VLC`. */
    fun findVlc(): String?

    /** True when [dir] holds a VLC install (D-16). */
    fun isVlcDir(dir: String): Boolean
}
