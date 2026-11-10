package io.github.alalkipgen.autoflow.media.selftest

/**
 * The three S6 renders (PLAN S6, D-23) as ffmpeg argument lists — the filtergraph shapes of specs/render-graph.md
 * (scale/pad per clip, concat, `ass=` with `fontsdir`, music mixed with `amix`, `-shortest`, preview/export presets).
 * Stage 2 replaces these with the shared RenderGraph compiler; until then they only serve the on-device check.
 */
object SelfTestRenders {
    data class Inputs(val clip1: String, val clip2: String, val song: String, val ass: String, val fontsDir: String, val outDir: String)

    data class Render(val id: String, val title: String, val output: String, val width: Int, val height: Int, val args: List<String>)

    const val FRAME_AT_S = 4
    const val COUNT = 3

    fun all(i: Inputs): List<Render> = listOf(
        render(i, "r1-concat-540p", "Concat 2 clips → 540p (preview preset)", 540, 960, ass = false, music = false, export = false),
        render(i, "r2-ass-540p", "+ Burmese .ass overlay (libass) → 540p", 540, 960, ass = true, music = false, export = false),
        render(i, "r3-export-1080p", "+ mp3 mix → 1080p (export preset)", 1080, 1920, ass = true, music = true, export = true),
    )

    /** One PNG frame at [FRAME_AT_S] s of [video]. */
    fun frameArgs(video: String, png: String) = listOf("-hide_banner", "-y", "-ss", "$FRAME_AT_S", "-i", video, "-frames:v", "1", png)

    /** `ffprobe` summary of an output: duration, codecs, size. */
    fun probeArgs(video: String) = listOf(
        "-v", "error", "-show_entries", "format=duration,size:stream=codec_type,codec_name,width,height", "-of", "compact=p=0:nk=0", video,
    )

    /** [probeArgs] output (`codec_name=h264|codec_type=video|width=540|height=960` lines) as "h264 540x960 + aac, 16.0 s". */
    fun summarizeProbe(out: String): String {
        val rows = out.lineSequence().filter { it.isNotBlank() }.map { line ->
            line.split('|').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        }.toList()
        val streams = rows.filter { "codec_type" in it }.joinToString(" + ") { r ->
            val size = if (r["width"] != null && r["height"] != null) " ${r["width"]}x${r["height"]}" else ""
            "${r["codec_name"] ?: "?"}$size"
        }
        val duration = rows.firstNotNullOfOrNull { it["duration"]?.toDoubleOrNull() }
        val d = duration?.let { val t = (it * 10).toLong(); "${t / 10}.${t % 10} s" }
        return listOfNotNull(streams.ifEmpty { null }, d).joinToString(", ").ifEmpty { out.trim().take(80) }
    }

    private fun render(i: Inputs, id: String, title: String, w: Int, h: Int, ass: Boolean, music: Boolean, export: Boolean): Render {
        val out = "${i.outDir}/$id.mp4"
        val fit = "scale=$w:$h:force_original_aspect_ratio=decrease,pad=$w:$h:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=24,format=yuv420p"
        val graph = buildList {
            add("[0:v]$fit[v0]")
            add("[1:v]$fit[v1]")
            add("[v0][0:a][v1][1:a]concat=n=2:v=1:a=1[vc][ac]")
            add(if (ass) "[vc]ass=filename='${i.ass}':fontsdir='${i.fontsDir}'[v]" else "[vc]null[v]")
            add(
                if (music) "[2:a]afade=t=in:d=0.5,afade=t=out:st=15:d=1[m];[ac]volume=0.3[ca];[ca][m]amix=inputs=2:duration=first:normalize=0[a]"
                else "[ac]anull[a]",
            )
        }.joinToString(";")
        val video = if (export) listOf("-c:v", "libx264", "-preset", "slow", "-crf", "18") else listOf("-c:v", "libx264", "-preset", "ultrafast", "-crf", "23")
        val audio = listOf("-c:a", "aac", "-b:a", if (export) "192k" else "128k")
        val args = buildList {
            addAll(listOf("-hide_banner", "-y", "-benchmark", "-nostdin", "-i", i.clip1, "-i", i.clip2))
            if (music) addAll(listOf("-i", i.song))
            addAll(listOf("-filter_complex", graph, "-map", "[v]", "-map", "[a]"))
            addAll(video)
            addAll(audio)
            addAll(listOf("-shortest", "-movflags", "+faststart", out))
        }
        return Render(id, title, out, w, h, args)
    }
}
