package io.github.alalkipgen.autoflow.ui.voice

import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class VoiceSpeakUi(val dir: String? = null, val confirm: VoiceSpeakRequest? = null, val notice: UiNotice? = null, val ok: Boolean = false)

/**
 * Speak on the Text → voice screen (specs/quick-tools.md §6, V-1e): the confirm card (requests = parts without a
 * current take, or the one part of ↻), then the [VoiceSpeakJob] of this project on the app's runner. Nothing is
 * spoken without Start; while the job is open the text, voice and tone are read-only.
 */
class VoiceSpeakModel(
    private val session: ProjectSession,
    private val speak: VoiceSpeakJob?,
    private val runner: AiJobRunner?,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(VoiceSpeakUi())

    /** Whole minutes Speak of [n] parts with [lock] takes at the job's pace (Edge faster than Gemini, F-3). */
    fun minutes(n: Int, lock: io.github.alalkipgen.autoflow.core.recap.VoiceLock?): Int =
        EdgeVoices.minutes(n, speak?.rpm(lock) ?: EdgeVoices.rpm(lock, io.github.alalkipgen.autoflow.ai.config.EdgeTtsConfig().rpm))
    val state: StateFlow<VoiceSpeakUi> = _state.asStateFlow()
    val jobs: StateFlow<List<AiJob>> = runner?.jobs ?: MutableStateFlow(emptyList())

    /** The unfinished Speak job of project [dir] in [list] (running, paused or failed), if any. */
    fun jobOf(dir: String?, list: List<AiJob>): AiJob? = list.lastOrNull { it.kind == AiJobKind.TEXT_TTS && it.project == dir && it.state in OPEN }

    /** Speak n parts, or ↻ for [only]: shows the confirm card; nothing is sent yet. */
    fun ask(only: Set<String>? = null): Job? {
        val open = session.open ?: return null
        val t = speak?.takeIf { it.available && runner != null } ?: return say(open.dir, UiNotice(UiText.TTS_UNAVAILABLE))
        if (jobOf(open.dir, jobs.value) != null) return say(open.dir, UiNotice(UiText.JOB_ALREADY_RUNNING))
        val request = t.plan(open.dir, only) ?: return say(open.dir, UiNotice(UiText.VA_NO_VOICE))
        if (request.parts.isEmpty()) return say(open.dir, UiNotice(UiText.VA_ALL_VOICED), ok = true)
        _state.value = VoiceSpeakUi(open.dir, confirm = request)
        return null
    }

    fun dismiss() { _state.value = VoiceSpeakUi(_state.value.dir) }

    fun start(): Job? {
        val c = _state.value.confirm ?: return null
        val dir = _state.value.dir ?: return null
        val t = speak ?: return null
        val r = runner ?: return null
        _state.value = VoiceSpeakUi(dir)
        return scope.launch { follow(r, dir, t.enqueue(r, dir, c)) }
    }

    /** Speaks only the parts not saved yet. */
    fun resume(job: AiJob): Job? {
        val r = runner ?: return null
        _state.value = VoiceSpeakUi(job.project)
        return scope.launch { follow(r, job.project, r.enqueue(job)) }
    }

    /** Stops the job; parts already spoken keep their take. */
    fun cancel(job: AiJob): Job? {
        val r = runner ?: return null
        return scope.launch { r.cancel(job.id); say(job.project, UiNotice(UiText.VA_SPEAK_CANCELLED)) }
    }

    private suspend fun follow(r: AiJobRunner, dir: String, id: String) {
        val end = r.jobs.first { list -> list.firstOrNull { it.id == id }?.state?.let { it !in ACTIVE } ?: true }.firstOrNull { it.id == id }
        when (end?.state) {
            AiJobState.DONE -> say(dir, UiNotice(UiText.VA_SPEAK_DONE, "n" to (end.initialItemCount ?: 0)), ok = true)
            null, AiJobState.CANCELLED -> say(dir, UiNotice(UiText.VA_SPEAK_CANCELLED))
            else -> Unit // PAUSED / FAILED: the job card says why and offers Resume
        }
    }

    private fun say(dir: String, notice: UiNotice, ok: Boolean = false): Job? { _state.value = VoiceSpeakUi(dir, notice = notice, ok = ok); return null }

    companion object {
        private val ACTIVE = setOf(AiJobState.QUEUED, AiJobState.RUNNING)
        private val OPEN = ACTIVE + setOf(AiJobState.PAUSED, AiJobState.FAILED)
    }
}
