package io.github.alalkipgen.autoflow.ui.media

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.media.probe.MediaProbe
import io.github.alalkipgen.autoflow.media.render.FfmpegProgress
import io.github.alalkipgen.autoflow.media.render.FfmpegRunner
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderJobRunner
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.media.render.RenderState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Desktop [RenderQueue] (PLAN 1C Export on Windows): renders in this process, one job at a time in enqueue order,
 * with the shared [RenderJobRunner] (the same compiler and part-file rename as the Android worker) and the ffmpeg /
 * ffprobe from [tools] (Settings path, else `PATH`). The app window stays open while it renders, so no background
 * service is needed. Cancel: a queued job is skipped, a running one stops ffmpeg (cancelling the coroutine kills
 * the process, ToolRunner contract); the jobs behind keep their place. Call [enqueue], [observe] and [cancel] from
 * one thread (the UI).
 */
class InProcessRenderQueue(
    private val fs: FileSystem,
    private val tools: MediaTools,
    private val scope: CoroutineScope,
) : RenderQueue {
    private val states = mutableMapOf<String, MutableStateFlow<RenderState>>()
    private val jobs = mutableMapOf<String, Job>()
    private val serial = Mutex()
    private var count = 0

    override fun enqueue(job: RenderJob): String {
        val id = "render${++count}"
        val state = MutableStateFlow<RenderState>(RenderState.Queued)
        states[id] = state
        jobs[id] = scope.launch {
            serial.withLock {
                if (state.value != RenderState.Queued) return@withLock // cancelled while waiting
                state.value = RenderState.Running(0.0, null)
                val result = try {
                    render(job) { p -> if (!state.value.finished) state.value = RenderState.Running(p.fraction, p.speed) }
                } catch (e: CancellationException) {
                    state.value = RenderState.Cancelled
                    throw e
                } catch (e: Throwable) {
                    RenderState.Failed("the render crashed: ${e::class.simpleName}: ${e.message}")
                }
                // A Cancel that ffmpeg's runner turned into a failure stays a Cancel.
                if (state.value != RenderState.Cancelled) state.value = result
            }
        }
        return id
    }

    override fun observe(id: String): Flow<RenderState> = states[id] ?: flowOf(RenderState.Failed("unknown render $id"))

    override fun cancel(id: String) {
        val state = states[id] ?: return
        if (state.value.finished) return
        state.value = RenderState.Cancelled
        jobs[id]?.cancel()
    }

    private suspend fun render(job: RenderJob, onProgress: (FfmpegProgress.Progress) -> Unit): RenderState {
        val runner = tools.runner ?: return RenderState.Failed(NO_RUNNER)
        val ffmpeg = tools.ffmpeg() ?: return RenderState.Failed(NO_FFMPEG)
        val ffprobe = tools.ffprobe() ?: return RenderState.Failed(NO_FFMPEG)
        return RenderJobRunner(fs, FfmpegRunner(runner, ffmpeg), MediaProbe(runner, ffprobe)).run(job, onProgress)
    }

    companion object {
        const val NO_FFMPEG = "ffmpeg was not found: set its path in Settings → Tools (or add it to PATH)"
        const val NO_RUNNER = "this app cannot start ffmpeg"
    }
}
