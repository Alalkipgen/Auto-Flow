package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.SrtCue
import kotlin.math.roundToInt

/** How burned subtitles look (specs/quick-tools.md §5 Export): a thin outline, a see-through box, or a thick outline. */
enum class BurnLook { CLEAN, BOXED, OUTLINE }

/** Text height as a share of the video height. */
enum class BurnSize(val share: Double) { SMALL(0.045), MEDIUM(0.055), LARGE(0.07) }

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
     * Burmese syllable); the `.ass` has no automatic wrap.
     */
    fun overlays(cues: List<SrtCue>, width: Int, height: Int, font: String, look: BurnLook, size: BurnSize, maxChars: Int): List<Overlay> {
        val px = maxOf(12, (height * size.share).roundToInt())
        val fit = maxOf(8, minOf(maxChars, (width * 0.9 / (px * 0.6)).toInt()))
        return cues.filter { it.text.isNotBlank() && it.endMs > it.startMs }.map { c ->
            val text = c.text.trim().split('\n').flatMap { l -> if (l.trim().length > fit) CaptionChunks.lines(l, fit) else listOf(l.trim()) }
                .joinToString("\n")
            Overlay(text, c.startMs, c.endMs, font, px, box = look == BurnLook.BOXED,
                strokeWidth = if (look == BurnLook.OUTLINE) maxOf(3, px / 10) else 0)
        }
    }

    /** The `.ass` text; [assScale] = the font's [FontInfo.assScale] (null = 1.0). */
    fun ass(cues: List<SrtCue>, width: Int, height: Int, font: String, look: BurnLook, size: BurnSize, maxChars: Int, assScale: Double?): String =
        AssWriter.write(overlays(cues, width, height, font, look, size, maxChars), Output(Ratio.R16_9, width, height, 0, Quality.EXPORT, ""), { assScale })

    private fun filter(ass: String, fontsDir: String) = "ass=filename=${RenderCompiler.filterPath(ass)}:fontsdir=${RenderCompiler.filterPath(fontsDir)}"

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
