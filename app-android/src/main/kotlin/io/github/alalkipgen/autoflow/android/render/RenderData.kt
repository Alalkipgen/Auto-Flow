package io.github.alalkipgen.autoflow.android.render

import androidx.work.Data
import androidx.work.WorkInfo
import io.github.alalkipgen.autoflow.media.render.FfmpegProgress
import io.github.alalkipgen.autoflow.media.render.RenderState

/**
 * [RenderState] ↔ WorkManager [Data] (≤ 10 KB) for [RenderWorker] (D-26). Every render ends as a *succeeded* work
 * whose [KEY_OUTCOME] says what happened: a failed or cancelled WorkManager work would fail or cancel every render
 * queued behind it in the chain.
 */
object RenderData {
    const val KEY_OPEN = "open"
    const val KEY_FRACTION = "fraction"
    const val KEY_SPEED = "speed"
    const val KEY_OUTCOME = "outcome"
    const val KEY_OUTPUT = "output"
    const val KEY_WALL_MS = "wallMs"
    const val KEY_DURATION_MS = "durationMs"
    const val KEY_RSS_KB = "maxRssKb"
    const val KEY_REASON = "reason"
    const val KEY_TAIL = "tail"
    const val KEY_FOREGROUND = "foreground"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val UNKNOWN = "this render is no longer known (finished renders are forgotten after a day)"
    private const val MAX_TAIL = 15
    private const val MAX_LINE = 120

    fun progress(p: FfmpegProgress.Progress): Data =
        Data.Builder().putDouble(KEY_FRACTION, p.fraction).putDouble(KEY_SPEED, p.speed ?: -1.0).build()

    /** The worker's result; [foreground] is its report line about the foreground service. */
    fun output(state: RenderState, foreground: String?): Data {
        val d = Data.Builder()
        foreground?.let { d.putString(KEY_FOREGROUND, it.take(MAX_LINE * 2)) }
        when (state) {
            is RenderState.Done -> d.putString(KEY_OUTCOME, DONE).putString(KEY_OUTPUT, state.output).putLong(KEY_WALL_MS, state.wallMs)
                .putLong(KEY_DURATION_MS, state.durationMs).putLong(KEY_RSS_KB, state.maxRssKb ?: -1)
            is RenderState.Failed -> d.putString(KEY_OUTCOME, FAILED).putString(KEY_REASON, state.reason.take(MAX_LINE * 4))
                .putStringArray(KEY_TAIL, state.tail.takeLast(MAX_TAIL).map { it.take(MAX_LINE) }.toTypedArray())
            else -> d.putString(KEY_OUTCOME, CANCELLED)
        }
        return d.build()
    }

    /** WorkManager's view → [RenderState]; null info = WorkManager no longer knows the id (pruned after a day). */
    fun state(info: WorkInfo?): RenderState = when (info?.state) {
        null -> RenderState.Failed(UNKNOWN)
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> RenderState.Queued
        WorkInfo.State.RUNNING -> RenderState.Running(
            info.progress.getDouble(KEY_FRACTION, 0.0),
            info.progress.getDouble(KEY_SPEED, -1.0).takeIf { it >= 0 },
        )
        WorkInfo.State.SUCCEEDED -> outcome(info.outputData)
        // Not expected (the worker always succeeds); kept readable in case WorkManager gives up on a work.
        WorkInfo.State.FAILED -> RenderState.Failed(info.outputData.getString(KEY_REASON) ?: "the render stopped", tail(info.outputData))
        WorkInfo.State.CANCELLED -> RenderState.Cancelled
    }

    fun foregroundNote(info: WorkInfo?): String? = info?.outputData?.getString(KEY_FOREGROUND)

    private fun outcome(d: Data): RenderState = when (d.getString(KEY_OUTCOME)) {
        DONE -> RenderState.Done(
            d.getString(KEY_OUTPUT).orEmpty(),
            d.getLong(KEY_WALL_MS, 0),
            d.getLong(KEY_DURATION_MS, 0),
            d.getLong(KEY_RSS_KB, -1).takeIf { it >= 0 },
        )
        CANCELLED -> RenderState.Cancelled
        else -> RenderState.Failed(d.getString(KEY_REASON) ?: "the render stopped", tail(d))
    }

    private fun tail(d: Data) = d.getStringArray(KEY_TAIL)?.toList().orEmpty()
}
