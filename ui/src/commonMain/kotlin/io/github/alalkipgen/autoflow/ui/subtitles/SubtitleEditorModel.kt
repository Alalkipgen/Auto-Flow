package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.subtitles.CueIssue
import io.github.alalkipgen.autoflow.core.subtitles.DraftFile
import io.github.alalkipgen.autoflow.core.subtitles.Edit
import io.github.alalkipgen.autoflow.core.subtitles.EditRefusal
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleChecks
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDraft
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleOps
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The editor of one Subtitles project (specs/quick-tools.md §5). [offer]: unsaved edits of the current version from
 * an earlier session (Restore · Discard). [notice]: why an edit was refused, or what was saved.
 */
data class SubtitleEditorUi(
    val dir: String? = null,
    val draft: SubtitleDraft? = null,
    val issues: Map<String, Set<CueIssue>> = emptyMap(),
    val view: TextChoice = TextChoice.ORIGINAL,
    val selected: String? = null,
    val offer: DraftFile? = null,
    val notice: UiNotice? = null,
    val ok: Boolean = false,
)

/**
 * Edits go to a [SubtitleDraft]; the draft file `.cache/st-draft.json` is written [persistDelayMs] after the last
 * edit (not an artifact). Save writes a new `st` and only moves `project.subtitles.current`. The app deletes no
 * file: after Save or Discard the draft file holds the saved document, which is never offered back.
 */
class SubtitleEditorModel(
    private val session: ProjectSession,
    private val fs: FileSystem,
    private val scope: CoroutineScope,
    val config: ToolsConfig = ToolsConfig(),
    private val clock: Clock = Clock.System,
    private val persistDelayMs: Long = 1_000,
) {
    private val versions = SubtitleVersions(session, fs, clock)
    private val _state = MutableStateFlow(SubtitleEditorUi())
    val state: StateFlow<SubtitleEditorUi> = _state.asStateFlow()
    private var persist: Job? = null

    /** Loads the current version of the open project (again after a source change); a matching draft is offered. */
    fun open() {
        val open = session.open ?: return
        val current = open.project.subtitles?.current ?: return run { _state.value = SubtitleEditorUi(open.dir) }
        val known = _state.value
        if (known.dir == open.dir && known.draft?.base == current) return
        val doc = versions.read(open.dir, current) ?: return run { _state.value = SubtitleEditorUi(open.dir) }
        val file = readDraft(open.dir)?.takeIf { it.base == current && it.doc != doc }
        _state.value = SubtitleEditorUi(open.dir, SubtitleDraft.of(current, doc), offer = file).withIssues()
    }

    fun restore() = update { s -> s.offer?.let { s.copy(draft = SubtitleDraft.restore(it, s.draft!!.doc), offer = null, notice = null) } ?: s }

    /** Discards the offered draft: the file is overwritten with the saved version. */
    fun dismissOffer() = update { s -> s.copy(offer = null).also { writeDraft(it) } }

    /** Applies [op] to the draft; a refusal only explains (the draft is unchanged). */
    fun edit(op: (SubtitleDoc) -> Edit) {
        val s = _state.value
        val draft = s.draft ?: return
        if (s.offer != null) return // Restore or Discard first
        val (next, refused) = draft.apply(op)
        _state.value = s.copy(draft = next, notice = refused?.let(::explain), ok = false).withIssues()
        if (next !== draft) schedulePersist()
    }

    /** Split here: at [atMs] when it is inside the line (the playhead), else in proportion to the line break. */
    fun splitAt(id: String, atMs: Long? = null) = edit { doc ->
        val c = doc.cues.firstOrNull { it.id == id } ?: return@edit Edit.Refused(EditRefusal.NoSuchLine)
        val cut = c.text.indexOf('\n')
        val at = atMs?.takeIf { it > c.startMs && it < c.endMs }
            ?: if (cut > 0) c.startMs + (c.endMs - c.startMs) * cut / c.text.length else (c.startMs + c.endMs) / 2
        SubtitleOps.split(doc, id, at.coerceIn(c.startMs + 1, (c.endMs - 1).coerceAtLeast(c.startMs + 1)))
    }

    /** A line the owner typed that cannot be used (e.g. a time that is not a time); nothing changes. */
    fun say(notice: UiNotice) = update { it.copy(notice = notice, ok = false) }

    fun undo() = step { it.undo() }
    fun redo() = step { it.redo() }
    fun setView(view: TextChoice) = update { it.copy(view = view).withIssues() }
    fun select(id: String?) = update { it.copy(selected = id) }

    /** Selects the next line with an issue after the selected one (wrapping); null when there is none. */
    fun nextIssue(): String? {
        val s = _state.value
        val cues = s.draft?.doc?.cues ?: return null
        val flagged = cues.map { it.id }.filter { it in s.issues }
        if (flagged.isEmpty()) return null
        val at = cues.indexOfFirst { it.id == s.selected }
        val next = flagged.firstOrNull { id -> cues.indexOfFirst { it.id == id } > at } ?: flagged.first()
        select(next)
        return next
    }

    /** Back to the saved version (Discard). */
    fun discard() {
        persist?.cancel()
        update { s -> s.draft?.let { d -> s.copy(draft = d.revert(), notice = null, ok = false).withIssues().also(::writeDraft) } ?: s }
    }

    /** Saves the draft as a new `st` version. */
    fun save(): Boolean {
        val s = _state.value
        val dir = s.dir ?: return false
        val draft = s.draft?.takeIf { it.dirty && s.offer == null } ?: return false
        persist?.cancel()
        return when (val r = versions.save(dir, draft.toSave())) {
            is CoreResult.Ok -> {
                _state.value = s.copy(draft = SubtitleDraft.of(r.value.first, r.value.second), ok = true,
                    notice = UiNotice(UiText.SUB_SAVED_EDIT, "n" to r.value.second.cues.size)).withIssues().also(::writeDraft)
                true
            }
            is CoreResult.Err -> { _state.value = s.copy(notice = UiNotice(UiText.RESULT_DETAIL, "detail" to r.error.message), ok = false); false }
        }
    }

    private fun step(change: (SubtitleDraft) -> SubtitleDraft) {
        val s = _state.value
        val draft = s.draft ?: return
        if (s.offer != null) return
        val next = change(draft)
        if (next === draft) return
        _state.value = s.copy(draft = next, notice = null).withIssues()
        schedulePersist()
    }

    private fun update(change: (SubtitleEditorUi) -> SubtitleEditorUi) { _state.value = change(_state.value) }

    private fun SubtitleEditorUi.withIssues() = copy(issues = draft?.let { SubtitleChecks.check(it.doc, config.subtitles, view) }.orEmpty())

    private fun schedulePersist() {
        persist?.cancel()
        persist = scope.launch { delay(persistDelayMs); writeDraft(_state.value) }
    }

    private fun draftPath(dir: String) = joinPath(joinPath(dir, ".cache"), DRAFT_FILE)

    private fun readDraft(dir: String): DraftFile? = runCatching {
        CoreJson.decodeFromString(DraftFile.serializer(), fs.read(draftPath(dir)).decodeToString())
    }.getOrNull()

    private fun writeDraft(s: SubtitleEditorUi) {
        val dir = s.dir ?: return
        val draft = s.draft ?: return
        if (draft.base == null) return
        runCatching {
            fs.createDirectories(joinPath(dir, ".cache"))
            fs.writeDurably(draftPath(dir), CoreJson.encodeToString(DraftFile.serializer(), draft.toFile(clock.now().toEpochMilliseconds())).encodeToByteArray())
        }
    }

    companion object {
        const val DRAFT_FILE = "st-draft.json"

        fun explain(why: EditRefusal): UiNotice = when (why) {
            EditRefusal.NoSuchLine -> UiNotice(UiText.SUB_NO_LINE)
            EditRefusal.BadTimes -> UiNotice(UiText.SUB_BAD_TIMES)
            EditRefusal.SplitOutside -> UiNotice(UiText.SUB_SPLIT_OUTSIDE)
            EditRefusal.SplitNeedsTextCut -> UiNotice(UiText.SUB_SPLIT_WHERE)
            EditRefusal.NoNextLine -> UiNotice(UiText.SUB_NO_NEXT)
            is EditRefusal.ShiftBeforeStart -> UiNotice(UiText.SUB_SHIFT_LIMIT, "s" to (-why.largestMs / 100) / 10.0)
        }
    }
}
