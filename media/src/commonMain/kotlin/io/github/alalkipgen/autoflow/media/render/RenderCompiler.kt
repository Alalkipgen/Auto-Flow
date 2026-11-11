package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.ArtifactId

/**
 * RenderGraph → one `ffmpeg -filter_complex` command (specs/render-graph.md), the only filtergraph code for desktop
 * and Android (D-23). Per clip: input seek/length (or a dark placeholder card / silence made by `color` / `anullsrc`
 * source filters in the graph — no `-f lavfi` inputs), scale/pad to the output, fps, exact
 * length; clips joined with `concat` (cuts) and `xfade` (crossfades); `.ass` overlays (libass, fonts only from
 * `fontsdir`); song with offset / fades, alone or mixed over the clips' sound; `-t` = graph length.
 * The caller writes [Compiled.Ok.ass] to [Env.assPath] before running ffmpeg and adds progress flags itself.
 */
object RenderCompiler {
    /** A resolved video artifact: the file to read and whether it has an audio stream (from the probe). */
    data class ClipMedia(val path: String, val hasAudio: Boolean)

    /**
     * Where things are: [resolve] maps a video artifact to its file, [songPath] is the song file ([AudioTrack.file]
     * resolved), [assPath] / [fontsDir] feed `ass=`, [assScale] is [FontInfo.assScale] per family, and
     * [placeholderFont] draws the "#NN — pending" cards (none = plain dark card).
     */
    data class Env(
        val resolve: (ArtifactId) -> ClipMedia?,
        val songPath: String?,
        val assPath: String,
        val fontsDir: String,
        val outputPath: String,
        val assScale: (String) -> Double? = { null },
        val placeholderFont: String? = null,
    )

    sealed interface Compiled {
        /** [args] start after the binary name; [ass] is null when nothing is drawn. */
        data class Ok(val args: List<String>, val ass: String?, val durationMs: Long) : Compiled

        data class Invalid(val reason: String) : Compiled
    }

    const val PLACEHOLDER_COLOR = "0x1A1A1A"
    private const val AUDIO_FORMAT = "aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo"

    fun compile(graph: RenderGraph, env: Env): Compiled {
        invalid(graph, env)?.let { return Compiled.Invalid(it) }
        val out = graph.output
        val total = graph.durationMs
        val audio = graph.audio
        val useClipAudio = audio == null || !audio.muteClips
        val inputs = mutableListOf<String>()
        val chains = mutableListOf<String>()
        var index = 0
        fun input(vararg args: String): Int { inputs += args; return index++ }

        val starts = mutableListOf<Long>()
        graph.clips.forEachIndexed { k, clip ->
            val d = sec(clip.durationMs)
            val media = clip.artifact?.let(env.resolve)
            val v = media?.let { input(*(if (clip.trimInMs > 0) arrayOf("-ss", sec(clip.trimInMs)) else emptyArray()), "-t", d, "-i", it.path) }
            // Generated sources are filters inside the graph, never `-f lavfi` inputs: the Android build has no input devices.
            val videoSource = if (v != null) "[$v:v]" else "color=c=$PLACEHOLDER_COLOR:s=${out.width}x${out.height}:r=${out.fps}:d=$d,"
            chains += "${videoSource}scale=${out.width}:${out.height}:force_original_aspect_ratio=decrease," +
                "pad=${out.width}:${out.height}:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p," +
                // fps last: setpts clears the constant frame rate that xfade requires.
                "tpad=stop_mode=clone:stop_duration=$d,trim=duration=$d,setpts=PTS-STARTPTS,fps=${out.fps}[v$k]"
            if (useClipAudio) {
                val audioSource = if (media?.hasAudio == true) "[$v:a]" else "anullsrc=r=48000:cl=stereo:d=$d,"
                chains += "$audioSource$AUDIO_FORMAT,apad=whole_dur=$d,atrim=duration=$d,asetpts=PTS-STARTPTS[a$k]"
            }
            starts += if (k == 0) 0 else starts.last() + graph.clips[k - 1].durationMs - clip.crossfadeMs
        }
        chains += join(graph, "v", "vseq") { x, off -> "xfade=transition=fade:duration=${sec(x)}:offset=${sec(off)}" }
        if (useClipAudio) chains += join(graph, "a", "aseq") { x, _ -> "acrossfade=d=${sec(x)}" }

        val labels = env.placeholderFont?.let { font ->
            graph.clips.withIndex().filter { it.value.artifact == null && it.value.label.isNotBlank() }.map { (k, c) ->
                Overlay(c.label, starts[k], starts[k] + c.durationMs, font, out.height / 24, "#B0FFFFFF", Pos.CENTER)
            }
        }.orEmpty()
        val drawn = graph.overlays + labels
        val ass = if (drawn.isEmpty()) null else AssWriter.write(drawn, out, env.assScale)
        chains += if (ass == null) "[vseq]null[vout]" else "[vseq]ass=filename=${filterPath(env.assPath)}:fontsdir=${filterPath(env.fontsDir)}[vout]"

        val song = audio?.let { a ->
            val s = input(*(if (a.offsetMs < 0) arrayOf("-ss", sec(-a.offsetMs)) else emptyArray()), "-i", env.songPath ?: "")
            val delay = if (a.offsetMs > 0) ",adelay=${a.offsetMs}|${a.offsetMs}" else ""
            val fadeIn = if (a.fadeInMs > 0) ",afade=t=in:st=${sec(maxOf(0, a.offsetMs))}:d=${sec(a.fadeInMs.toLong())}" else ""
            val fadeOut = if (a.fadeOutMs > 0) ",afade=t=out:st=${sec(maxOf(0, total - a.fadeOutMs))}:d=${sec(a.fadeOutMs.toLong())}" else ""
            "[$s:a]$AUDIO_FORMAT$delay$fadeIn$fadeOut,apad=whole_dur=${sec(total)},atrim=duration=${sec(total)}"
        }
        chains += when {
            song == null -> "[aseq]anull[aout]"
            !useClipAudio -> "$song[aout]"
            else -> "$song[song];[aseq]volume=${audio.clipsVolume}[clips];[clips][song]amix=inputs=2:duration=first:normalize=0[aout]"
        }

        val q = out.quality
        val args = buildList {
            addAll(listOf("-hide_banner", "-y", "-nostdin"))
            addAll(inputs)
            addAll(listOf("-filter_complex", chains.joinToString(";"), "-map", "[vout]", "-map", "[aout]"))
            addAll(listOf("-c:v", "libx264", "-preset", q.x264Preset, "-crf", "${q.crf}", "-pix_fmt", "yuv420p", "-r", "${out.fps}"))
            addAll(listOf("-c:a", "aac", "-b:a", q.audioBitrate, "-ar", "48000", "-ac", "2"))
            addAll(listOf("-t", sec(total), "-movflags", "+faststart", env.outputPath))
        }
        return Compiled.Ok(args, ass, total)
    }

    /** `[p0][p1]concat…` for cuts only, else a pairwise chain (`xfade` / `acrossfade` at crossfades, `concat` at cuts). */
    private fun join(graph: RenderGraph, p: String, outLabel: String, fade: (Long, Long) -> String): String {
        val clips = graph.clips
        val av = if (p == "v") "v=1:a=0" else "v=0:a=1"
        if (clips.size == 1) return "[${p}0]${if (p == "v") "null" else "anull"}[$outLabel]"
        if (clips.all { it.crossfadeMs == 0L }) return clips.indices.joinToString("") { "[$p$it]" } + "concat=n=${clips.size}:$av[$outLabel]"
        val steps = mutableListOf<String>()
        var acc = "${p}0"
        var accMs = clips[0].durationMs
        for (k in 1 until clips.size) {
            val next = if (k == clips.lastIndex) outLabel else "${p}j$k"
            val x = clips[k].crossfadeMs
            // concat drops the constant frame rate that a following xfade requires, so video cuts re-state it.
            val cut = if (p == "v") "concat=n=2:$av,fps=${graph.output.fps}" else "concat=n=2:$av"
            steps += "[$acc][$p$k]" + (if (x > 0) fade(x, accMs - x) else cut) + "[$next]"
            accMs += clips[k].durationMs - x
            acc = next
        }
        return steps.joinToString(";")
    }

    private fun invalid(graph: RenderGraph, env: Env): String? {
        if (graph.clips.isEmpty()) return "the timeline has no clips"
        val frameMs = 1000L / graph.output.fps
        graph.clips.forEachIndexed { k, c ->
            val n = "clip ${k + 1}"
            if (c.durationMs < frameMs) return "$n is shorter than one frame"
            if (c.artifact != null && env.resolve(c.artifact) == null) return "$n: the video ${c.artifact} is missing"
            if (c.transition == Transition.CROSSFADE && k > 0) {
                if (c.transitionMs <= 0) return "$n: the crossfade needs a length"
                if (c.crossfadeMs >= minOf(c.durationMs, graph.clips[k - 1].durationMs)) return "$n: the crossfade is longer than a clip"
            }
        }
        graph.overlays.forEachIndexed { k, o -> if (o.endMs <= o.startMs) return "overlay ${k + 1} ends before it starts" }
        if (graph.audio != null && env.songPath == null) return "the song file is missing"
        return null
    }

    /** Milliseconds as ffmpeg seconds: `8.000`. */
    fun sec(ms: Long): String = "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"

    /**
     * A path as a filter option value inside `-filter_complex`: `\` → `/`, then escaped for the option parser
     * (`\ ' :`) and again for the graph parser (`\ ' [ ] , ;`), so `C:/x` and `a,b's` survive both levels.
     */
    fun filterPath(path: String): String {
        val option = path.replace('\\', '/').fold(StringBuilder()) { b, c -> if (c in "\\':") b.append('\\'); b.append(c) }
        return option.fold(StringBuilder()) { b, c -> if (c in "\\'[],;") b.append('\\'); b.append(c) }.toString()
    }
}
