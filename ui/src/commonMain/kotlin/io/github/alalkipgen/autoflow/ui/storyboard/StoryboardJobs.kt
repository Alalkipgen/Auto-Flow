package io.github.alalkipgen.autoflow.ui.storyboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** [running] is the label of the job in progress; [last] is the outcome of the previous one. */
data class JobState(val running: String? = null, val last: StoryboardResult? = null)

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
    fun run(label: String, onDone: (StoryboardResult) -> Unit = {}, block: suspend StoryboardService.() -> StoryboardResult): Boolean {
        if (busy) return false
        _state.value = JobState(running = label)
        job = scope.launch {
            val result = try {
                service.block()
            } catch (e: CancellationException) {
                _state.value = JobState(last = CANCELLED)
                throw e
            } catch (e: Exception) {
                StoryboardResult.Failed(e.message ?: e::class.simpleName ?: "error")
            }
            _state.value = JobState(last = result)
            onDone(result)
        }
        return true
    }

    /** Cancels the running job; the project stays as it was before it started. */
    fun cancel() {
        job?.cancel()
    }

    fun clearLast() {
        if (!busy) _state.value = JobState()
    }

    companion object {
        val CANCELLED = StoryboardResult.Failed("Cancelled. Nothing was changed.")
    }
}
