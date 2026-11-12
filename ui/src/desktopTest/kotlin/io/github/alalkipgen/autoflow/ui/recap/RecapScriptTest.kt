package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.recap.RecapScriptGenerator
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapRules
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.RecapScript
import io.github.alalkipgen.autoflow.core.recap.RecapStyle
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Movie Recap slice (b): summary, subtitles, Gemini / manual script, edit, approve (docs/specs/recap.md §4). */
class RecapScriptTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val picker = FakePicker(files = listOf("/dl/film.srt"))
    private val text = FakeTextProvider()

    private fun model(scope: kotlinx.coroutines.CoroutineScope) =
        RecapModel(session, fs, null, picker, scope, RecapScriptGenerator(text))

    private fun open() {
        val p = (store.create("/p/R.autoflow", "R", Mode.MOVIE_RECAP) as CoreResult.Ok).value
        val scenes = listOf(RecapScene(0, 10_000), RecapScene(10_000, 20_000, picked = true), RecapScene(20_000, 30_000, picked = true))
        current.set(OpenProject("/p/R.autoflow", p.copy(recap = Recap(rightsConfirmed = true, movie = RecapMovie("source/movie.mp4", 9, 30_000), scenes = scenes))))
        fs.createDirectories("/dl")
        fs.writeDurably("/dl/film.srt", "\uFEFF1\n00:00:11,000 --> 00:00:12,500\nWhere are you?\n\n2\n00:00:21,000 --> 00:00:22,000\nHere.\n".encodeToByteArray())
    }

    private fun recap() = current.state.value!!.project.recap!!

    @Test
    fun geminiScriptEditAndApprove() = runTest {
        open()
        val m = model(this)
        assertNull(m.writeScript())
        assertEquals("Type a summary or import subtitles (.srt) first", m.state.value.message)

        m.setSummary("A boy waits.")
        assertEquals("A boy waits.", session.readSource(RecapModel.SUMMARY_FILE))
        m.importSubtitles()!!.join()
        assertEquals("source/subtitles.srt", recap().subtitles)
        assertEquals("Subtitles ready: 2 lines.", m.state.value.message)
        m.setStyle(RecapStyle.COMMENTARY)

        m.writeScript()!!.join()
        val script = assertNotNull(m.state.value.script)
        assertEquals(RecapScript.SOURCE_GEMINI, script.source)
        assertEquals(RecapStyle.COMMENTARY, script.style)
        assertEquals(listOf(10_000L to 20_000L, 20_000L to 30_000L), script.segments.map { it.startMs to it.endMs })
        assertTrue(script.segments.all { it.textMy.isNotBlank() })
        assertFalse(recap().scriptApproved)
        assertNull(m.state.value.job)

        assertTrue(m.saveSegment(0, "  ကောင်လေး စောင့်နေတယ်  "))
        assertEquals("ကောင်လေး စောင့်နေတယ်", m.state.value.script!!.segments[0].textMy)
        assertTrue(m.approveScript())
        assertTrue(recap().scriptApproved)
        assertTrue(RecapRules.canVoice(recap(), m.state.value.script).allowed)

        // Editing again clears the approval; changing the picks makes the script outdated.
        assertTrue(m.saveSegment(1, "ဒီမှာ"))
        assertFalse(recap().scriptApproved)
        m.togglePick(0)
        assertFalse(m.approveScript())
        assertEquals("The picked scenes changed: write the script again", m.state.value.message)
    }

    @Test
    fun writeItMyselfStartsEmptyAndCannotBeApprovedBlank() = runTest {
        open()
        val m = model(this)
        assertTrue(m.writeManually())
        val script = assertNotNull(m.state.value.script)
        assertEquals(RecapScript.SOURCE_MANUAL, script.source)
        assertEquals(2, script.segments.size)
        assertFalse(m.approveScript())
        assertEquals("Every picked scene needs narration text", m.state.value.message)
        m.saveSegment(0, "က")
        m.saveSegment(1, "ခ")
        assertTrue(m.approveScript())
        assertEquals(0, text.calls)
    }
}
