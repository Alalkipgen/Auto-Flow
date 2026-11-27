package io.github.alalkipgen.autoflow.ui.media

import io.github.alalkipgen.autoflow.media.render.MediaTaskJob
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.media.render.RenderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One [MediaTaskJob] on the queue as Jobs and its screen see it; [screen] is where Jobs → Open goes. */
data class MediaTaskRun(
    val id: String,
    val projectDir: String,
    val kind: String,
    val output: String,
    val screen: String,
    val state: RenderState = RenderState.Queued,
)

/**
 * The media tasks of this session (specs/quick-tools.md §7): queued on the shared [RenderQueue] (one render at a
 * time, the Android foreground worker / the desktop in-process queue), with real progress from ffmpeg's `-progress`,
 * Cancel, and a Jobs row each. Finished runs stay listed so the owner sees how they ended. No queue → [start] is null.
 */
class MediaTasks(private val queue: RenderQueue?, private val scope: CoroutineScope) {
    private val _runs = MutableStateFlow<List<MediaTaskRun>>(emptyList())
    val runs: StateFlow<List<MediaTaskRun>> = _runs.asStateFlow()

    val available: Boolean get() = queue != null

    fun start(task: MediaTaskJob, projectDir: String, title: String, screen: String): MediaTaskRun? {
        val q = queue ?: return null
        val id = q.enqueue(task.toRenderJob(title, projectDir))
        val run = MediaTaskRun(id, projectDir, task.kind, MediaTaskJob.absolute(projectDir, task.output), screen)
        _runs.update { list -> list.filterNot { it.id == id } + run }
        scope.launch {
            q.observe(id).collect { s -> _runs.update { list -> list.map { if (it.id == id) it.copy(state = s) else it } } }
        }
        return run
    }

    fun run(id: String): MediaTaskRun? = _runs.value.firstOrNull { it.id == id }

    /** The newest run writing [output] (a task restarted for the same file replaces the older row on screen). */
    fun forOutput(output: String): MediaTaskRun? = _runs.value.lastOrNull { it.output == output }

    fun cancel(id: String) {
        if (run(id)?.state?.finished == false) queue?.cancel(id)
    }
}
