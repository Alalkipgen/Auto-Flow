package io.github.alalkipgen.autoflow.ui.storyboard

import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.jobs.foregroundRunId
import io.github.alalkipgen.autoflow.ui.jobs.withGeminiProgress
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** [running] is the label of the job in progress; [last] is the outcome of the previous one. */
data class JobState(val running: String? = null, val last: StoryboardResult? = null, val notice: UiNotice? = null,
    val gemini: MusicGeminiRun? = null) {
    fun lastFor(open: OpenProject?): StoryboardResult? = last.takeIf { gemini == null || gemini.belongsTo(open) }
}

data class MusicGeminiRun(val id: String, val dir: String, val projectId: String, val name: String, val screen: String,
    val stage: UiNotice? = null) {
    fun belongsTo(open: OpenProject?) = open?.dir == dir && open.project.id == projectId
}

/**
 * Runs one AI storyboard job at a time in an app-wide [scope], so it survives screen changes and every screen can
 * show it with a Cancel button (specs/ai-providers.md). While a job runs, screens disable edits: the job saves the
 * project at the end and would overwrite them.
 */
class StoryboardJobs(private val service: StoryboardService, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(JobState())
    val state: StateFlow<JobState> = _state.asStateFlow()
    private var job: Job? = null

    val busy: Boolean get() = _state.value.running != null

    /** Starts [block] unless another job runs (returns false). [onDone] gets the result on the job's thread. */
    fun run(label: String, onDone: (StoryboardResult) -> Unit = {}, notice: UiNotice? = null, block: suspend StoryboardService.() -> StoryboardResult): Boolean {
        return start(label, onDone, notice, null) { service.block() }
    }

    internal fun runGemini(project: OpenProject, screen: String, label: String, notice: UiNotice,
        onDone: (StoryboardResult) -> Unit = {}, block: suspend StoryboardService.((UiNotice) -> Unit) -> StoryboardResult): Boolean {
        val run = MusicGeminiRun(foregroundRunId(), project.dir, project.project.id, project.project.name, screen)
        return start(label, { if (service.isOpen(run)) onDone(it) }, notice, run) {
            if (!service.isCurrent(project)) return@start StoryboardService.projectChanged
            withGeminiProgress(run.id) {
                service.block { stage ->
                    val current = _state.value
                    val active = current.gemini
                    if (current.running != null && active?.id == run.id)
                        _state.value = current.copy(gemini = active.copy(stage = stage))
                }
            }
        }
    }

    private fun start(label: String, onDone: (StoryboardResult) -> Unit, notice: UiNotice?,
        run: MusicGeminiRun?, block: suspend () -> StoryboardResult): Boolean {
        if (busy) return false
        _state.value = JobState(running = label, notice = notice, gemini = run)
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StoryboardResult.Failed(e.message ?: e::class.simpleName ?: "error")
            }
            _state.value = JobState(last = result, gemini = run)
            onDone(result)
        }
        job = launched
        // Also covers cancellation before the body starts, without clearing a newer run.
        launched.invokeOnCompletion { cause ->
            if (cause is CancellationException && job === launched && busy) _state.value = JobState(last = CANCELLED, gemini = run)
        }
        launched.start()
        return true
    }

    /** Cancels the running job; the project stays as it was before it started. */
    fun cancel() {
        job?.cancel()
    }

    internal fun matches(id: String?): Boolean = _state.value.let { it.running != null && id == (it.gemini?.id ?: it.running) }

    internal fun cancelRun(id: String?) { if (matches(id)) cancel() }

    fun clearLast() {
        if (!busy) _state.value = JobState()
    }

    companion object {
        val CANCELLED = StoryboardResult.Failed("Cancelled. Nothing was changed.", UiNotice(UiText.CANCELLED_UNCHANGED))
    }
}
