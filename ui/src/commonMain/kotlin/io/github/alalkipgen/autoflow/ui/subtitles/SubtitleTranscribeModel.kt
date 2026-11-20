package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
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

/** What Transcribe will send (nothing is sent before Start): sound length, requests, today's usage, spoken language. */
data class SubtitleTranscribeConfirm(val audioMs: Long, val requests: Int, val usedToday: Int?, val lang: String, val again: Boolean)

data class SubtitleTranscribeUi(val dir: String? = null, val planning: Boolean = false, val confirm: SubtitleTranscribeConfirm? = null,
    val notice: UiNotice? = null, val ok: Boolean = false)

/**
 * Transcribe in the Subtitles tool (specs/quick-tools.md §5, S-5b): the confirm card, then the [VideoTranscriber] job
 * of this project on the app's runner. Progress, Pause / Resume / Cancel come from that job ([jobOf]), so a job
 * resumed after a restart shows the same way; Done makes a new version that the editor opens.
 */
class SubtitleTranscribeModel(
    private val session: ProjectSession,
    private val transcriber: VideoTranscriber?,
    private val runner: AiJobRunner?,
    private val usage: UsageCounter?,
    private val config: ToolsConfig,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SubtitleTranscribeUi())
    val state: StateFlow<SubtitleTranscribeUi> = _state.asStateFlow()
    val jobs: StateFlow<List<AiJob>> = runner?.jobs ?: MutableStateFlow(emptyList())
    val languages: List<String> get() = config.transcribe.languages.ifEmpty { listOf(VideoTranscriber.AUTO) }
    private var plan: VideoTranscriber.Plan? = null

    /** The unfinished Transcribe job of project [dir] in [list] (running, paused or failed), if any. */
    fun jobOf(dir: String?, list: List<AiJob>): AiJob? =
        list.lastOrNull { it.kind == AiJobKind.MEDIA_TRANSCRIBE && it.project == dir && it.state in OPEN }

    /** "Turn speech into text": finds the pauses, then shows the confirm card. */
    fun ask(): Job? {
        val open = session.open ?: return null
        val video = open.project.subtitles?.video ?: return null
        val t = transcriber?.takeIf { it.available && runner != null } ?: return say(open.dir, UiNotice(UiText.TRANSCRIPTION_UNAVAILABLE))
        if (!video.hasAudio) return say(open.dir, UiNotice(UiText.MOVIE_NO_SOUND_TO_TRANSCRIBE))
        if (jobOf(open.dir, jobs.value) != null) return say(open.dir, UiNotice(UiText.JOB_ALREADY_RUNNING))
        _state.value = SubtitleTranscribeUi(open.dir, planning = true)
        return scope.launch {
            val p = t.plan(open.dir, video)
            val today = usage?.let { runCatching { it.today().values.sum() }.getOrNull() }
            if (_state.value.dir != open.dir || !_state.value.planning) return@launch
            plan = p
            _state.value = SubtitleTranscribeUi(open.dir, confirm = SubtitleTranscribeConfirm(p.audioMs, p.requests, today, languages.first(),
                again = open.project.subtitles?.current != null))
        }
    }

    fun setLanguage(lang: String) { _state.value.confirm?.let { _state.value = _state.value.copy(confirm = it.copy(lang = lang)) } }

    /** Back on the confirm card: nothing is sent. */
    fun dismiss() { plan = null; _state.value = SubtitleTranscribeUi(_state.value.dir) }

    /** Start on the confirm card: the job of this project; the lines arrive as a new version when every chunk is in. */
    fun start(): Job? {
        val c = _state.value.confirm ?: return null
        val p = plan ?: return null
        val dir = _state.value.dir ?: return null
        val t = transcriber ?: return null
        val r = runner ?: return null
        plan = null
        _state.value = SubtitleTranscribeUi(dir)
        return scope.launch { follow(r, t, dir, t.enqueue(r, dir, p, c.lang)) }
    }

    /** The chunk being sent finishes and is kept; the job then pauses. */
    fun pause(job: AiJob) { transcriber?.pause(job.id) }

    /** Sends only the chunks not saved yet. */
    fun resume(job: AiJob): Job? {
        val t = transcriber ?: return null
        val r = runner ?: return null
        t.clearPause(job.id)
        _state.value = SubtitleTranscribeUi(job.project)
        return scope.launch { follow(r, t, job.project, r.enqueue(job)) }
    }

    /** Stops the job; the subtitles do not change (saved chunks stay in the project's cache). */
    fun cancel(job: AiJob): Job? {
        val r = runner ?: return null
        transcriber?.clearPause(job.id)
        return scope.launch { r.cancel(job.id); say(job.project, UiNotice(UiText.SUB_TRANSCRIBE_CANCELLED)) }
    }

    private suspend fun follow(r: AiJobRunner, t: VideoTranscriber, dir: String, id: String) {
        val end = r.jobs.first { list -> list.firstOrNull { it.id == id }?.state?.let { it !in ACTIVE } ?: true }.firstOrNull { it.id == id }
        when (end?.state) {
            AiJobState.DONE -> t.resultLines(dir, id)?.let { say(dir, UiNotice(UiText.SUB_TRANSCRIBE_DONE, "n" to it), ok = true) }
            null, AiJobState.CANCELLED -> say(dir, UiNotice(UiText.SUB_TRANSCRIBE_CANCELLED))
            else -> Unit // PAUSED / FAILED: the job card says why and offers Resume
        }
    }

    private fun say(dir: String, notice: UiNotice, ok: Boolean = false): Job? { _state.value = SubtitleTranscribeUi(dir, notice = notice, ok = ok); return null }

    private companion object {
        val ACTIVE = setOf(AiJobState.QUEUED, AiJobState.RUNNING)
        val OPEN = ACTIVE + setOf(AiJobState.PAUSED, AiJobState.FAILED)
    }
}
