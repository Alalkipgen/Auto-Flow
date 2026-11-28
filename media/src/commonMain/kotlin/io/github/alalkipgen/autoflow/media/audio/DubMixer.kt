package io.github.alalkipgen.autoflow.media.audio

import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.media.render.DubPicture
import io.github.alalkipgen.autoflow.media.render.MediaTaskJob
import io.github.alalkipgen.autoflow.media.render.SubtitleBurn

/**
 * One dub line for [DubMixer]: take [file] (relative to the project folder or absolute) placed at [atMs] with its
 * first [leadMs] of silence skipped, at [speed] (`atempo` only above 1.0), cut at [maxMs] (its slot) so it never
 * reaches the next line.
 */
data class DubCue(val file: String, val atMs: Long, val leadMs: Long, val maxMs: Long, val speed: Double = 1.0)

/** The voice track as queue tasks: the stems, then the voice that mixes them (run in this order). */
data class DubVoicePlan(val stems: List<MediaTaskJob>, val voice: MediaTaskJob) {
    val tasks: List<MediaTaskJob> get() = stems + voice
}

/**
 * The dub mix and export (specs/dubbing.md §6, D-87) as direct ffmpeg commands on the render queue, one per task:
 * stems of `dubbing.stemGroup` cues (every cue an `amix` input over a silent bed of the video's length, as D-0
 * measured), the voice track mixing the stems, then the export — the original sound at its Lower volume (none for
 * Mute) under the voice, the picture copied (`-c:v copy`) or, with Burmese subtitles, burned like [SubtitleBurn].
 * Stem and voice names come from their own arguments, so the same lines give the same files and a finished one is
 * reused (Resume), and changed lines give new files.
 */
object DubMixer {
    const val STEM = "dub-stem"
    const val VOICE = "dub-voice"
    const val EXPORT = "dub-export"
    const val PARTS_DIR = "exports/dub-parts"
    private const val FORMAT = "aformat=sample_rates=48000:channel_layouts=stereo"

    /** One cue's filter chain from input [input] to [label]. */
    fun chain(input: Int, c: DubCue, label: String): String {
        val tempo = if (c.speed > 1.0) ",atempo=${num(c.speed.coerceAtMost(2.0))}" else ""
        return "[$input:a]$FORMAT,atrim=start=${sec(c.leadMs.coerceAtLeast(0))},asetpts=PTS-STARTPTS$tempo," +
            "atrim=duration=${sec(c.maxMs.coerceAtLeast(1))},adelay=${c.atMs.coerceAtLeast(0)}:all=1[$label]"
    }

    /** [cues] mixed over a silent bed of [totalMs] into a 48 kHz stereo wav at [MediaTaskJob.PART]. */
    fun stemArgs(cues: List<DubCue>, totalMs: Long): List<String> =
        mix(cues.map { it.file }, cues.mapIndexed { k, c -> chain(k, c, "c$k") }, totalMs)

    /** The voice track: [stems] (each already the video's length) mixed as they are. */
    fun voiceArgs(stems: List<String>, totalMs: Long): List<String> = mix(stems, stems.indices.map { "[$it:a]$FORMAT[c$it]" }, totalMs)

    /** Stems of [group] cues each, then the voice at [voiceOut] (null = in [PARTS_DIR], named by its arguments). */
    fun voicePlan(cues: List<DubCue>, totalMs: Long, group: Int, voiceOut: String? = null): DubVoicePlan {
        val stems = cues.chunked(group.coerceAtLeast(1)).map { g ->
            val args = stemArgs(g, totalMs)
            MediaTaskJob(STEM, args, "$PARTS_DIR/stem-${key(args)}.wav", totalMs)
        }
        val args = voiceArgs(stems.map { it.output }, totalMs)
        return DubVoicePlan(stems, MediaTaskJob(VOICE, args, voiceOut ?: "$PARTS_DIR/voice-${key(args)}.wav", totalMs))
    }

    /**
     * The export of [video] with the [voice] track: the original sound (when [hasSound]) at [originalVolume] under it
     * (0 = Mute: only the voice), the length of the video ([totalMs]). [ass] null = the picture is copied into
     * [outExt] (`VideoSound.videoExt`); else the subtitles are burned (libx264 [x264Preset] / [crf], mp4).
     */
    fun exportArgs(video: String, voice: String, totalMs: Long, originalVolume: Double, hasSound: Boolean, outExt: String, bitrate: String,
        ass: String? = null, fontsDir: String = "", x264Preset: String = "veryfast", crf: Int = 20): List<String> =
        exportArgs(video, voice, totalMs, originalVolume, hasSound, outExt, bitrate, DubPicture(ass = ass, fontsDir = fontsDir), x264Preset, crf)

    /**
     * The export with what Style draws on the picture (D-107): [picture] = the blur / colour boxes and the burned
     * subtitles, made into a new libx264 mp4; nothing drawn = the picture copied, as above. Subtitles only make the
     * same command as before.
     */
    fun exportArgs(video: String, voice: String, totalMs: Long, originalVolume: Double, hasSound: Boolean, outExt: String, bitrate: String,
        picture: DubPicture, x264Preset: String = "veryfast", crf: Int = 20): List<String> {
        val sound = if (hasSound && originalVolume > 0)
            "[0:a]$FORMAT,volume=${num(originalVolume)}[orig];[1:a]$FORMAT[dub];[orig][dub]amix=inputs=2:duration=longest:normalize=0[aout]"
            else "[1:a]$FORMAT[aout]"
        val drawn = picture.drawn
        val pictureArgs = if (!drawn) listOf("-map", "0:v:0", "-c:v", "copy")
            else listOf("-map", "[vout]", "-c:v", "libx264", "-preset", x264Preset, "-crf", crf.toString(), "-pix_fmt", "yuv420p")
        val graph = if (!drawn) sound else "${picture.chain()};$sound"
        val container = if (!drawn && outExt == "mkv") listOf("-f", "matroska") else listOf("-movflags", "+faststart", "-f", "mp4")
        return listOf("-hide_banner", "-y", "-nostdin", "-i", video, "-i", voice, "-filter_complex", graph) + pictureArgs +
            listOf("-map", "[aout]", "-c:a", "aac", "-b:a", bitrate, "-t", sec(totalMs)) + container + MediaTaskJob.PART
    }

    /** A short name for [args] (16 hex of their SHA-256): the same task, the same name. */
    fun key(args: List<String>): String = Sha256.hex(args.joinToString("\u0000").encodeToByteArray()).take(16)

    private fun mix(inputs: List<String>, chains: List<String>, totalMs: Long): List<String> {
        val t = sec(totalMs)
        val graph = (chains + "anullsrc=r=48000:cl=stereo:d=$t[bed]" +
            "[bed]${chains.indices.joinToString("") { "[c$it]" }}amix=inputs=${chains.size + 1}:duration=first:normalize=0,atrim=duration=$t[aout]")
            .joinToString(";")
        return listOf("-hide_banner", "-y", "-nostdin") + inputs.flatMap { listOf("-i", it) } +
            listOf("-filter_complex", graph, "-map", "[aout]", "-c:a", "pcm_s16le", "-f", "wav", MediaTaskJob.PART)
    }

    private fun sec(ms: Long) = "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"

    private fun num(v: Double): String = (kotlin.math.round(v * 1000) / 1000).toString().removeSuffix(".0")
}
