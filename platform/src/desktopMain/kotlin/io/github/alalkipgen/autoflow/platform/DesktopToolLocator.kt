package io.github.alalkipgen.autoflow.platform

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath

/** [ToolLocator] from `PATH` and the standard install folders. [env] and [osName] are injectable for tests. */
class DesktopToolLocator(
    private val fs: FileSystem = JvmFs(),
    private val env: (String) -> String? = System::getenv,
    private val osName: String = System.getProperty("os.name").orEmpty(),
) : ToolLocator {
    private val windows get() = osName.startsWith("Windows")

    override fun findFfmpeg(): String? {
        val exe = if (windows) "ffmpeg.exe" else "ffmpeg"
        val sep = if (windows) ';' else ':'
        return env("PATH").orEmpty().split(sep).map { it.trim().trim('"').toPortablePath() }
            .filter { it.isNotEmpty() }
            .map { joinPath(it, exe) }
            .firstOrNull { exists(it) }
    }

    override fun findVlc(): String? {
        val candidates = if (windows) {
            listOfNotNull(env("ProgramFiles"), "C:\\Program Files").map { joinPath(it.toPortablePath(), "VideoLAN/VLC") }
        } else {
            listOf("/usr/lib/x86_64-linux-gnu", "/usr/lib", "/Applications/VLC.app/Contents/MacOS/lib")
        }
        return candidates.distinct().firstOrNull(::isVlcDir)
    }

    override fun isVlcDir(dir: String): Boolean =
        listOf("libvlc.dll", "libvlc.so.5", "libvlc.so", "libvlc.dylib").any { exists(joinPath(dir.toPortablePath(), it)) }

    private fun exists(path: String) = try {
        fs.exists(path)
    } catch (_: Exception) {
        false
    }
}
