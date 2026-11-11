package io.github.alalkipgen.autoflow.ui.media

import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore

/**
 * Where ffmpeg / ffprobe are for the screens (desktop: the Settings path, else `PATH`; Android: bundled, D-23) and
 * the [runner] that starts them. [runner] is null when the app binds no `ToolRunner`; then nothing is probed.
 */
class MediaTools(private val settings: SettingsStore, private val tools: ToolLocator, val runner: ToolRunner?) {
    fun ffmpeg(): String? = settings.state.value.ffmpegPath.takeUnless { tools.bundled } ?: tools.findFfmpeg()

    fun ffprobe(): String? = tools.findFfprobe() ?: ffmpeg()?.let(::probeNextTo)

    /** Length of the audio in [file] (ffprobe), or null when it cannot be read or has no audio stream. */
    suspend fun audioDurationMs(file: String): Long? {
        val run = runner ?: return null
        val probe = ffprobe() ?: return null
        val r = MediaProbe(run, probe).probe(file, parentOf(file))
        return (r as? MediaProbe.Result.Ok)?.info?.takeIf { it.hasAudio && it.durationMs > 0 }?.durationMs
    }

    companion object {
        /** `…/ffmpeg.exe` → `…/ffprobe.exe`, `…/libffmpeg.so` → `…/libffprobe.so`; `/` or `\` separators. */
        fun probeNextTo(ffmpeg: String): String {
            val cut = maxOf(ffmpeg.lastIndexOf('/'), ffmpeg.lastIndexOf('\\')) + 1
            return ffmpeg.substring(0, cut) + ffmpeg.substring(cut).replace("ffmpeg", "ffprobe", ignoreCase = true)
        }

        fun parentOf(file: String): String {
            val cut = maxOf(file.lastIndexOf('/'), file.lastIndexOf('\\'))
            return if (cut > 0) file.substring(0, cut) else "."
        }
    }
}
