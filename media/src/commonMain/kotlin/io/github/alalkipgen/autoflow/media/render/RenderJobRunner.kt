package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.media.probe.MediaProbe

/**
 * Does one [RenderJob] (shared by the Android worker and desktop): probes the clips for sound when it is used,
 * reads the font size factors from the fonts dir, compiles, writes `.ass` + `.rendergraph.json`, runs ffmpeg into
 * `<name>.part.mp4` and renames it to `<name>.mp4` only on success, so a failed render never replaces a good export.
 */
class RenderJobRunner(
    private val fs: FileSystem,
    private val ffmpeg: FfmpegRunner,
    private val probe: MediaProbe,
) {
    suspend fun run(job: RenderJob, onProgress: (FfmpegProgress.Progress) -> Unit = {}): RenderState {
        val abs = { p: String -> if (isAbsolute(p)) p else joinPath(job.workDir, p) }
        val graph = job.graph
        val clipSound = graph.audio == null || !graph.audio.muteClips
        val media = mutableMapOf<ArtifactId, RenderCompiler.ClipMedia>()
        for (id in graph.clips.mapNotNull { it.artifact }.distinct()) {
            val path = job.files[id.value]?.let(abs) ?: continue
            val hasAudio = if (clipSound) {
                when (val r = probe.probe(path, job.workDir)) {
                    is MediaProbe.Result.Ok -> r.info.hasAudio
                    is MediaProbe.Result.Failed -> return RenderState.Failed("cannot read the video $id: ${r.reason}")
                }
            } else {
                false
            }
            media[id] = RenderCompiler.ClipMedia(path, hasAudio)
        }
        val fontsDir = abs(job.fontsDir)
        val scales = fontScales(fontsDir)
        val out = abs(job.outputName)
        val env = RenderCompiler.Env(
            resolve = { media[it] },
            songPath = job.songPath?.let(abs),
            assPath = "$out.ass",
            fontsDir = fontsDir,
            outputPath = "$out.part.mp4",
            assScale = { scales[it] },
            placeholderFont = job.placeholderFont,
        )
        val compiled = when (val c = RenderCompiler.compile(graph, env)) {
            is RenderCompiler.Compiled.Invalid -> return RenderState.Failed(c.reason)
            is RenderCompiler.Compiled.Ok -> c
        }
        try {
            fs.createDirectories(out.substringBeforeLast('/', "."))
            compiled.ass?.let { fs.writeDurably(env.assPath, it.encodeToByteArray()) }
            fs.writeDurably("$out.rendergraph.json", CoreJson.encodeToString(RenderGraph.serializer(), graph).encodeToByteArray())
        } catch (e: Exception) {
            return RenderState.Failed("cannot write to ${out.substringBeforeLast('/')}: ${e.message}")
        }
        return when (val r = ffmpeg.render(compiled.args, job.workDir, compiled.durationMs, onProgress = onProgress)) {
            is FfmpegRunner.Outcome.CannotStart -> RenderState.Failed("ffmpeg cannot start: ${r.message}")
            is FfmpegRunner.Outcome.Failed ->
                RenderState.Failed(if (r.timedOut) "ffmpeg took too long and was stopped" else "ffmpeg failed (exit ${r.exitCode})", r.tail)
            is FfmpegRunner.Outcome.Rendered -> try {
                fs.atomicMove(env.outputPath, "$out.mp4")
                RenderState.Done("$out.mp4", r.wallMs, compiled.durationMs, r.maxRssKb)
            } catch (e: Exception) {
                RenderState.Failed("cannot save $out.mp4: ${e.message}")
            }
        }
    }

    /** Family → libass size factor for every `.ttf` / `.otf` in [dir] (unreadable fonts are skipped). */
    private fun fontScales(dir: String): Map<String, Double> = runCatching {
        fs.list(dir).filter { it.lowercase().endsWith(".ttf") || it.lowercase().endsWith(".otf") }
            .mapNotNull { name -> runCatching { FontInfo.parse(fs.read(joinPath(dir, name))) }.getOrNull() }
            .associate { it.family to it.assScale }
    }.getOrDefault(emptyMap())

    private fun isAbsolute(p: String) = p.startsWith("/") || (p.length > 2 && p[1] == ':' && (p[2] == '/' || p[2] == '\\'))
}
