package io.github.alalkipgen.autoflow.android.render

import io.github.alalkipgen.autoflow.media.render.FfmpegProgress
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderState
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive

/**
 * One render as [RenderWorker] runs it (D-26), without Android types so it is tested with fakes:
 * - a job the owner cancelled before it started never renders (nor starts the foreground service);
 * - Cancel stops only this render ([RenderJobs.cancel]) and [run] returns only after the render — and so the ffmpeg
 *   process — has ended, so the next render in the chain never overlaps it;
 * - every outcome, also a crash, becomes a [RenderState], because a failed work would fail the renders behind it;
 * - a half-written `.part.mp4` is deleted (the last good export stays as it was);
 * - when WorkManager itself stops the worker (system limits, process shutdown) the cancellation is rethrown and the
 *   job file stays, so WorkManager's retry runs it again.
 */
class RenderExecution(
    private val jobs: RenderJobs,
    private val render: suspend (RenderJob, (FfmpegProgress.Progress) -> Unit) -> RenderState,
    private val deleteFile: (String) -> Unit = { File(it).delete() },
) {
    /** [job] is null when the job file could not be read. */
    data class Outcome(val state: RenderState, val job: RenderJob?)

    /** [foreground] starts the foreground service; false = it did not, and the job fails instead of running unguarded. */
    suspend fun run(
        id: String,
        foreground: suspend (RenderJob) -> Boolean,
        onProgress: (FfmpegProgress.Progress) -> Unit = {},
    ): Outcome = coroutineScope {
        val job = try {
            jobs.read(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@coroutineScope Outcome(RenderState.Failed("cannot read the render job: ${e.message}"), null)
        }
        val work = async(start = CoroutineStart.LAZY) {
            try {
                render(job, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RenderState.Failed("the render crashed: ${e::class.simpleName}: ${e.message}")
            }
        }
        if (!jobs.begin(id) { work.cancel() }) {
            work.cancel()
            return@coroutineScope Outcome(RenderState.Cancelled, job)
        }
        var state: RenderState = RenderState.Cancelled
        try {
            state = if (!foreground(job)) {
                work.cancel()
                RenderState.Failed("could not start the foreground service")
            } else {
                try {
                    work.await()
                } catch (e: CancellationException) {
                    ensureActive() // the worker itself was stopped: rethrow, WorkManager retries it
                    RenderState.Cancelled
                }
            }
        } finally {
            // Before end(): once the job no longer runs, a watcher may already check for the half-written file.
            if (state !is RenderState.Done) runCatching { deleteFile(partPath(job)) }
            jobs.end(id)
        }
        Outcome(state, job)
    }

    companion object {
        /** Where the shared RenderJobRunner writes before the rename (`<outputName>.part.mp4`). */
        fun partPath(job: RenderJob): String {
            val out = if (job.outputName.startsWith("/")) job.outputName else File(job.workDir, job.outputName).path
            return "$out.part.mp4"
        }
    }
}
