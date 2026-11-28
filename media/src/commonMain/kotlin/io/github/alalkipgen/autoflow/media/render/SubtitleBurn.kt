package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.CaptionSize
import io.github.alalkipgen.autoflow.core.recap.CaptionStyle
import io.github.alalkipgen.autoflow.core.recap.SrtCue
import kotlin.math.roundToInt

/** How burned subtitles look (specs/quick-tools.md §5 Export): a thin outline, a see-through box, or a thick outline. */
enum class BurnLook { CLEAN, BOXED, OUTLINE }

/** Text height as a share of the video height. */
enum class BurnSize(val share: Double) { SMALL(0.045), MEDIUM(0.055), LARGE(0.07) }

/** How long one burned line may be: [SHORT] = `subtitles.shortCharsPerLine`, [LONG] = `subtitles.maxCharsPerLine`. */
enum class BurnLength { SHORT, LONG }

/**
 * How much text a burned caption shows at once (owner, 2026-11-21: three long lines covered the picture in a landscape
 * dub): at most [lines] lines of [length]; a longer cue is shown in parts one after another, each part's time its share
 * of the text ([CaptionChunks.split]), never re-timed outside the cue.
 */
data class BurnFit(val lines: Int, val length: BurnLength) {
    companion object {
        /** By the frame: landscape → one short line (the picture stays clear); portrait or square → two short lines. */
        fun auto(width: Int, height: Int): BurnFit = BurnFit(if (width > height) 1 else 2, BurnLength.SHORT)
    }
}

/**
 * A subtitle burn as one direct ffmpeg command (spec §9; no render graph, whose `Output.fps` is an Int and whose
 * sizes are presets): the `.ass` from [AssWriter] with `PlayResX/Y` = the source size, then
 * `-i src -vf ass=… -map 0:v:0 -map 0:a:0? -c:v libx264 -c:a copy` (AAC when the sound cannot be copied into mp4),
 * so the picture keeps its exact size and frame rate and the sound is untouched.
 */
object SubtitleBurn {
    /** Sound codecs an mp4 takes as they are. */
    private val MP4_SOUND = setOf("aac", "mp3", "ac3", "eac3", "alac")

    fun copiesSound(codec: String?): Boolean = codec?.lowercase() in MP4_SOUND

    /**
     * One overlay per cue at the cue's own times (never re-timed). A line wider than the picture — at most
     * [maxChars], fewer when the text is large for the width — is broken like [CaptionChunks] (never inside a
     * Burmese syllable); the `.ass` has no automatic wrap. [maxLines] > 0 ([BurnFit.lines]): a cue with more lines is
     * shown in parts of at most that many lines, one after another inside the cue's time ([CaptionChunks.split]).
     */
    fun overlays(cues: List<SrtCue>, width: Int, height: Int, font: String, look: BurnLook, size: BurnSize, maxChars: Int,
        maxLines: Int = 0): List<Overlay> {
        val px = px(height, size.share)
        return parts(cues, width, px, maxChars, maxLines).map { c ->
            Overlay(c.text, c.startMs, c.endMs, font, px, box = look == BurnLook.BOXED,
                strokeWidth = if (look == BurnLook.OUTLINE) maxOf(3, px / 10) else 0)
        }
    }

    /**
     * Dubbing's burned subtitles in a caption style (D-106, Movie Recap's [CaptionStyle]): its colour, the dark box or
     * the outline ([CaptionStyle.strokeWidth] is pixels at 1080, scaled to the video's short side; 0 = the thin
     * automatic outline), at [share] of the video height. Cues are broken and split as in the [BurnLook] overlays;
     * [centerY] (0–1 of the frame) = centred there instead of the usual bottom place (on a blur band).
     * The default Dubbing style (white, Medium, boxed) gives the same overlays as [BurnLook.BOXED] at [BurnSize.MEDIUM].
     */
    fun overlays(cues: List<SrtCue>, width: Int, height: Int, font: String, style: CaptionStyle, maxChars: Int,
        maxLines: Int = 0, centerY: Float? = null): List<Overlay> {
        val px = px(height, share(style))
        val stroke = if (style.box || style.strokeWidth <= 0) 0
            else maxOf(1, (style.strokeWidth * minOf(width, height).coerceAtLeast(1) / 1080.0).roundToInt())
        return parts(cues, width, px, maxChars, maxLines).map { c ->
            Overlay(c.text, c.startMs, c.endMs, font, px, color = style.effectiveColour, centerY = centerY, box = style.box,
                strokeWidth = stroke, strokeColour = style.strokeColour)
        }
    }

    /**
     * A caption style's text height as a share of the video height (D-106): Small / Medium / Large = [BurnSize]'s (the
     * sizes Dubbing always had); a custom factor (XL = 1.4) × Medium.
     */
    fun share(style: CaptionStyle): Double = if (style.customSizeFactor != null) BurnSize.MEDIUM.share * style.effectiveSizeFactor
        else when (style.size) {
            CaptionSize.S -> BurnSize.SMALL.share
            CaptionSize.M -> BurnSize.MEDIUM.share
            CaptionSize.L -> BurnSize.LARGE.share
        }

    private fun px(height: Int, share: Double) = maxOf(12, (height * share).roundToInt())

    /** The shown cues (split into parts of [maxLines]) with every line at most [fit] characters for [px] text. */
    private fun parts(cues: List<SrtCue>, width: Int, px: Int, maxChars: Int, maxLines: Int): List<SrtCue> {
        val fit = fit(width, px, maxChars)
        val shown = cues.filter { it.text.isNotBlank() && it.endMs > it.startMs }
        val parts = if (maxLines > 0) CaptionChunks.split(shown, fit, maxLines).filter { it.text.isNotBlank() && it.endMs > it.startMs } else shown
        return parts.map { c ->
            c.copy(text = c.text.trim().split('\n').flatMap { l -> if (l.trim().length > fit) CaptionChunks.lines(l, fit) else listOf(l.trim()) }
                .joinToString("\n"))
        }
    }

    /** The longest line in characters: [maxChars], fewer when the text is large for the picture width. */
    fun fitChars(width: Int, height: Int, size: BurnSize, maxChars: Int): Int = fit(width, px(height, size.share), maxChars)

    private fun fit(width: Int, px: Int, maxChars: Int): Int = maxOf(8, minOf(maxChars, (width * 0.9 / (px * 0.6)).toInt()))

    /** The `.ass` text; [assScale] = the font's [FontInfo.assScale] (null = 1.0); [maxLines] as in [overlays]. */
    fun ass(cues: List<SrtCue>, width: Int, height: Int, font: String, look: BurnLook, size: BurnSize, maxChars: Int, assScale: Double?,
        maxLines: Int = 0): String =
        AssWriter.write(overlays(cues, width, height, font, look, size, maxChars, maxLines), Output(Ratio.R16_9, width, height, 0, Quality.EXPORT, ""), { assScale })

    /** The `.ass` of a caption style (D-106), as the styled [overlays]. */
    fun ass(cues: List<SrtCue>, width: Int, height: Int, font: String, style: CaptionStyle, maxChars: Int, assScale: Double?,
        maxLines: Int = 0, centerY: Float? = null): String =
        AssWriter.write(overlays(cues, width, height, font, style, maxChars, maxLines, centerY), Output(Ratio.R16_9, width, height, 0, Quality.EXPORT, ""), { assScale })

    internal fun filter(ass: String, fontsDir: String) = "ass=filename=${RenderCompiler.filterPath(ass)}:fontsdir=${RenderCompiler.filterPath(fontsDir)}"

    /** The burn; paths are relative to the project folder (the task's work dir) or absolute. Output = [MediaTaskJob.PART]. */
    fun args(source: String, ass: String, fontsDir: String, audioCodec: String?, x264Preset: String, crf: Int): List<String> =
        listOf("-y", "-nostdin", "-i", source, "-vf", filter(ass, fontsDir), "-map", "0:v:0", "-map", "0:a:0?",
            "-c:v", "libx264", "-preset", x264Preset, "-crf", crf.toString(), "-pix_fmt", "yuv420p") +
            (if (audioCodec == null || copiesSound(audioCodec)) listOf("-c:a", "copy") else listOf("-c:a", "aac", "-b:a", "192k")) +
            listOf("-movflags", "+faststart", "-f", "mp4", MediaTaskJob.PART)

    /** "Check one frame": the frame at [atMs] with the subtitles, from the source (`-copyts` keeps the cue times). */
    fun frameArgs(source: String, ass: String, fontsDir: String, atMs: Long, image: String): List<String> =
        listOf("-y", "-nostdin", "-ss", RenderCompiler.sec(atMs), "-copyts", "-i", source, "-vf", filter(ass, fontsDir),
            "-frames:v", "1", "-update", "1", image)
}
