package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.recap.SrtCue
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.CueIssue
import io.github.alalkipgen.autoflow.core.subtitles.DraftFile
import io.github.alalkipgen.autoflow.core.subtitles.EditRefusal
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleOps
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** S-3b editor model: draft, undo/redo, restore after a restart, Save → a new `st`, Discard; files are never deleted. */
class SubtitleEditorModelTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/app/projects.json"), current)
    private val dir = "/p/Clip.autoflow"
    private val first: ArtifactId

    init {
        val doc = SubtitleOps.fromCues(listOf(SrtCue(0, 2_000, "ဒီည ထွက်သွားရမယ်။\nမနက်ဖြန် ပြန်လာမယ်။"), SrtCue(2_500, 4_000, "Two"),
            SrtCue(5_000, 7_000, "Three")), "srt")
        val created = (store.create(dir, "Clip", Mode.SUBTITLES) as CoreResult.Ok).value
        first = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(),
            ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
        val project = created.copy(subtitles = SubtitlesTool(video = SourceVideo("source/movie.mp4", 9, 60_000, 1920, 1080, 25.0), current = first))
        current.set(OpenProject(dir, (store.save(dir, project) as CoreResult.Ok).value))
    }

    private fun model(scope: CoroutineScope) = SubtitleEditorModel(session, fs, scope).also { it.open() }
    private fun saved() = (store.open(dir) as CoreResult.Ok).value.subtitles!!.current!!
    private fun draftFile() = CoreJson.decodeFromString(DraftFile.serializer(), fs.read("$dir/.cache/st-draft.json").decodeToString())

    @Test
    fun editsAreKeptAsADraftAndOfferedAfterARestart() = runTest {
        val m = model(this)
        m.edit { SubtitleOps.split(it, "c1", 1_000) } // at the line break
        assertEquals(listOf("ဒီည ထွက်သွားရမယ်။", "မနက်ဖြန် ပြန်လာမယ်။"), m.state.value.draft!!.doc.cues.take(2).map { it.text })
        assertTrue(m.state.value.draft!!.dirty)
        advanceTimeBy(500); runCurrent()
        assertFalse(fs.exists("$dir/.cache/st-draft.json")) // debounced
        advanceTimeBy(600); runCurrent()
        assertEquals(m.state.value.draft!!.doc, draftFile().doc)

        val again = model(this)
        val offer = assertNotNull(again.state.value.offer)
        assertEquals(first, offer.base)
        again.edit { SubtitleOps.delete(it, "c2") } // blocked until Restore or Discard
        assertEquals(3, again.state.value.draft!!.doc.cues.size)
        again.restore()
        assertEquals(m.state.value.draft!!.doc, again.state.value.draft!!.doc)
        assertTrue(again.save())
        val second = saved()
        assertNotEquals(first, second)
        assertEquals(UiNotice(UiText.SUB_SAVED_EDIT, "n" to 4), again.state.value.notice)
        val doc = SubtitleVersions(session, fs, kotlin.time.Clock.System).read(dir, second)!!
        assertEquals(SubtitleDoc.SOURCE_EDIT to first, doc.source to doc.derivedFrom)
        assertTrue(fs.exists("$dir/artifacts/${first.value}.json")) // the old version stays
        assertNull(model(this).state.value.offer) // nothing unsaved any more
        assertFalse(again.state.value.draft!!.dirty)
    }

    @Test
    fun refusedEditsExplainAndUndoRedoFollowTheChecks() = runTest {
        val m = model(this)
        m.edit { SubtitleOps.merge(it, "c3") }
        assertEquals(UiNotice(UiText.SUB_NO_NEXT), m.state.value.notice)
        assertFalse(m.state.value.draft!!.dirty)
        m.edit { SubtitleOps.setTimes(it, "c2", 1_500, 4_000) }
        assertEquals(mapOf("c1" to setOf(CueIssue.OVERLAP)), m.state.value.issues)
        m.undo()
        assertEquals(emptyMap(), m.state.value.issues)
        assertFalse(m.state.value.draft!!.dirty)
        m.redo()
        assertEquals("c1", m.nextIssue())
        assertEquals("c1", m.nextIssue()) // wraps to the only one
        m.discard()
        assertFalse(m.state.value.draft!!.dirty)
        assertFalse(m.save()) // nothing to save
        assertEquals(first, saved())
        assertNull(model(this).state.value.offer)
    }

    @Test
    fun burmeseEditsAreTheOwnersAndAnEditedOriginalIsFlagged() = runTest {
        val m = model(this)
        m.edit { SubtitleOps.setTextMy(it, "c2", "နှစ်") }
        val c2 = m.state.value.draft!!.doc.cues[1]
        assertTrue(c2.myEdited)
        assertEquals(io.github.alalkipgen.autoflow.core.subtitles.myHash("Two"), c2.myFrom)
        assertEquals(setOf("c1", "c3"), m.state.value.issues.keys) // now translating: the others are not translated
        m.edit { SubtitleOps.setText(it, "c2", "Two!") }
        assertEquals(setOf(CueIssue.ORIGINAL_CHANGED), m.state.value.issues["c2"])
        m.setView(TextChoice.BURMESE)
        assertEquals(TextChoice.BURMESE, m.state.value.view)
    }

    @Test
    fun shiftLimitIsSaidInSeconds() {
        assertEquals(UiNotice(UiText.SUB_SHIFT_LIMIT, "s" to 1.2), SubtitleEditorModel.explain(EditRefusal.ShiftBeforeStart(-1_234)))
    }

    @Test
    fun shiftMovesAllOrFromALineAndRefusesBeforeZero() = runTest {
        val m = model(this)
        m.shift(100)
        assertEquals(listOf(100L, 2_600L, 5_100L), m.state.value.draft!!.doc.cues.map { it.startMs })
        assertEquals(UiNotice(UiText.SUB_SHIFTED, "lines" to 3, "s" to 0.1), m.state.value.notice)
        m.shift(-1_000, fromId = "c2") // c1 stays
        assertEquals(listOf(100L, 1_600L, 4_100L), m.state.value.draft!!.doc.cues.map { it.startMs })
        assertEquals(listOf(2_100L, 3_100L, 6_100L), m.state.value.draft!!.doc.cues.map { it.endMs })
        assertEquals(UiNotice(UiText.SUB_SHIFTED, "lines" to 2, "s" to -1.0), m.state.value.notice)
        val before = m.state.value.draft
        m.shift(-1_000) // c1 would start before 0:00
        assertEquals(UiNotice(UiText.SUB_SHIFT_LIMIT, "s" to 0.1), m.state.value.notice)
        assertEquals(before, m.state.value.draft)
        m.undo(); m.undo()
        assertFalse(m.state.value.draft!!.dirty)
    }
}
