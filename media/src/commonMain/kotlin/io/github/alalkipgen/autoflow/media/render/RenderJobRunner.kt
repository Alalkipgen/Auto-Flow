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
    /** Compiled [graph] of [job] with its [env]; or why it cannot be compiled. */
    private sealed interface Prepared {
        data class Ready(val env: RenderCompiler.Env, val compiled: RenderCompiler.Compiled.Ok) : Prepared

        data class Failed(val state: RenderState.Failed) : Prepared
    }

    /**
     * "Exact frame" (D-42, [ExactFrame]): the still at [atMs] of [job]'s output, written to [image] (relative to the
     * project folder or absolute; `.png` or `.jpg`). Only the clip under [atMs] is decoded. A failure never leaves a
     * half-written image: ffmpeg writes `<name>.part.<ext>` first.
     */
    suspend fun frame(job: RenderJob, atMs: Long, image: String): RenderState {
        val abs = { p: String -> if (isAbsolute(p)) p else joinPath(job.workDir, p) }
        val window = ExactFrame.window(job.graph, atMs)
        val target = abs(image)
        val ext = target.substringAfterLast('.', "png")
        val part = target.removeSuffix(".$ext") + ".part.$ext"
        val (env, compiled) = when (val p = prepare(job, window.graph, assPath = "$target.ass", outputPath = part)) {
            is Prepared.Failed -> return p.state
            is Prepared.Ready -> p.env to p.compiled
        }
        try {
            fs.createDirectories(target.substringBeforeLast('/', "."))
            compiled.ass?.let { fs.writeDurably(env.assPath, it.encodeToByteArray()) }
        } catch (e: Exception) {
            return RenderState.Failed("cannot write to ${target.substringBeforeLast('/')}: ${e.message}")
        }
        val args = ExactFrame.stillArgs(compiled.args, window.seekMs, part)
        return when (val r = ffmpeg.render(args, job.workDir, compiled.durationMs)) {
            is FfmpegRunner.Outcome.CannotStart -> RenderState.Failed("ffmpeg cannot start: ${r.message}")
            is FfmpegRunner.Outcome.Failed ->
                RenderState.Failed(if (r.timedOut) "ffmpeg took too long and was stopped" else "ffmpeg failed (exit ${r.exitCode})", r.tail)
            is FfmpegRunner.Outcome.Rendered -> try {
                if (!fs.exists(part)) return RenderState.Failed("ffmpeg wrote no frame at ${RenderCompiler.sec(atMs)} s")
                fs.atomicMove(part, target)
                RenderState.Done(target, r.wallMs, compiled.durationMs, r.maxRssKb)
            } catch (e: Exception) {
                RenderState.Failed("cannot save $target: ${e.message}")
            }
        }
    }

    /** Probes the clips (for sound) and the sources, reads the font factors and compiles [graph]. */
    private suspend fun prepare(job: RenderJob, graph: RenderGraph, assPath: String, outputPath: String): Prepared {
        val abs = { p: String -> if (isAbsolute(p)) p else joinPath(job.workDir, p) }
        val clipSound = graph.voice?.let { it.clipsVolume > 0 } ?: (graph.audio == null || !graph.audio.muteClips)
        val media = mutableMapOf<ArtifactId, RenderCompiler.ClipMedia>()
        for (id in graph.clips.mapNotNull { it.artifact }.distinct()) {
            val path = job.files[id.value]?.let(abs) ?: continue
            val hasAudio = if (clipSound) {
                when (val r = probe.probe(path, job.workDir)) {
                    is MediaProbe.Result.Ok -> r.info.hasAudio
                    is MediaProbe.Result.Failed -> return Prepared.Failed(RenderState.Failed("cannot read the video $id: ${r.reason}"))
                }
            } else {
                false
            }
            media[id] = RenderCompiler.ClipMedia(path, hasAudio)
        }
        val sources = mutableMapOf<String, RenderCompiler.ClipMedia>()
        for (src in graph.clips.filter { it.artifact == null }.mapNotNull { it.source }.distinct()) {
            val path = abs(src)
            if (!fs.exists(path)) continue
            val hasAudio = if (clipSound) {
                when (val r = probe.probe(path, job.workDir)) {
                    is MediaProbe.Result.Ok -> r.info.hasAudio
                    is MediaProbe.Result.Failed -> return Prepared.Failed(RenderState.Failed("cannot read $src: ${r.reason}"))
                }
            } else {
                false
            }
            sources[src] = RenderCompiler.ClipMedia(path, hasAudio)
        }
        val fontsDir = abs(job.fontsDir)
        val scales = fontScales(fontsDir)
        val env = RenderCompiler.Env(
            resolve = { media[it] },
            songPath = job.songPath?.let(abs),
            assPath = assPath,
            fontsDir = fontsDir,
            outputPath = outputPath,
            assScale = { scales[it] },
            placeholderFont = job.placeholderFont,
            resolveSource = { sources[it] },
            voicePath = { f -> abs(f).takeIf { fs.exists(it) } },
            backgroundImageFile = graph.backgroundImageFile?.let { abs(it).takeIf { p -> fs.exists(p) } },
            // Watermark and other image layers: resolve relative paths against workDir (D-52).
            resolveLayerImage = { imageId -> abs(imageId).takeIf { p -> fs.exists(p) } },
        )
        return when (val c = RenderCompiler.compile(graph, env)) {
            is RenderCompiler.Compiled.Invalid -> Prepared.Failed(RenderState.Failed(c.reason))
            is RenderCompiler.Compiled.Ok -> Prepared.Ready(env, c)
        }
    }

    suspend fun run(job: RenderJob, onProgress: (FfmpegProgress.Progress) -> Unit = {}): RenderState {
        val abs = { p: String -> if (isAbsolute(p)) p else joinPath(job.workDir, p) }
        val graph = job.graph
        val out = abs(job.outputName)
        val (env, compiled) = when (val p = prepare(job, graph, assPath = "$out.ass", outputPath = "$out.part.mp4")) {
            is Prepared.Failed -> return p.state
            is Prepared.Ready -> p.env to p.compiled
        }
        try {
            fs.createDirectories(out.substringBeforeLast('/', "."))
            compiled.ass?.let { fs.writeDurably(env.assPath, it.encodeToByteArray()) }
            fs.writeDurably("$out.rendergraph.json", CoreJson.encodeToString(RenderGraph.serializer(), graph).encodeToByteArray())
        } catch (e: Exception) {
            return RenderState.Failed("cannot write to ${out.substringBeforeLast('/')}: ${e.message}")
        }
        // 5B-4 two-pass loudnorm (D-51): when loudness is requested, run a fast measure pass first (no video encode,
        // audio only, stats_mode=rms), parse the JSON from stderr, then compile again with the accurate numbers.
        val args = if (graph.voice?.loudnessLufs != null && compiled is RenderCompiler.Compiled.Ok) {
            // Build pass-1 args: same inputs as the full render but write to /dev/null with audio-only loudnorm measure.
            // Start from compiled.args, strip the output path, add -vn, replace the last -af with one that appends
            // the loudnorm measure filter.
            val measureArgs = buildList {
                val full = compiled.args
                var i = 0
                while (i < full.size) {
                    when {
                        full[i] == "-af" && i + 1 < full.size -> {
                            add("-af"); add("${full[i + 1]},${io.github.alalkipgen.autoflow.media.audio.AudioFilters.loudnormMeasure(graph.voice!!.loudnessLufs!!)}")
                            i += 2
                        }
                        // Skip the output path (last non-flag arg)
                        i == full.lastIndex && !full[i].startsWith("-") -> { add("-vn"); add("-f"); add("null"); add("-"); i++ }
                        else -> { add(full[i]); i++ }
                    }
                }
            }
            // Run measure pass: collect stderr for JSON
            val measureStderr = ffmpeg.measure(measureArgs, job.workDir, compiled.durationMs)
            val measurement = io.github.alalkipgen.autoflow.media.audio.AudioFilters.parseMeasurement(measureStderr)
            if (measurement != null) {
                // Recompile with two-pass values: replace the loudnormSinglePass filter with loudnormApply
                val twoPassEnv = env.copy()
                when (val c2 = RenderCompiler.compile(graph.copy(
                    voice = graph.voice!!.copy(loudnessLufs = null) // let the compiler skip single-pass
                ), twoPassEnv)) {
                    is RenderCompiler.Compiled.Ok -> {
                        // Append loudnormApply to the final audio filter
                        val args2 = c2.args.toMutableList()
                        val afIdx2 = args2.indexOfLast { it == "-af" }
                        if (afIdx2 >= 0 && afIdx2 + 1 < args2.size) {
                            args2[afIdx2 + 1] = "${args2[afIdx2 + 1]},${io.github.alalkipgen.autoflow.media.audio.AudioFilters.loudnormApply(measurement, graph.voice!!.loudnessLufs!!)},aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo"
                        }
                        args2
                    }
                    else -> compiled.args
                }
            } else compiled.args
        } else compiled.args

        return when (val r = ffmpeg.render(args, job.workDir, compiled.durationMs, onProgress = onProgress)) {
            is FfmpegRunner.Outcome.CannotStart -> RenderState.Failed("ffmpeg cannot start: ${r.message}")
            is FfmpegRunner.Outcome.Failed ->
                RenderState.Failed(if (r.timedOut) "ffmpeg took too long and was stopped" else "ffmpeg failed (exit ${r.exitCode})", r.tail)
            is FfmpegRunner.Outcome.Rendered -> try {
                fs.atomicMove(env.outputPath, "$out.mp4")
                job.srt?.let { fs.writeDurably("$out.srt", it.encodeToByteArray()) }
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
