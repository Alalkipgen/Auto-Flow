package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.CaptionStyle
import io.github.alalkipgen.autoflow.core.recap.FitRow
import io.github.alalkipgen.autoflow.core.recap.Layer
import io.github.alalkipgen.autoflow.core.recap.LayerParams
import io.github.alalkipgen.autoflow.core.recap.LayerType
import io.github.alalkipgen.autoflow.core.recap.Look
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapFit
import io.github.alalkipgen.autoflow.core.recap.RecapRules
import io.github.alalkipgen.autoflow.core.recap.RecapScript
import io.github.alalkipgen.autoflow.core.recap.RecapVisuals
import io.github.alalkipgen.autoflow.core.recap.Srt
import io.github.alalkipgen.autoflow.core.recap.SrtCue
import io.github.alalkipgen.autoflow.core.recap.VoiceTake

/**
 * Movie Recap → [RenderGraph] / [RenderJob] (docs/specs/recap.md §6, D-33): one movie clip per segment (length from
 * [RecapFit]: the scene, or the take + 0.4 s running on past the scene), each current take 0.2 s into its clip, the
 * movie's sound at `movieVolume`, the looks, and the captions (each voiced segment's text over its take in
 * two-line chunks, burned in when `burnCaptions` and a font is set; always as `.srt`), and the sound settings
 * (`recap.audio`, 5B-4): voice polish, background music [bgmFile] with ducking, loudness.
 */
object RecapRender {
    /** A segment's take resolved to its file (relative to the project folder or absolute). */
    data class Voiced(val take: VoiceTake, val file: String)

    fun graph(
        recap: Recap,
        script: RecapScript,
        ratio: Ratio,
        quality: Quality,
        takeFile: (VoiceTake) -> String?,
        font: String?,
        bgmFile: String? = null,
    ): RenderGraph {
        val movie = requireNotNull(recap.movie) { "no movie" }
        val output = outputFor(recap, ratio, quality)
        val layout = layout(recap, script, takeFile)
        // 5B-6 SmartFit: pass slowFactor to Clip (SLOW_VIDEO) and speedFactor to VoiceCue (SPEED).
        val clips = layout.map { p ->
            Clip(artifact = null, source = movie.file, trimInMs = p.movieMs, trimOutMs = p.movieMs + p.row.clipMs,
                slowFactor = p.row.videoSlow)
        }
        val cues = layout.mapNotNull { p ->
            p.voiced?.let { VoiceCue(it.file, p.startMs + RecapFit.LEAD_MS, speedFactor = p.row.voiceSpeed) }
        }
        val onMask = captionY(recap, output)
        val overlays = if (font == null || !recap.burnCaptions) emptyList() else captions(recap, script, takeFile, output).map { c ->
            Overlay(
                c.text, c.startMs, c.endMs, font, captionSizePx(output, recap.captionStyle),
                color = recap.captionStyle.colour, centerY = onMask, box = recap.captionStyle.box,
            )
        }
        // Brand Kit watermark layer (5B-3, D-52): bottom-right corner, 20% wide, semi-transparent.
        val watermarkLayers = recap.visual.watermarkFile?.let { wf ->
            listOf(
                Layer(
                    id = "watermark",
                    type = LayerType.IMAGE,
                    rect = listOf(0.76f, 0.82f, 0.22f, 0.14f),
                    fromMs = 0,
                    toMs = null,
                    params = LayerParams(image = wf, opacity = 0.75f),
                )
            )
        }.orEmpty()

        return RenderGraph(
            clips = clips,
            overlays = overlays,
            output = output,
            voice = VoiceTrack(
                cues,
                clipsVolume = recap.movieVolume,
                polish = recap.audio.polish,
                music = bgmFile?.let { MusicBed(it, recap.audio.bgmVolume, recap.audio.duck) },
                loudnessLufs = recap.audio.loudnessLufs.takeIf { recap.audio.loudness },
            ),
            looks = recap.looks,
            layers = watermarkLayers,
            masks = recap.visual.masks,
            background = recap.visual.background,
            backgroundColour = recap.visual.backgroundColour,
            backgroundImageFile = recap.visual.backgroundImageFile,
        )
    }

    /**
     * Captions on the lowest blur box (5B-2): its centre in the output (0–1 of the height), or null for the usual
     * bottom place (no such box, turned off, or the box is cropped away by Fill frame).
     */
    fun captionY(recap: Recap, output: Output): Float? {
        val mask = recap.visual.captionMask ?: return null
        val movie = recap.movie ?: return null
        val y = RecapVisuals.outputY(
            mask.centerY, movie.width, movie.height, output.width, output.height,
            zoom = Look.ZOOM in recap.looks, fill = Look.FILL in recap.looks,
        )
        return y.takeIf { it in 0.05f..0.95f }
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
        bgmFile: String? = null,
    ): RenderJob = RenderJob(
        title = title,
        graph = graph(recap, script, ratio, quality, takeFile, font, bgmFile),
        workDir = projectDir,
        files = emptyMap(),
        fontsDir = fontsDir,
        outputName = outputName,
        srt = Srt.write(captions(recap, script, takeFile, outputFor(recap, ratio, quality))),
    )

    /**
     * Captions in output time: each voiced segment's text over its take, cut by [CaptionChunks] into chunks of at most
     * two lines that fit [output]'s width, one after the other — the same chunks are burned in and written to `.srt`.
     */
    fun captions(recap: Recap, script: RecapScript, takeFile: (VoiceTake) -> String?, output: Output): List<SrtCue> =
        CaptionChunks.split(segmentCaptions(recap, script, takeFile), lineChars(output, recap.captionStyle))

    /** Characters per caption line: fewer for large captions, more for small ones (same chunks burned and in `.srt`). */
    fun lineChars(output: Output, style: CaptionStyle): Int =
        maxOf(8, kotlin.math.round(ProjectRender.lineChars(output) / style.size.factor).toInt())

    /** One cue per voiced segment: its whole text from its take start to its take end. */
    fun segmentCaptions(recap: Recap, script: RecapScript, takeFile: (VoiceTake) -> String?): List<SrtCue> =
        layout(recap, script, takeFile).mapNotNull { p ->
            val text = script.segments[p.row.segment].textMy.trim()
            val at = p.startMs + RecapFit.LEAD_MS
            p.voiced?.takeIf { text.isNotEmpty() }?.let { SrtCue(at, at + it.take.durationMs, text) }
        }

    /** Preview = 540p; Export = the project's [Recap.quality] (Auto = the movie's short side, at most 1080). */
    fun outputFor(recap: Recap, ratio: Ratio, quality: Quality): Output =
        if (quality == Quality.PREVIEW) RenderPresets.preview(ratio)
        else RenderPresets.exportAt(ratio, recap.quality.shortSidePx(recap.movie))

    /** Caption em size: 1/18 of the short side (60 px at 1080), a little smaller than lyrics. */
    fun captionSizePx(output: Output): Int = minOf(output.width, output.height) / 18

    /** [captionSizePx] × the caption style's size (Small 0.85 · Medium 1 · Large 1.2). */
    fun captionSizePx(output: Output, style: CaptionStyle): Int = kotlin.math.round(captionSizePx(output) * style.size.factor).toInt()

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
