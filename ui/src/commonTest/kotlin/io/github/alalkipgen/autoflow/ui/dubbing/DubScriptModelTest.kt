package io.github.alalkipgen.autoflow.ui.dubbing

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.dubbing.DubBlock
import io.github.alalkipgen.autoflow.core.dubbing.DubRules
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.projects.StepClock
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitleVersions
import kotlin.test.*

/** D-1b (specs/dubbing.md §2): spoken-line edits are new `st` versions, and any change after Approve clears it. */
class DubScriptModelTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/app/projects.json", StepClock()), current)
    private val dir = "/p/Dub.autoflow"
    private val doc = SubtitleDoc(source = "srt", nextId = 3, cues = listOf(
        SubtitleCue("c1", 0, 1_500, "Hello.", textMy = "မင်္ဂလာပါ။"),
        SubtitleCue("c2", 2_000, 4_000, "How are you?", textMy = "နေကောင်းလား။"),
    ))

    private fun open(d: SubtitleDoc = doc, durationMs: Long = 8_000): DubScriptModel {
        val created = (store.create(dir, "Dub", Mode.DUBBING) as CoreResult.Ok).value
        val st = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), d).encodeToByteArray(),
            ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
        val p = (store.save(dir, created.copy(dubbing = DubbingTool(), subtitles = SubtitlesTool(
            video = SourceVideo("source/movie.mp4", 9, durationMs, 1920, 1080, 25.0), current = st))) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
        return DubScriptModel(session, fs).also { it.open() }
    }

    private fun project() = current.state.value!!.project
    private fun currentDoc() = SubtitleVersions(session, fs, kotlin.time.Clock.System).read(dir, project().subtitles!!.current!!)!!

    @Test fun rowsShowTimesSlotSubtitleAndSpokenLine() {
        val m = open()
        val rows = m.rows(current.state.value, m.state.value.doc!!)
        assertEquals(listOf(1_900L, 6_000L), rows.map { it.slotMs }) // next start − 100 ms gap; the last runs to the video end
        assertEquals("နေကောင်းလား။", rows[1].spoken)
        assertFalse(rows[1].own)
        assertEquals(DubStep.SCRIPT, DubStep.first(m.gates(current.state.value, m.state.value.doc)))
    }

    @Test fun approveThenEditingASpokenLineSavesANewVersionAndClearsTheApproval() {
        val m = open()
        val first = project().subtitles!!.current
        assertTrue(m.approve())
        assertTrue(DubRules.isApproved(project().dubbing, currentDoc()))
        assertTrue(m.setSpoken("c2", "နေကောင်းရဲ့လား")) // shortened spoken line
        assertNotEquals(first, project().subtitles!!.current)
        assertEquals(first, currentDoc().derivedFrom) // the old version stays; only the pointer moved
        assertEquals("နေကောင်းရဲ့လား", currentDoc().cues[1].spokenMy)
        assertEquals("နေကောင်းလား။", currentDoc().cues[1].textMy) // the subtitle text is untouched
        assertNull(project().dubbing!!.scriptApproved)
        assertEquals(DubBlock.NOT_APPROVED, m.gates(current.state.value, m.state.value.doc).voice)

        assertTrue(m.approve())
        assertTrue(m.useSubtitle("c2")) // follow the subtitle text again
        assertNull(currentDoc().cues[1].spokenMy)
        assertNull(project().dubbing!!.scriptApproved)
    }

    @Test fun unchangedTextSavesNothingAndKeepsTheApproval() {
        val m = open()
        assertTrue(m.approve())
        val before = project()
        assertTrue(m.setSpoken("c1", " မင်္ဂလာပါ။ ")) // equal to the subtitle text = still following it
        assertEquals(before, project())
    }

    @Test fun aSubtitleEditElsewhereAlsoClearsTheApproval() {
        val m = open()
        assertTrue(m.approve())
        SubtitleVersions(session, fs, kotlin.time.Clock.System).save(dir, doc.copy(cues = listOf(doc.cues[0], doc.cues[1].copy(textMy = "ဘယ်လိုလဲ။"))))
        assertNull(project().dubbing!!.scriptApproved) // the line follows the subtitle text, so its spoken line changed
    }

    @Test fun approveIsRefusedWhileALineIsNotTranslatedOrTheVideoIsTooLong() {
        val m = open(doc.copy(cues = listOf(doc.cues[0], doc.cues[1].copy(textMy = null))))
        val g = m.gates(current.state.value, m.state.value.doc)
        assertEquals(DubBlock.NOT_TRANSLATED, g.script)
        assertEquals(1, g.notTranslated)
        assertEquals(DubStep.TRANSLATE, DubStep.first(g))
        assertFalse(m.approve())
        assertNull(project().dubbing!!.scriptApproved)

        session.update(dir, project().id) { it.copy(subtitles = it.subtitles!!.copy(video = it.subtitles!!.video!!.copy(durationMs = 600_001))) }
        assertEquals(DubBlock.TOO_LONG, m.gates(current.state.value, m.state.value.doc).video) // over `dubbing.maxSourceMs`
        assertEquals(DubStep.VIDEO, DubStep.first(m.gates(current.state.value, m.state.value.doc)))
    }
}
