package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.io.joinPath
import kotlinx.serialization.Serializable

/**
 * One direct ffmpeg command on the render queue (specs/quick-tools.md §7: burned subtitles, cleaned sound, sound
 * swaps — no render graph, no AI). [args] are ffmpeg's arguments without the global `-progress` ones; [PART] marks
 * where the output goes. ffmpeg writes `<output>.part.<ext>`, renamed to [output] only on success, so a failed or
 * cancelled task never leaves a half file under the real name; Resume = run the same task again. [output] is
 * relative to the project folder or absolute; an [output] that is already there is the finished result (the app
 * names outputs so the same task gives the same name, and never overwrites one). [kind] names the task in Jobs.
 */
@Serializable
data class MediaTaskJob(
    val kind: String,
    val args: List<String>,
    val output: String,
    val durationMs: Long,
) {
    /** Where ffmpeg writes before the rename. */
    fun partPath(workDir: String): String {
        val out = absolute(workDir, output)
        val ext = out.substringAfterLast('.', "")
        return if (ext.isEmpty() || '/' in ext || '\\' in ext) "$out.part" else out.removeSuffix(".$ext") + ".part.$ext"
    }

    /** The queue carries [RenderJob]s: this task inside one (the graph is unused). */
    fun toRenderJob(title: String, workDir: String): RenderJob = RenderJob(
        title = title,
        graph = RenderGraph(clips = emptyList(), output = Output(Ratio.R16_9, 0, 0, 0, Quality.EXPORT, "")),
        workDir = workDir,
        files = emptyMap(),
        fontsDir = workDir,
        outputName = absolute(workDir, output).substringBeforeLast('.'),
        mediaTask = this,
    )

    companion object {
        const val PART = "{part}"
        const val SUBTITLE_BURN = "subtitle-burn"

        fun absolute(workDir: String, path: String): String =
            if (path.startsWith("/") || (path.length > 2 && path[1] == ':')) path else joinPath(workDir, path)
    }
}
