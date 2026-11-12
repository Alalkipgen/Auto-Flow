package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.FitRow
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapFit
import io.github.alalkipgen.autoflow.core.recap.RecapRules
import io.github.alalkipgen.autoflow.core.recap.RecapScript
import io.github.alalkipgen.autoflow.core.recap.Srt
import io.github.alalkipgen.autoflow.core.recap.SrtCue
import io.github.alalkipgen.autoflow.core.recap.VoiceTake

/**
 * Movie Recap → [RenderGraph] / [RenderJob] (docs/specs/recap.md §6, D-33): one movie clip per segment (length from
 * [RecapFit]: the scene, or the take + 0.4 s running on past the scene), each current take 0.2 s into its clip, the
 * movie's sound at `movieVolume`, the looks, and the captions (each voiced segment's text over its take, burned in
 * when `burnCaptions` and a font is set; always as `.srt`).
 */
object RecapRender {
    /** A segment's take resolved to its file (relative to the project folder or absolute). */
    data class Voiced(val take: VoiceTake, val file: String)

    fun graph(recap: Recap, script: RecapScript, ratio: Ratio, quality: Quality, takeFile: (VoiceTake) -> String?, font: String?): RenderGraph {
        val movie = requireNotNull(recap.movie) { "no movie" }
        val output = RenderPresets.of(ratio, quality)
        val layout = layout(recap, script, takeFile)
        val clips = layout.map { p -> Clip(artifact = null, source = movie.file, trimInMs = p.movieMs, trimOutMs = p.movieMs + p.row.clipMs) }
        val cues = layout.mapNotNull { p -> p.voiced?.let { VoiceCue(it.file, p.startMs + RecapFit.LEAD_MS) } }
        val overlays = if (font == null || !recap.burnCaptions) emptyList() else captions(recap, script, takeFile).map { c ->
            Overlay(ProjectRender.wrap(c.text, ProjectRender.lineChars(output)), c.startMs, c.endMs, font, captionSizePx(output))
        }
        return RenderGraph(
            clips = clips,
            overlays = overlays,
            output = output,
            voice = VoiceTrack(cues, clipsVolume = recap.movieVolume),
            looks = recap.looks,
        )
    }

    fun job(
        recap: Recap,
        script: RecapScript,
        ratio: Ratio,
        projectDir: String,
        quality: Quality,
        takeFile: (VoiceTake) -> String?,
        font: String?,
        fontsDir: String,
        outputName: String,
        title: String,
    ): RenderJob = RenderJob(
        title = title,
        graph = graph(recap, script, ratio, quality, takeFile, font),
        workDir = projectDir,
        files = emptyMap(),
        fontsDir = fontsDir,
        outputName = outputName,
        srt = Srt.write(captions(recap, script, takeFile)),
    )

    /** Captions in output time: each voiced segment's text from its take start to its take end. */
    fun captions(recap: Recap, script: RecapScript, takeFile: (VoiceTake) -> String?): List<SrtCue> =
        layout(recap, script, takeFile).mapNotNull { p ->
            val text = script.segments[p.row.segment].textMy.trim()
            val at = p.startMs + RecapFit.LEAD_MS
            p.voiced?.takeIf { text.isNotEmpty() }?.let { SrtCue(at, at + it.take.durationMs, text) }
        }

    /** Caption em size: 1/18 of the short side (60 px at 1080), a little smaller than lyrics. */
    fun captionSizePx(output: Output): Int = minOf(output.width, output.height) / 18

    /** [startMs] in the output, [movieMs] where the clip starts in the movie. */
    private data class Placed(val row: FitRow, val startMs: Long, val movieMs: Long, val voiced: Voiced?)

    /** Fit rows with their output start and current take (a take whose file is missing counts as no voice). */
    private fun layout(recap: Recap, script: RecapScript, takeFile: (VoiceTake) -> String?): List<Placed> {
        var at = 0L
        return RecapFit.rows(recap, script).map { row ->
            val voiced = RecapRules.currentTake(recap, script, row.segment)?.let { t -> takeFile(t)?.let { Voiced(t, it) } }
            // A take that cannot be found plays as "No voice": the clip is the scene alone.
            val r = if (voiced == null && row.takeMs != null) row.copy(takeMs = null, clipMs = row.sceneMs) else row
            Placed(r, at, script.segments[row.segment].startMs, voiced).also { at += r.clipMs }
        }
    }
}
