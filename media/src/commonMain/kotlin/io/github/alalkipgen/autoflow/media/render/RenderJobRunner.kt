package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.media.audio.AudioFilters
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
            compiled.layerAssFiles.forEach { (path, text) -> fs.writeDurably(path, text.encodeToByteArray()) }
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
            // Layer images (audit F08): an artifact id (`im_…`) through the job's files, else a project path such as
            // the Brand Kit watermark (D-52); null when the file is not there, which fails the compile.
            resolveLayerImage = { image -> abs(job.files[image] ?: image).takeIf { p -> fs.exists(p) } },
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
        val (env, compiled) = when (val p = prepare(job, graph, assPath = "$out.ass", outputPath = partPath(job))) {
            is Prepared.Failed -> return p.state
            is Prepared.Ready -> p.env to p.compiled
        }
        try {
            fs.createDirectories(out.substringBeforeLast('/', "."))
            compiled.ass?.let { fs.writeDurably(env.assPath, it.encodeToByteArray()) }
            compiled.layerAssFiles.forEach { (path, text) -> fs.writeDurably(path, text.encodeToByteArray()) }
            fs.writeDurably("$out.rendergraph.json", CoreJson.encodeToString(RenderGraph.serializer(), graph).encodeToByteArray())
        } catch (e: Exception) {
            return RenderState.Failed("cannot write to ${out.substringBeforeLast('/')}: ${e.message}")
        }
        // 5B-4 two-pass loudness (D-51, audit F09): pass 1 runs only the sound chains with a measuring `loudnorm`
        // (no picture, nothing written); its JSON numbers go into pass 2 — the real render — as measured_I / TP /
        // LRA / thresh / offset. Numbers missing or not finite (silence, a failed pass) → the one-pass `loudnorm`
        // the graph was compiled with, and the result says so ([RenderState.Loudness]).
        var loudness: RenderState.Loudness? = null
        val args = graph.voice?.loudnessLufs?.let { lufs ->
            val measureArgs = compiled.measureArgs ?: return@let null
            val measured = AudioFilters.parseMeasurement(ffmpeg.measure(measureArgs, job.workDir, compiled.durationMs))
            if (measured == null) {
                loudness = RenderState.Loudness(lufs, null, "the loudness could not be measured, so one-pass loudnorm was used")
                return@let null
            }
            when (val c2 = RenderCompiler.compile(graph, env, measured)) {
                is RenderCompiler.Compiled.Ok -> c2.args.also { loudness = RenderState.Loudness(lufs, measured, null) }
                is RenderCompiler.Compiled.Invalid -> return RenderState.Failed(c2.reason)
            }
        } ?: compiled.args

        return when (val r = ffmpeg.render(args, job.workDir, compiled.durationMs, onProgress = onProgress)) {
            is FfmpegRunner.Outcome.CannotStart -> RenderState.Failed("ffmpeg cannot start: ${r.message}")
            is FfmpegRunner.Outcome.Failed ->
                RenderState.Failed(if (r.timedOut) "ffmpeg took too long and was stopped" else "ffmpeg failed (exit ${r.exitCode})", r.tail)
            is FfmpegRunner.Outcome.Rendered -> try {
                fs.atomicMove(env.outputPath, "$out.mp4")
                job.srt?.let { fs.writeDurably("$out.srt", it.encodeToByteArray()) }
                RenderState.Done("$out.mp4", r.wallMs, compiled.durationMs, r.maxRssKb, loudness).also { done ->
                    // 5B-4: how this render ended, beside the mp4 for the Export screen after a restart. Best effort:
                    // the video is already saved, and a missing or stale record is simply not shown (RenderResult.read).
                    runCatching { fs.writeDurably(RenderResult.path(done.output), RenderResult.of(done, fs.size(done.output)).encode()) }
                }
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

    companion object {
        /**
         * Where [run] writes [job]'s video before the rename: `<workDir>/<outputName>.part.mp4`. Owned by that job
         * alone, so the queue deletes it after a cancel or a failure (audit F16) — never the finished `<name>.mp4`.
         */
        fun partPath(job: RenderJob): String =
            job.outputName.let { if (isAbsolute(it)) it else joinPath(job.workDir, it) } + ".part.mp4"

        private fun isAbsolute(p: String) = p.startsWith("/") || (p.length > 2 && p[1] == ':' && (p[2] == '/' || p[2] == '\\'))
    }
}
