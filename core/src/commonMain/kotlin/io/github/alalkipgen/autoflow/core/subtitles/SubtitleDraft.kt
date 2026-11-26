package io.github.alalkipgen.autoflow.core.subtitles

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Unsaved edits of one `st` version (specs/quick-tools.md §5). [base] is the version the edits started from;
 * [doc] the edited document. Undo/redo keep the last [LIMIT] steps in memory only; the draft file keeps [doc].
 */
class SubtitleDraft private constructor(
    val base: ArtifactId?,
    private val saved: SubtitleDoc,
    val doc: SubtitleDoc,
    private val undos: List<SubtitleDoc>,
    private val redos: List<SubtitleDoc>,
) {
    /** The document differs from the saved version. */
    val dirty: Boolean get() = doc != saved
    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()

    /**
     * How many lines the unsaved edits touch (Group 5 🆕-6, the sticky caption): lines that are new or differ from the
     * saved version, plus saved lines that were deleted. Read-only; 0 when not [dirty].
     */
    val changedLines: Int get() {
        if (doc == saved) return 0
        val before = saved.cues.associateBy { it.id }
        val now = doc.cues.mapTo(HashSet()) { it.id }
        return doc.cues.count { before[it.id] != it } + saved.cues.count { it.id !in now }
    }

    /** Applies [edit]; a refused edit leaves the draft as it was and is returned for the UI to explain. */
    fun apply(edit: (SubtitleDoc) -> Edit): Pair<SubtitleDraft, EditRefusal?> = when (val r = edit(doc)) {
        is Edit.Refused -> this to r.reason
        is Edit.Done -> (if (r.doc == doc) this else SubtitleDraft(base, saved, r.doc, (undos + doc).takeLast(LIMIT), emptyList())) to null
    }

    fun undo(): SubtitleDraft = if (undos.isEmpty()) this else SubtitleDraft(base, saved, undos.last(), undos.dropLast(1), redos + doc)
    fun redo(): SubtitleDraft = if (redos.isEmpty()) this else SubtitleDraft(base, saved, redos.last(), (undos + doc).takeLast(LIMIT), redos.dropLast(1))

    /** Discard: back to the saved version, without history. */
    fun revert(): SubtitleDraft = of(base, saved)

    /** The document to save as a new `st`: marked as an edit of [base]. */
    fun toSave(): SubtitleDoc = doc.copy(source = SubtitleDoc.SOURCE_EDIT, derivedFrom = base)

    fun toFile(savedAtMs: Long): DraftFile = DraftFile(base = base, savedAtMs = savedAtMs, doc = doc)

    companion object {
        const val LIMIT = 50

        fun of(base: ArtifactId?, doc: SubtitleDoc) = SubtitleDraft(base, doc, doc, emptyList(), emptyList())

        /** A draft file restored over [saved] (the version it started from); no undo history survives a restart. */
        fun restore(file: DraftFile, saved: SubtitleDoc) = SubtitleDraft(file.base, saved, file.doc, emptyList(), emptyList())
    }
}

/** `.cache/st-draft.json`: not an artifact, safe to delete (project-format.md `.cache/`). */
@Serializable
@SerialName("SubtitleDraftFile")
data class DraftFile(
    val schemaVersion: Int = 1,
    val base: ArtifactId?,
    val savedAtMs: Long,
    val doc: SubtitleDoc,
)
