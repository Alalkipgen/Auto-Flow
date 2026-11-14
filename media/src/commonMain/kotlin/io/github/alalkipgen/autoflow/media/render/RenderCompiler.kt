package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.media.audio.AudioFilters

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.recap.FrameBackground
import io.github.alalkipgen.autoflow.core.recap.Look
import io.github.alalkipgen.autoflow.core.recap.RecapMask

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
        /** [Clip.source] → its file (resolved and probed by the runner). */
        val resolveSource: (String) -> ClipMedia? = { null },
        /** [VoiceCue.file] → an existing file, else null. */
        val voicePath: (String) -> String? = { null },
        /** Layer image id → an absolute file path, or null when the artifact is missing. */
        val resolveLayerImage: (String) -> String? = { null },
        /** Background image absolute path for [FrameBackground.IMAGE] (D-51); null = not found. */
        val backgroundImageFile: String? = null,
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
        val voice = graph.voice
        val useClipAudio = if (voice != null) voice.clipsVolume > 0 else audio == null || !audio.muteClips
        val inputs = mutableListOf<String>()
        val chains = mutableListOf<String>()
        var index = 0
        fun input(vararg args: String): Int { inputs += args; return index++ }

        val starts = mutableListOf<Long>()
        graph.clips.forEachIndexed { k, clip ->
            val d = sec(clip.durationMs)
            val fromSource = clip.artifact == null && clip.source != null
            val media = clip.artifact?.let(env.resolve) ?: clip.source?.takeIf { fromSource }?.let(env.resolveSource)
            // A long movie is opened once per clip: fewer decoder threads keep the memory down on the phone.
            val threads = if (fromSource) arrayOf("-threads", "2") else emptyArray()
            val v = media?.let { input(*threads, *(if (clip.trimInMs > 0) arrayOf("-ss", sec(clip.trimInMs)) else emptyArray()), "-t", d, "-i", it.path) }
            // Generated sources are filters inside the graph, never `-f lavfi` inputs: the Android build has no input devices.
            val videoSource = if (v != null) "[$v:v]" else "color=c=$PLACEHOLDER_COLOR:s=${out.width}x${out.height}:r=${out.fps}:d=$d,"
            val looks = if (fromSource) graph.looks.toSet() else emptySet()
            // 5B-2: blur boxes on the movie picture itself (before the looks), so they follow the movie at any ratio.
            val masked = if (fromSource && v != null && graph.masks.isNotEmpty()) {
                chains += maskChains(v, k, graph.masks)
                "[mk${k}_${graph.masks.lastIndex}]"
            } else videoSource
            val pre = (if (Look.MIRROR in looks) "hflip," else "") + (if (Look.ZOOM in looks) "crop=iw*0.9:ih*0.9," else "")
            val background = if (fromSource) graph.background else FrameBackground.BARS
            val imgBg = background == FrameBackground.IMAGE && graph.backgroundImageFile != null
            if (imgBg) {
                // IMAGE background (D-51): a still image opens as a separate input, blurred and scaled to cover the
                // frame; the movie picture is fitted on top (like BLUR but the backing comes from a file, not the movie).
                val bi = input("-loop", "1", "-i", graph.backgroundImageFile!!)
                chains += "[$bi:v]scale=${out.width}:${out.height}:force_original_aspect_ratio=increase,crop=${out.width}:${out.height},gblur=sigma=4,setsar=1[imgbg$k]"
            }
            val fit = when {
                Look.FILL in looks -> "scale=${out.width}:${out.height}:force_original_aspect_ratio=increase,crop=${out.width}:${out.height},"
                imgBg -> {
                    // Scale movie to fit, then overlay on the blurred image background
                    "scale=${out.width}:${out.height}:force_original_aspect_ratio=decrease,setsar=1[mfg$k];[imgbg$k][mfg$k]overlay=(main_w-overlay_w)/2:(main_h-overlay_h)/2,"
                }
                background == FrameBackground.BLUR -> blurredBackground(k, out.width, out.height)
                else -> {
                    val colour = if (background == FrameBackground.COLOUR) ":color=${hexColour(graph.backgroundColour)}" else ""
                    "scale=${out.width}:${out.height}:force_original_aspect_ratio=decrease,pad=${out.width}:${out.height}:(ow-iw)/2:(oh-ih)/2$colour,"
                }
            }
            val warm = if (Look.WARM in looks) "eq=saturation=1.15:contrast=1.05," else ""
            // 5B-6 SmartFit SLOW_VIDEO: setpts slows the picture (factor < 1 → PTS multiplied by 1/factor).
            val slowPts = if (clip.slowFactor in 0.01f..0.999f) "setpts=${String.format("%.4f", 1f / clip.slowFactor)}*PTS," else ""
            chains += "$masked$pre${fit}setsar=1,format=yuv420p,$warm$slowPts" +
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
            graph.clips.withIndex().filter { it.value.artifact == null && it.value.source == null && it.value.label.isNotBlank() }.map { (k, c) ->
                Overlay(c.label, starts[k], starts[k] + c.durationMs, font, out.height / 24, "#B0FFFFFF", Pos.CENTER)
            }
        }.orEmpty()

        // Compile composited layers (5A-4); images for layers follow the base clips and song in the input list.
        val layerResult = LayerCompiler.compile(
            layers = graph.layers,
            output = out,
            totalMs = total,
            resolveImage = env.resolveLayerImage,
            baseInputCount = index,
        )
        layerResult.inputPaths.forEach { path -> input("-i", path) }
        chains += layerResult.chains

        // After layers, apply ASS overlays. The video label entering ASS is [vlayer] when layers exist, else [vseq].
        val preAssLabel = if (layerResult.chains.isNotEmpty()) "vlayer" else "vseq"
        val drawn = graph.overlays + labels + layerResult.assEvents
        val ass = if (drawn.isEmpty()) null else AssWriter.write(drawn, out, env.assScale)
        chains += if (ass == null) "[$preAssLabel]null[vout]" else "[$preAssLabel]ass=filename=${filterPath(env.assPath)}:fontsdir=${filterPath(env.fontsDir)}[vout]"

        val song = audio?.let { a ->
            val s = input(*(if (a.offsetMs < 0) arrayOf("-ss", sec(-a.offsetMs)) else emptyArray()), "-i", env.songPath ?: "")
            val delay = if (a.offsetMs > 0) ",adelay=${a.offsetMs}|${a.offsetMs}" else ""
            val fadeIn = if (a.fadeInMs > 0) ",afade=t=in:st=${sec(maxOf(0, a.offsetMs))}:d=${sec(a.fadeInMs.toLong())}" else ""
            val fadeOut = if (a.fadeOutMs > 0) ",afade=t=out:st=${sec(maxOf(0, total - a.fadeOutMs))}:d=${sec(a.fadeOutMs.toLong())}" else ""
            "[$s:a]$AUDIO_FORMAT$delay$fadeIn$fadeOut,apad=whole_dur=${sec(total)},atrim=duration=${sec(total)}"
        }
        // 5B-6 SmartFit SPEED: atempo filter — chain multiple atempos if factor > 2 (ffmpeg limit per filter).
        fun atempoChain(speed: Float): String {
            if (speed <= 1.01f) return ""
            val factors = mutableListOf<Float>()
            var rem = speed
            while (rem > 2.0f) { factors += 2.0f; rem /= 2.0f }
            if (rem > 1.001f) factors += rem
            return factors.joinToString(",") { f -> "atempo=${String.format("%.4f", f)}" } + ","
        }
        data class CueTuple(val inputIdx: Int, val startMs: Long, val speed: Float)
        val cues = voice?.cues.orEmpty().map { c -> CueTuple(input("-i", env.voicePath(c.file) ?: ""), c.startMs, c.speedFactor) }
        chains += when {
            voice != null && voice.music == null && voice.loudnessLufs == null && !voice.polish -> {
                val base = if (useClipAudio) "[aseq]volume=${voice.clipsVolume}[base]" else "anullsrc=r=48000:cl=stereo:d=${sec(total)},$AUDIO_FORMAT[base]"
                val delayed = cues.mapIndexed { k, (i, at, spd) -> "[${i}:a]$AUDIO_FORMAT,${atempoChain(spd)}adelay=$at|$at[c$k]" }
                (listOf(base) + delayed).joinToString(";") + ";[base]" + cues.indices.joinToString("") { "[c$it]" } +
                    "amix=inputs=${cues.size + 1}:duration=first:normalize=0,atrim=duration=${sec(total)}[aout]"
            }
            voice != null -> {
                // 5B-4: polished takes are mixed on their own ([vo]) so the music can duck under the voice only.
                val base = if (useClipAudio) "[aseq]volume=${voice.clipsVolume}[base]" else "anullsrc=r=48000:cl=stereo:d=${sec(total)},$AUDIO_FORMAT[base]"
                val polish = if (voice.polish) "," + AudioFilters.voicePolish() else ""
                val delayed = cues.mapIndexed { k, (i, at, spd) -> "[${i}:a]$AUDIO_FORMAT,${atempoChain(spd)}$polish,adelay=$at|$at[c$k]".replace(",,", ",") }
                val bed = "anullsrc=r=48000:cl=stereo:d=${sec(total)},$AUDIO_FORMAT[vbed]"
                val vo = "[vbed]" + cues.indices.joinToString("") { "[c$it]" } + "amix=inputs=${cues.size + 1}:duration=first:normalize=0[vo]"
                val music = voice.music?.let { m ->
                    val i = input("-i", env.voicePath(m.file) ?: "")
                    listOf("[$i:a]$AUDIO_FORMAT,${AudioFilters.bgm(total, m.volume)}[bgm]") + AudioFilters.mixWithMusic("vo", "bgm", "vm", m.duck)
                }.orEmpty()
                val narration = if (voice.music != null) "vm" else "vo"
                val loud = voice.loudnessLufs?.let { ",${AudioFilters.loudnormSinglePass(it)},$AUDIO_FORMAT" } ?: ""
                (listOf(base) + delayed + bed + vo + music).joinToString(";") +
                    ";[base][$narration]amix=inputs=2:duration=first:normalize=0,atrim=duration=${sec(total)}$loud[aout]"
            }
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
            if (c.artifact == null && c.source != null && env.resolveSource(c.source) == null) return "$n: the file ${c.source} is missing"
            if (c.transition == Transition.CROSSFADE && k > 0) {
                if (c.transitionMs <= 0) return "$n: the crossfade needs a length"
                if (c.crossfadeMs >= minOf(c.durationMs, graph.clips[k - 1].durationMs)) return "$n: the crossfade is longer than a clip"
            }
        }
        graph.overlays.forEachIndexed { k, o -> if (o.endMs <= o.startMs) return "overlay ${k + 1} ends before it starts" }
        if (graph.audio != null && env.songPath == null) return "the song file is missing"
        graph.voice?.let { v ->
            if (graph.audio != null) return "a song and a narration cannot be mixed in one render"
            v.cues.forEachIndexed { k, c -> if (env.voicePath(c.file) == null) return "narration ${k + 1}: the file ${c.file} is missing" }
            v.music?.let { m -> if (env.voicePath(m.file) == null) return "the background music file ${m.file} is missing" }
        }
        return null
    }

    /**
     * One blur box after another on input [input]'s picture (clip [k]): split, crop the box (0–1 of the picture),
     * `gblur`, overlay it back. Ends in `[mk<k>_<last>]`.
     */
    private fun maskChains(input: Int, k: Int, masks: List<RecapMask>): String {
        var cur = "$input:v"
        return masks.mapIndexed { j, m ->
            val (x, y, w, h) = (0..3).map { i -> f3(m.rect.getOrElse(i) { if (i < 2) 0f else 1f }) }
            val l = "mk${k}_$j"
            "[$cur]split[${l}a][${l}b];[${l}b]crop=w=iw*$w:h=ih*$h:x=iw*$x:y=ih*$y,gblur=sigma=${m.strength.sigma}[${l}c];" +
                "[${l}a][${l}c]overlay=x=main_w*$x:y=main_h*$y[$l]".also { cur = l }
        }.joinToString(";")
    }

    /**
     * The picture fitted inside [w] × [h] over a blurred copy of itself that covers the frame (5B-2). The copy is
     * blurred at a quarter size (cheap on the phone) and scaled back up. Ends with a `,` so the chain continues.
     */
    private fun blurredBackground(k: Int, w: Int, h: Int): String {
        val sw = maxOf(2, w / 4 / 2 * 2)
        val sh = maxOf(2, h / 4 / 2 * 2)
        return "split[bf$k][bb$k];" +
            "[bb$k]scale=$sw:$sh:force_original_aspect_ratio=increase,crop=$sw:$sh,gblur=sigma=12,scale=$w:$h,setsar=1[bbl$k];" +
            "[bf$k]scale=$w:$h:force_original_aspect_ratio=decrease,setsar=1[bfs$k];" +
            "[bbl$k][bfs$k]overlay=(main_w-overlay_w)/2:(main_h-overlay_h)/2,"
    }

    /** `#RRGGBB` → `0xRRGGBB` (black when it is not a colour). */
    private fun hexColour(c: String): String {
        val hex = c.removePrefix("#")
        return if (hex.length == 6 && hex.all { it in "0123456789abcdefABCDEF" }) "0x${hex.uppercase()}" else "0x000000"
    }

    /** A 0–1 value with at most three decimals, never in exponent form. */
    private fun f3(v: Float): String {
        val r = kotlin.math.round(v.coerceIn(0f, 1f) * 1000).toInt()
        return if (r % 1000 == 0) "${r / 1000}" else "${r / 1000}.${(r % 1000).toString().padStart(3, '0').trimEnd('0')}"
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
