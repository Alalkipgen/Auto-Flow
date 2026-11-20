package io.github.alalkipgen.autoflow.ui.dubbing

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.dubbing.DubBlock
import io.github.alalkipgen.autoflow.core.dubbing.DubRules
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitleVersions
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One Dub script row (specs/dubbing.md §2): number, times, slot, subtitle text and the spoken line. */
data class DubScriptRow(val n: Int, val id: String, val startMs: Long, val endMs: Long, val slotMs: Long,
    val textMy: String, val spoken: String, val own: Boolean)

/** [dir]'s current `st` as Dub script rows; [notice] is the last result line. */
data class DubScriptUi(val dir: String? = null, val base: io.github.alalkipgen.autoflow.core.model.ArtifactId? = null, val doc: SubtitleDoc? = null,
    val notice: UiNotice? = null, val ok: Boolean = false)

/** What the five steps show for the open project; the gates are `DubRules` (`:core`), the UI only says them. */
data class DubGates(val video: DubBlock?, val script: DubBlock?, val voice: DubBlock?, val notTranslated: Int, val approved: Boolean)

/**
 * The Dub script step (D-1b). Every change is a new immutable `st` version (D-79) through [SubtitleVersions], whose
 * same `project.json` write clears the approval when the spoken lines changed; Approve stores `DubRules.approve`.
 */
class DubScriptModel(private val session: ProjectSession, fs: FileSystem, private val config: ToolsConfig = ToolsConfig(),
    private val clock: Clock = Clock.System) {
    private val versions = SubtitleVersions(session, fs, clock)
    private val _state = MutableStateFlow(DubScriptUi())
    val state: StateFlow<DubScriptUi> = _state.asStateFlow()
    val maxMinutes: Long get() = config.dubbing.maxSourceMs / 60_000

    /** Reads the current `st` of the open Dubbing project (again only when the pointer moved). */
    fun open() {
        val open = session.open ?: return
        val current = open.project.subtitles?.current
        val known = _state.value
        if (known.dir == open.dir && known.base == current && (current == null || known.doc != null)) return
        val doc = current?.let { versions.read(open.dir, it) }
        _state.value = DubScriptUi(open.dir, current, doc, notice = if (current != null && doc == null) UiNotice(UiText.SUB_DOC_UNREADABLE) else null)
    }

    fun gates(open: OpenProject?, doc: SubtitleDoc?): DubGates {
        val video = open?.project?.subtitles?.video
        val max = config.dubbing.maxSourceMs
        val videoBlock = when {
            video == null -> DubBlock.NO_VIDEO
            DubRules.tooLong(video.durationMs, max) -> DubBlock.TOO_LONG
            else -> null
        }
        val script = DubRules.canOpenScript(video?.durationMs, max, doc)
        return DubGates(videoBlock, script, DubRules.canSpeak(video?.durationMs, max, open?.project?.dubbing, doc),
            doc?.let { DubRules.notTranslated(it).size } ?: 0, DubRules.isApproved(open?.project?.dubbing, doc))
    }

    fun rows(open: OpenProject?, doc: SubtitleDoc): List<DubScriptRow> {
        val slots = DubRules.slots(doc, open?.project?.subtitles?.video?.durationMs ?: 0, config.dubbing.minGapMs)
        return doc.cues.mapIndexed { i, c ->
            DubScriptRow(i + 1, c.id, c.startMs, c.endMs, slots[i], c.textMy?.trim().orEmpty(), DubRules.spoken(c), c.spokenMy != null)
        }
    }

    /** Sets line [id]'s spoken line (blank or equal to the subtitle text = follow it); unchanged text saves nothing. */
    fun setSpoken(id: String, text: String): Boolean {
        val (open, doc) = current() ?: return false
        val next = DubRules.setSpoken(doc, id, text)
        if (next == doc) return true
        return when (val r = versions.save(open.dir, next)) {
            is CoreResult.Ok -> true.also {
                _state.value = DubScriptUi(open.dir, session.open?.project?.subtitles?.current, r.value.second, UiNotice(UiText.DUB_LINE_SAVED), ok = true)
            }
            is CoreResult.Err -> false.also { say(UiNotice(UiText.RESULT_DETAIL, "detail" to r.error.message)) }
        }
    }

    /** "Use the subtitle text": the line follows `textMy` again. */
    fun useSubtitle(id: String): Boolean = setSpoken(id, "")

    /** Approve the dub script (`DUB_APPROVE`): refused by `DubRules` unless the script is open. */
    fun approve(): Boolean {
        val (open, doc) = current() ?: return false
        val video = open.project.subtitles?.video
        val at = clock.now().toEpochMilliseconds()
        if (DubRules.approve(open.project.dubbing ?: DubbingTool(), video?.durationMs, config.dubbing.maxSourceMs, doc, at) == null) return false
        val r = session.update(open.dir, open.project.id) { p ->
            val d = p.dubbing ?: DubbingTool()
            p.copy(dubbing = DubRules.approve(d, p.subtitles?.video?.durationMs, config.dubbing.maxSourceMs, doc, at) ?: d)
        }
        return when (r) {
            is CoreResult.Ok -> DubRules.isApproved(r.value.dubbing, doc).also { if (it) say(UiNotice(UiText.DUB_APPROVED), ok = true) }
            is CoreResult.Err -> false.also { say(UiNotice(UiText.RESULT_DETAIL, "detail" to r.error.message)) }
        }
    }

    /** The open project and its current `st`, only when this state is about exactly that version. */
    private fun current(): Pair<OpenProject, SubtitleDoc>? {
        open()
        val open = session.open ?: return null
        val s = _state.value
        return if (s.dir == open.dir && s.base == open.project.subtitles?.current && s.doc != null) open to s.doc else null
    }

    private fun say(notice: UiNotice, ok: Boolean = false) { _state.value = _state.value.copy(notice = notice, ok = ok) }
}
