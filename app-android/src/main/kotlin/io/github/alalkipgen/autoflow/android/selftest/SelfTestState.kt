package io.github.alalkipgen.autoflow.android.selftest

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Shared between [SelfTestService] (writes) and [SelfTestActivity] (shows), in one process. */
object SelfTestState {
    enum class Status { RUNNING, OK, FAILED, SKIPPED }

    data class Step(val label: String, val status: Status, val detail: String = "")

    /** A frame PNG to show: the owner screenshots these to check Burmese shaping on the device. */
    data class Frame(val label: String, val path: String)

    data class Ui(
        val running: Boolean = false,
        val progress: String = "",
        val steps: List<Step> = emptyList(),
        val frames: List<Frame> = emptyList(),
        val report: String? = null,
    )

    private val state = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = state

    fun begin() = state.update { Ui(running = true, progress = "Starting…") }

    fun progress(text: String) = state.update { it.copy(progress = text) }

    /** Adds a step, or replaces the last one with the same [label] (RUNNING → OK/FAILED). */
    fun step(label: String, status: Status, detail: String = "") = state.update { ui ->
        val i = ui.steps.indexOfLast { it.label == label }
        val s = Step(label, status, detail)
        ui.copy(steps = if (i >= 0) ui.steps.toMutableList().also { it[i] = s } else ui.steps + s)
    }

    fun frame(label: String, path: String) = state.update { it.copy(frames = it.frames + Frame(label, path)) }

    fun finish(report: String?) = state.update { it.copy(running = false, progress = "", report = report ?: it.report) }

    fun showSaved(report: String) = state.update { if (it.running || it.report != null) it else it.copy(report = report) }
}
