package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.BlockTake
import io.github.alalkipgen.autoflow.core.recap.CaptionStyle
import io.github.alalkipgen.autoflow.core.recap.FitRow
import io.github.alalkipgen.autoflow.core.recap.Layer
import io.github.alalkipgen.autoflow.core.recap.LayerParams
import io.github.alalkipgen.autoflow.core.recap.LayerType
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.Look
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapBlock
import io.github.alalkipgen.autoflow.core.recap.RecapClip
import io.github.alalkipgen.autoflow.core.recap.RecapFit
import io.github.alalkipgen.autoflow.core.recap.RecapRulesV2
import io.github.alalkipgen.autoflow.core.recap.RecapScript
import io.github.alalkipgen.autoflow.core.recap.RecapStory
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.RecapVisuals
import io.github.alalkipgen.autoflow.core.recap.Srt
import io.github.alalkipgen.autoflow.core.recap.SrtCue
import io.github.alalkipgen.autoflow.core.recap.VoiceTake
import io.github.alalkipgen.autoflow.core.recap.toV2

/**
 * Movie Recap → [RenderGraph] / [RenderJob] (docs/specs/recap.md §6, studio-v2 §2, D-33), from the v2 project data
 * (audit F07): per story block, every clip in order (length from [RecapFit]: the block, or the take + 0.4 s running on
 * past its last clip), the block's current take — the chosen one, else the newest, not stale — 0.2 s into the block,
 * the movie's sound at `movieVolume`, the looks, every `recap.layers` layer plus the Brand Kit watermark, and the
 * captions (each voiced block's text over its take in two-line chunks, or at the owner's chunk edges
 * `captions.chunkEdits`, F05; burned in when `captions.burn` and a font is set; always as `.srt` — the same cues), and
 * the sound settings (`recap.audio`, 5B-4): voice polish, background music [bgmFile] with ducking, loudness.
 * The v1 overloads convert with [toV2] (lossless) and use the same code.
 */
object RecapRender {
    /** A block's take resolved to its file (relative to the project folder or absolute). */
    data class Voiced(val take: BlockTake, val file: String)

    fun graph(
        recap: RecapV2,
        story: RecapStory,
        ratio: Ratio,
        quality: Quality,
        takeFile: (BlockTake) -> String?,
        font: String?,
        bgmFile: String? = null,
        lexicon: Lexicon = Lexicon.EMPTY,
    ): RenderGraph {
        val movie = requireNotNull(recap.movie) { "no movie" }
        val output = outputFor(recap, ratio, quality)
        val layout = layout(recap, story, takeFile, lexicon)
        // Every clip of every block in order; 5B-6 SmartFit: slowFactor on the clips (SLOW_VIDEO), speedFactor on
        // the voice (SPEED).
        val clips = layout.flatMap { p -> p.clips.map { c -> Clip(artifact = null, source = movie.file, trimInMs = c.inMs, trimOutMs = c.outMs, slowFactor = p.row.videoSlow) } }
        val cues = layout.mapNotNull { p ->
            p.voiced?.let { VoiceCue(it.file, p.startMs + RecapFit.LEAD_MS, speedFactor = p.row.voiceSpeed) }
        }
        val overlays = if (font == null || !recap.burnCaptions) emptyList() else captions(layout, recap, story, output).map { c ->
            captionOverlay(recap, output, c, font)
        }
        // Brand Kit watermark layer (5B-3, D-52/D-56): position driven by watermarkAnchor.
        val watermarkLayers = recap.visual.watermarkFile?.let { wf ->
            listOf(
                Layer(
                    id = "watermark",
                    type = LayerType.IMAGE,
                    rect = recap.visual.watermarkRect(),
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
            // The project's own layers (spec §4, 5A-4) in their order, the watermark on top.
            layers = recap.layers + watermarkLayers,
            masks = recap.visual.masks,
            background = recap.visual.background,
            backgroundColour = recap.visual.backgroundColour,
            backgroundImageFile = recap.visual.backgroundImageFile,
        )
    }

    /** The exact same native-v2 layout as [graph], including every clip, current take, Fit and missing files. */
    fun timeline(
        recap: RecapV2,
        story: RecapStory,
        takeFile: (BlockTake) -> String?,
        output: Output,
        lexicon: Lexicon = Lexicon.EMPTY,
    ): RecapTimeline {
        val placed = layout(recap, story, takeFile, lexicon)
        val duration = recap.movie?.durationMs ?: 0
        val clips = placed.flatMap { p ->
            var at = p.startMs
            p.clips.mapIndexed { ordinal, c ->
                RecapTimelineClip(story.blocks[p.row.segment].id, ordinal, at, at + c.durationMs,
                    c.inMs, p.row.videoSlow, duration).also { at += c.durationMs }
            }
        }
        val voice = placed.mapNotNull { p -> p.voiced?.let { VoiceCue(it.file, p.startMs + RecapFit.LEAD_MS, speedFactor = p.row.voiceSpeed) } }
        return RecapTimeline(clips, if (recap.burnCaptions) captions(placed, recap, story, output) else emptyList(), voice)
    }

    /** v1 shape: [graph] of [Recap.toV2]. */
    fun graph(
        recap: Recap,
        script: RecapScript,
        ratio: Ratio,
        quality: Quality,
        takeFile: (VoiceTake) -> String?,
        font: String?,
        bgmFile: String? = null,
    ): RenderGraph {
        val (v2, story) = recap.toV2(script)
        return graph(v2, story, ratio, quality, v1Files(recap, takeFile), font, bgmFile)
    }

    /**
     * Captions on the lowest blur box (5B-2): its centre in the output (0–1 of the height), or null for the usual
     * bottom place (no such box, turned off, or the box is cropped away by Fill frame).
     */
    fun captionY(recap: RecapV2, output: Output): Float? {
        val mask = recap.visual.captionMask ?: return null
        val movie = recap.movie ?: return null
        val y = RecapVisuals.outputY(
            mask.centerY, movie.width, movie.height, output.width, output.height,
            zoom = Look.ZOOM in recap.looks, fill = Look.FILL in recap.looks,
        )
        return y.takeIf { it in 0.05f..0.95f }
    }

    /**
     * Captions on a box are centred on it across too (a Custom box need not be in the middle, S10), after Mirror /
     * Zoom / Fill like the box; null with no caption box, as [captionY]. A full-width strip's centre is the frame's
     * centre, as before.
     */
    fun captionX(recap: RecapV2, output: Output): Float? {
        captionY(recap, output) ?: return null
        val mask = recap.visual.captionMask ?: return null
        val movie = recap.movie ?: return null
        // Mirror flips the picture after the boxes are blurred: the box (and its caption) lands on the other side.
        val x = if (Look.MIRROR in recap.looks) 1f - mask.centerX else mask.centerX
        return RecapVisuals.outputX(
            x, movie.width, movie.height, output.width, output.height,
            zoom = Look.ZOOM in recap.looks, fill = Look.FILL in recap.looks,
        ).coerceIn(0.05f, 0.95f)
    }

    /** One burned-in caption cue as the export draws it: size, colour, outline or box, on the caption box or at the bottom. */
    fun captionOverlay(recap: RecapV2, output: Output, cue: SrtCue, font: String): Overlay = Overlay(
        cue.text, cue.startMs, cue.endMs, font, captionSizePx(output, recap.captionStyle),
        color = recap.captionStyle.effectiveColour, centerY = captionY(recap, output), centerX = captionX(recap, output),
        box = recap.captionStyle.box, strokeWidth = recap.captionStyle.strokeWidth, strokeColour = recap.captionStyle.strokeColour,
    )

    /**
     * Where the export draws the captions, in the Recap preview's terms (5A-8 / S10): [x] / [y] in 0–1 of the movie
     * picture — the centre of the caption, or with [bottom] the centre of its bottom edge (the usual place, ASS `\an2`);
     * [emOfPicture] = the caption's em size over the movie-picture height. Null when the caption sits outside the
     * picture (on the bars or the background), where the preview cannot show it.
     */
    data class CaptionSpot(val x: Float, val y: Float, val bottom: Boolean, val emOfPicture: Float)

    fun captionSpot(recap: RecapV2, output: Output): CaptionSpot? {
        val movie = recap.movie ?: return null
        val zoom = Look.ZOOM in recap.looks
        val fill = Look.FILL in recap.looks
        val oy = captionY(recap, output)
        val ox = captionX(recap, output) ?: 0.5f
        val y = oy ?: (1f - AssWriter.MARGIN_PERCENT / 100f)
        val mx = RecapVisuals.movieX(ox, movie.width, movie.height, output.width, output.height, zoom, fill)
            .let { if (Look.MIRROR in recap.looks) 1f - it else it }
        val my = RecapVisuals.movieY(y, movie.width, movie.height, output.width, output.height, zoom, fill)
        if (mx !in 0f..1f || my !in 0f..1f) return null
        val em = captionSizePx(output, recap.captionStyle) / RecapVisuals.pictureHeightPx(movie.width, movie.height, output.width, output.height, zoom, fill)
        return CaptionSpot(mx, my, bottom = oy == null, emOfPicture = em)
    }

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
        recap: RecapV2,
        story: RecapStory,
        ratio: Ratio,
        projectDir: String,
        quality: Quality,
        takeFile: (BlockTake) -> String?,
        font: String?,
        fontsDir: String,
        outputName: String,
        title: String,
        bgmFile: String? = null,
        lexicon: Lexicon = Lexicon.EMPTY,
        imageFile: (String) -> String? = { null },
    ): RenderJob {
        val graph = graph(recap, story, ratio, quality, takeFile, font, bgmFile, lexicon)
        return RenderJob(
            title = title,
            graph = graph,
            workDir = projectDir,
            // Layer images by artifact id (`im_…`, spec §4) → their files; one that is not found fails the render (F08).
            files = graph.layers.mapNotNull { it.params.image }.distinct().mapNotNull { id -> imageFile(id)?.let { id to it } }.toMap(),
            fontsDir = fontsDir,
            outputName = outputName,
            srt = Srt.write(captions(recap, story, takeFile, graph.output, lexicon)),
        )
    }

    /** v1 shape: [job] of [Recap.toV2]. */
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
    ): RenderJob {
        val (v2, story) = recap.toV2(script)
        return job(v2, story, ratio, projectDir, quality, v1Files(recap, takeFile), font, fontsDir, outputName, title, bgmFile)
    }

    /**
     * Captions in output time: each voiced block's text (`textMy`; captions never show the speak-as text) over its
     * take, cut by [CaptionChunks] into chunks of at most two lines that fit [output]'s width — at the owner's chunk
     * edges when `captions.chunkEdits` has valid ones for the block (F05) — one after the other. The same cues are
     * burned in and written to `.srt`.
     */
    fun captions(recap: RecapV2, story: RecapStory, takeFile: (BlockTake) -> String?, output: Output, lexicon: Lexicon = Lexicon.EMPTY): List<SrtCue> =
        captions(layout(recap, story, takeFile, lexicon), recap, story, output)

    private fun captions(layout: List<Placed>, recap: RecapV2, story: RecapStory, output: Output): List<SrtCue> {
        val chars = lineChars(output, recap.captionStyle)
        return layout.flatMap { p ->
            val voiced = p.voiced ?: return@flatMap emptyList()
            val block = story.blocks[p.row.segment]
            val text = block.textMy.trim()
            if (text.isEmpty()) return@flatMap emptyList()
            val at = p.startMs + RecapFit.LEAD_MS
            CaptionChunks.timed(text, voiced.take.durationMs, recap.captions.chunkEdits[block.id], chars)
                // SPEED changes the take's output time, not its persisted chunk edges. Match VoiceCue.atempo.
                .map { it.copy(startMs = at + (it.startMs / p.row.voiceSpeed).toLong(), endMs = at + (it.endMs / p.row.voiceSpeed).toLong()) }
        }
    }

    /** Characters per caption line: fewer for large captions, more for small ones (same chunks burned and in `.srt`). */
    fun lineChars(output: Output, style: CaptionStyle): Int =
        maxOf(8, kotlin.math.round(ProjectRender.lineChars(output) / style.size.factor).toInt())

    /** Preview = 540p; Export = the project's quality (Auto = the movie's short side, at most 1080). */
    fun outputFor(recap: RecapV2, ratio: Ratio, quality: Quality): Output =
        if (quality == Quality.PREVIEW) RenderPresets.preview(ratio)
        else RenderPresets.exportAt(ratio, recap.quality.shortSidePx(recap.movie))

    fun outputFor(recap: Recap, ratio: Ratio, quality: Quality): Output =
        if (quality == Quality.PREVIEW) RenderPresets.preview(ratio)
        else RenderPresets.exportAt(ratio, recap.quality.shortSidePx(recap.movie))

    /** Caption em size: 1/18 of the short side (60 px at 1080), a little smaller than lyrics. */
    fun captionSizePx(output: Output): Int = minOf(output.width, output.height) / 18

    /** [captionSizePx] × the caption style's effective size factor (custom or preset). */
    fun captionSizePx(output: Output, style: CaptionStyle): Int = kotlin.math.round(captionSizePx(output) * style.effectiveSizeFactor).toInt()

    /** [startMs] in the output; [clips] = the movie ranges that play for the block (already fitted to [row]). */
    private data class Placed(val row: FitRow, val startMs: Long, val clips: List<RecapClip>, val voiced: Voiced?)

    /**
     * Fit rows with their output start, current take (a take whose file is missing counts as no voice) and the movie
     * ranges that fill [FitRow.clipMs]: every clip of the block in order; a run-on extends the last clip past its end
     * (the movie runs on; past the movie end the last frame holds); SLOW_VIDEO stretches each clip by 1 / slow.
     */
    private fun layout(recap: RecapV2, story: RecapStory, takeFile: (BlockTake) -> String?, lexicon: Lexicon): List<Placed> {
        var at = 0L
        return RecapFit.rows(recap, story, lexicon).map { row ->
            val block = story.blocks[row.segment]
            val voiced = RecapRulesV2.currentTake(recap, block, lexicon)?.let { t -> takeFile(t)?.let { Voiced(t, it) } }
            // A take that cannot be found plays as "No voice": the block alone.
            val r = if (voiced == null && row.takeMs != null) row.copy(takeMs = null, clipMs = row.sceneMs, voiceSpeed = 1f, videoSlow = 1f) else row
            Placed(r, at, fitted(block, r), voiced).also { at += r.clipMs }
        }
    }

    /** The block's clips as output ranges summing to [FitRow.clipMs] (see [layout]). */
    private fun fitted(block: RecapBlock, row: FitRow): List<RecapClip> {
        val clips = block.clips
        if (clips.isEmpty()) return emptyList()
        if (row.videoSlow in 0.01f..0.999f) {
            // Each clip's output is its length / slow; the rounding rest goes to the last one so the sum is exact.
            val outs = clips.map { (it.durationMs / row.videoSlow).toLong() }.toMutableList()
            outs[outs.lastIndex] += row.clipMs - outs.sum()
            return clips.mapIndexed { i, c -> RecapClip(c.inMs, c.inMs + outs[i]) }
        }
        val extra = row.clipMs - block.durationMs
        if (extra <= 0) return clips
        return clips.dropLast(1) + clips.last().let { it.copy(outMs = it.outMs + extra) }
    }

    /** v1 takes are found by their audio id ([toV2] keeps it). */
    private fun v1Files(recap: Recap, takeFile: (VoiceTake) -> String?): (BlockTake) -> String? =
        { bt -> recap.takes.lastOrNull { it.audio == bt.audio }?.let(takeFile) }
}
