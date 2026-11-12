package io.github.alalkipgen.autoflow.media.audio

import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner

/**
 * The song as small mono 16-bit PCM WAV, decoded with the bundled / located ffmpeg: what Gemini hears for "Suggest
 * timings" (D-31) and what beat detection reads (D-32). ffmpeg always has `pcm_s16le`, so no extra encoder is needed.
 */
object SongAudio {
    /** 16 kHz: speech quality (Gemini resamples audio to 16 kHz anyway); about 1.9 MB per minute. */
    const val AI_RATE = 16_000

    fun monoWavArgs(input: String, output: String, rate: Int): List<String> = listOf(
        "-hide_banner", "-nostdin", "-y", "-i", input, "-vn", "-ac", "1", "-ar", rate.toString(), "-c:a", "pcm_s16le", "-f", "wav", output,
    )

    /** Writes [output]; null when done, else why not (for the screen). */
    suspend fun decodeMonoWav(runner: ToolRunner, ffmpeg: String, input: String, output: String, workDir: String, rate: Int): String? =
        when (val r = runner.run(listOf(ffmpeg) + monoWavArgs(input, output, rate), workDir, TIMEOUT_MS, keepOutput = false) {}) {
            is ToolExec.CannotStart -> "ffmpeg cannot start: ${r.message}"
            is ToolExec.Done -> when {
                r.timedOut -> "ffmpeg took too long to read the song"
                r.exitCode != 0 -> r.tail.lastOrNull { it.isNotBlank() } ?: "ffmpeg exit ${r.exitCode}"
                else -> null
            }
        }

    private const val TIMEOUT_MS = 120_000L
}
