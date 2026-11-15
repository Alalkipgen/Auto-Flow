package io.github.alalkipgen.autoflow.media.audio

import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner

/**
 * "Cut long pauses" for a new take (D-46 fix 1, studio-v2 §8, audit F09): every pause longer than 350 ms becomes
 * 200 ms; shorter pauses and the speech stay exactly as they are. Two audio-only ffmpeg runs: `silencedetect` finds
 * the pauses ([AudioFilters.silenceDetectArgs]), then the kept ranges are cut sample-exact and joined into a mono
 * 48 kHz WAV ([AudioFilters.cutArgs]). One `silenceremove` cannot do this: it keeps `stop_duration + stop_silence`
 * (≈ 570 ms) of every long pause and shortens pauses the rule leaves alone.
 */
class SilenceTrim(private val tools: ToolRunner, private val ffmpeg: String) {
    sealed interface Result {
        /** [output] was written; [kept] = the input ranges in it (seconds, end null = to the end). */
        data class Trimmed(val kept: List<Pair<Double, Double?>>) : Result

        /** No pause is long enough: nothing written, the take stays as it is. */
        data object Unchanged : Result

        data class Failed(val reason: String) : Result
    }

    suspend fun trim(
        input: String,
        output: String,
        workDir: String,
        timeoutMs: Long,
        gapMs: Long = AudioFilters.SILENCE_GAP_MS,
        keepMs: Long = AudioFilters.SILENCE_KEEP_MS,
    ): Result {
        val log = StringBuilder()
        val detect = tools.run(listOf(ffmpeg) + AudioFilters.silenceDetectArgs(input, gapMs), workDir, timeoutMs, keepOutput = false) {
            log.appendLine(it)
        }
        failure(detect, "finding the pauses")?.let { return it }
        val text = log.toString()
        val kept = AudioFilters.keptRanges(AudioFilters.parseSilences(text), AudioFilters.parseInputDuration(text), gapMs, keepMs)
        if (kept == listOf(0.0 to null)) return Result.Unchanged
        if (kept.isEmpty()) return Result.Failed("the take is all silence")
        val cut = tools.run(listOf(ffmpeg) + AudioFilters.cutArgs(input, output, kept), workDir, timeoutMs, keepOutput = false) {}
        failure(cut, "cutting the pauses")?.let { return it }
        return Result.Trimmed(kept)
    }

    private fun failure(r: ToolExec, step: String): Result.Failed? = when (r) {
        is ToolExec.CannotStart -> Result.Failed("ffmpeg cannot start ($step): ${r.message}")
        is ToolExec.Done -> when {
            r.timedOut -> Result.Failed("ffmpeg took too long ($step)")
            r.exitCode != 0 -> Result.Failed("ffmpeg failed ($step, exit ${r.exitCode}): ${r.tail.lastOrNull { it.isNotBlank() }.orEmpty()}")
            else -> null
        }
    }
}
