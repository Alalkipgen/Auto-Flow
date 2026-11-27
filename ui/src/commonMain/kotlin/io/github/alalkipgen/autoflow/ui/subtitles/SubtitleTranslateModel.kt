package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.subtitles.TranslateName
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

/** What Translate will send (nothing is sent before Start): [lines] in [requests], the style, the names, "replace my edits". */
data class SubtitleTranslateConfirm(val lines: Int, val requests: Int, val style: String, val names: List<TranslateName>, val replaceEdited: Boolean)

data class SubtitleTranslateUi(val dir: String? = null, val confirm: SubtitleTranslateConfirm? = null, val notice: UiNotice? = null,
    val ok: Boolean = false, val retry: Boolean = false)

/**
 * Translate to Burmese in the Subtitles editor (specs/quick-tools.md §5, S-6b, D-81): the confirm card, then the
 * [SubtitleTranslateJob] of this project on the app's runner. Only offered when the lines are not Burmese already
 * ([offers]); nothing is ever translated without Start. While the job is open the editor is read-only ([locks]).
 * Done makes one new version that the editor opens; lines of a failed batch say so and Retry sends only them.
 */
class SubtitleTranslateModel(
    private val session: ProjectSession,
    private val translate: SubtitleTranslateJob?,
    private val runner: AiJobRunner?,
    private val config: ToolsConfig,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SubtitleTranslateUi())
    val state: StateFlow<SubtitleTranslateUi> = _state.asStateFlow()
    val jobs: StateFlow<List<AiJob>> = runner?.jobs ?: MutableStateFlow(emptyList())
    private var plan: SubtitleTranslateJob.Plan? = null
    private val names = mutableMapOf<String, List<TranslateName>>()

    /** "Translate to Burmese" is offered for lines whose language is not Burmese (unknown counts as not Burmese). */
    fun offers(lang: String?): Boolean = lang?.lowercase()?.substringBefore('-') !in BURMESE

    /** The unfinished Translate job of project [dir] in [list] (running, paused or failed), if any. */
    fun jobOf(dir: String?, list: List<AiJob>): AiJob? =
        list.lastOrNull { it.kind == AiJobKind.SUB_TRANSLATE && it.project == dir && it.state in OPEN }

    /** "Translate to Burmese" (and Retry after a failed batch): counts the lines without Burmese, then shows the confirm card. */
    fun ask(): Job? {
        val open = session.open ?: return null
        val t = translate?.takeIf { it.available && runner != null } ?: return say(open.dir, UiNotice(UiText.GEMINI_UNAVAILABLE))
        if (jobOf(open.dir, jobs.value) != null) return say(open.dir, UiNotice(UiText.JOB_ALREADY_RUNNING))
        val p = t.plan(open.dir, replaceEdited = false) ?: return null
        if (p.lines == 0 && (t.plan(open.dir, replaceEdited = true)?.lines ?: 0) == 0) return say(open.dir, UiNotice(UiText.SUB_TRANSLATE_NOTHING), ok = true)
        plan = p
        _state.value = SubtitleTranslateUi(open.dir, confirm = SubtitleTranslateConfirm(p.lines, p.requests, config.translate.style,
            names[open.dir] ?: emptyList(), replaceEdited = false))
        return null
    }

    fun setStyle(style: String) = edit { it.copy(style = style) }

    /** "Also replace my edits" (off by default): hand-edited Burmese is sent again; the counts follow. */
    fun setReplaceEdited(on: Boolean) {
        val dir = _state.value.dir ?: return
        val p = translate?.plan(dir, on) ?: return
        plan = p
        edit { it.copy(lines = p.lines, requests = p.requests, replaceEdited = on) }
    }

    fun addName() = edit { it.copy(names = it.names + TranslateName("", "")) }
    fun setName(i: Int, name: TranslateName) = edit { c -> c.copy(names = c.names.mapIndexed { k, n -> if (k == i) name else n }) }
    fun removeName(i: Int) = edit { c -> c.copy(names = c.names.filterIndexed { k, _ -> k != i }) }

    /** Back on the confirm card: nothing is sent. */
    fun dismiss() { plan = null; _state.value = SubtitleTranslateUi(_state.value.dir) }

    /** Start on the confirm card: the job of this project; the Burmese arrives as one new version when every batch is in. */
    fun start(): Job? {
        val c = _state.value.confirm?.takeIf { it.lines > 0 } ?: return null
        val p = plan ?: return null
        val dir = _state.value.dir ?: return null
        val t = translate ?: return null
        val r = runner ?: return null
        val kept = c.names.map { TranslateName(it.from.trim(), it.my.trim()) }.filter { it.from.isNotEmpty() && it.my.isNotEmpty() }
        names[dir] = kept
        plan = null
        _state.value = SubtitleTranslateUi(dir)
        return scope.launch { follow(r, t, dir, t.enqueue(r, dir, p, c.style, kept, c.replaceEdited)) }
    }

    /** Sends only the batches not saved yet. */
    fun resume(job: AiJob): Job? {
        val t = translate ?: return null
        val r = runner ?: return null
        _state.value = SubtitleTranslateUi(job.project)
        return scope.launch { follow(r, t, job.project, r.enqueue(job)) }
    }

    /** Stops the job; the subtitles do not change and editing comes back. */
    fun cancel(job: AiJob): Job? {
        val r = runner ?: return null
        return scope.launch { r.cancel(job.id); say(job.project, UiNotice(UiText.SUB_TRANSLATE_CANCELLED)) }
    }

    private suspend fun follow(r: AiJobRunner, t: SubtitleTranslateJob, dir: String, id: String) {
        val end = r.jobs.first { list -> list.firstOrNull { it.id == id }?.state?.let { it !in ACTIVE } ?: true }.firstOrNull { it.id == id }
        when (end?.state) {
            AiJobState.DONE -> t.result(dir, id)?.let {
                if (it.failed > 0) _state.value = SubtitleTranslateUi(dir, notice = UiNotice(UiText.SUB_TRANSLATE_NOT_ALL, "n" to it.failed), retry = true)
                else say(dir, UiNotice(UiText.SUB_TRANSLATE_DONE, "n" to it.translated), ok = true)
            }
            null, AiJobState.CANCELLED -> say(dir, UiNotice(UiText.SUB_TRANSLATE_CANCELLED))
            else -> Unit // PAUSED / FAILED: the job card says why and offers Resume
        }
    }

    private fun edit(f: (SubtitleTranslateConfirm) -> SubtitleTranslateConfirm) {
        _state.value.confirm?.let { _state.value = _state.value.copy(confirm = f(it)) }
    }

    private fun say(dir: String, notice: UiNotice, ok: Boolean = false): Job? { _state.value = SubtitleTranslateUi(dir, notice = notice, ok = ok); return null }

    companion object {
        private val BURMESE = setOf("my", "mya", "bur")
        private val ACTIVE = setOf(AiJobState.QUEUED, AiJobState.RUNNING)
        private val OPEN = ACTIVE + setOf(AiJobState.PAUSED, AiJobState.FAILED)
    }
}
