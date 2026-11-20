package io.github.alalkipgen.autoflow.media.audio

import io.github.alalkipgen.autoflow.media.render.MediaTaskJob

/**
 * Video sound (specs/quick-tools.md §6, §9; V-3): Save the sound only · Replace the sound · Add voice or music on
 * top. The picture is never re-encoded (`-c:v copy`) and the video keeps its length: a shorter new sound is padded with
 * silence, a longer one is cut. Results go to `MediaTaskJob.PART` as one ffmpeg command each.
 */
object VideoSound {
    const val SOUND_ONLY = "video-sound-only"
    const val REPLACE = "video-sound-replace"
    const val ADD = "video-sound-add"

    /** The container a video with [ext] keeps its picture in: mp4 for mp4 / mov / m4v, Matroska for mkv / webm. */
    fun videoExt(ext: String): String = if (ext.lowercase() in setOf("mkv", "webm")) "mkv" else "mp4"

    /** Save the sound only: AAC is copied into m4a as it is; anything else becomes AAC at [bitrate]; wav = 16-bit PCM. */
    fun soundOnlyArgs(video: String, audioCodec: String?, format: String, bitrate: String): List<String> {
        val encode = when {
            format == "wav" -> listOf("-c:a", "pcm_s16le", "-f", "wav")
            audioCodec == "aac" -> listOf("-c:a", "copy", "-movflags", "+faststart", "-f", "mp4")
            else -> listOf("-c:a", "aac", "-b:a", bitrate, "-movflags", "+faststart", "-f", "mp4")
        }
        return listOf("-hide_banner", "-y", "-nostdin", "-i", video, "-vn", "-map", "0:a:0") + encode + MediaTaskJob.PART
    }

    /** Replace the sound with [audio]: padded with silence or cut to the video's [videoMs]. */
    fun replaceArgs(video: String, audio: String, videoMs: Long, outExt: String, bitrate: String): List<String> =
        listOf("-hide_banner", "-y", "-nostdin", "-i", video, "-i", audio, "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy",
            "-af", "apad", "-c:a", "aac", "-b:a", bitrate, "-t", secs(videoMs)) + container(outExt) + MediaTaskJob.PART

    /**
     * Add [audio] on top of the video's own sound ([hasSound]; none = it is the only sound) from [startMs] at [volume]
     * (1.0 = as it is); [lower] = the original is lowered while the added sound plays (the existing ducking).
     */
    fun addArgs(video: String, audio: String, videoMs: Long, startMs: Long, volume: Double, lower: Boolean, hasSound: Boolean,
        outExt: String, bitrate: String): List<String> {
        val added = "[1:a]adelay=${startMs.coerceAtLeast(0)}:all=1,volume=${num(volume)},apad[add]"
        val graph = if (!hasSound) "$added;[add]anull[out]"
            else (listOf(added, "[0:a]anull[orig]") + AudioFilters.mixWithMusic("add", "orig", "out", lower)).joinToString(";")
        return listOf("-hide_banner", "-y", "-nostdin", "-i", video, "-i", audio, "-filter_complex", graph, "-map", "0:v:0", "-map", "[out]",
            "-c:v", "copy", "-c:a", "aac", "-b:a", bitrate, "-t", secs(videoMs)) + container(outExt) + MediaTaskJob.PART
    }

    private fun container(ext: String) = if (ext == "mkv") listOf("-f", "matroska") else listOf("-movflags", "+faststart", "-f", "mp4")

    private fun secs(ms: Long) = "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"

    private fun num(v: Double): String {
        val r = kotlin.math.round(v * 1000) / 1000
        return r.toString().removeSuffix(".0")
    }
}
