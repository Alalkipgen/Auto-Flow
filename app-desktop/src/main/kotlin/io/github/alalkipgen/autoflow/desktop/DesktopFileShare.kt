package io.github.alalkipgen.autoflow.desktop

import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import java.awt.Desktop
import java.io.File

/** Export screen on the desktop: Open plays the mp4 in the default player; there is no share sheet. */
class DesktopFileShare : FileShare {
    override fun open(path: String, mime: String): PlatformResult<Unit> = try {
        val desktop = Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() && it.isSupported(Desktop.Action.OPEN) }
        if (desktop == null) {
            PlatformResult.Unsupported("Opening files is not available here; the video is in ${File(path).parent}")
        } else {
            desktop.open(File(path))
            PlatformResult.Ok(Unit)
        }
    } catch (e: Exception) {
        PlatformResult.Failed("Cannot open the video: ${e.message ?: e::class.simpleName}")
    }

    override fun share(path: String, mime: String, title: String): PlatformResult<Unit> =
        PlatformResult.Unsupported("There is no share sheet on the desktop; the video is in ${File(path).parent}")
}
