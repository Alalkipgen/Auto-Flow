package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ai.fake.FakeTtsProvider
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.FitStatus
import io.github.alalkipgen.autoflow.core.recap.Look
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapFit
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.render.BatteryReader
import io.github.alalkipgen.autoflow.media.render.BatteryReading
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.ui.export.ExportModel
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/** Movie Recap slice (d): fit-check settings and the recap render from the Export screen (docs/specs/recap.md §6). */
class RecapExportTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val settings = SettingsStore(fs, "/a/settings.json")
    private val tts = FakeTtsProvider()
    private val dir = "/p/R.autoflow"

    private class Queue : RenderQueue {
        val jobs = mutableListOf<RenderJob>()
        override fun enqueue(job: RenderJob): String { jobs += job; return "job${jobs.size}" }
        override fun observe(id: String): Flow<RenderState> = MutableStateFlow(RenderState.Queued)
        override fun cancel(id: String) {}
    }

    private val queue = Queue()

    private fun recap() = current.state.value!!.project.recap!!

    private fun recapModel(scope: kotlinx.coroutines.CoroutineScope) = RecapModel(session, fs, null, null, scope, tts = tts)

    private fun exportModel(scope: kotlinx.coroutines.CoroutineScope) =
        ExportModel(session, settings, fs, queue, null, BatteryReader { BatteryReading(80, charging = true) }, scope)

    private fun open(m: RecapModel) {
        val p = (store.create(dir, "R", Mode.MOVIE_RECAP) as CoreResult.Ok).value
        val scenes = listOf(RecapScene(0, 4_000, picked = true), RecapScene(4_000, 9_000, picked = true))
        current.set(OpenProject(dir, p.copy(recap = Recap(rightsConfirmed = true, movie = RecapMovie("source/movie.mp4", 9, 9_000), scenes = scenes))))
        m.refresh()
        assertTrue(m.writeManually())
        m.saveSegment(0, "ကောင်လေး စောင့်နေတယ်")
        m.saveSegment(1, "သူငယ်ချင်း ရောက်လာတယ်")
    }

    @Test
    fun fitSettingsAreSaved() = runTest {
        val m = recapModel(this)
        open(m)
        m.setMovieVolume(0.0)
        assertEquals(0.0, recap().movieVolume)
        m.toggleLook(Look.WARM)
        m.toggleLook(Look.MIRROR)
        assertEquals(listOf(Look.MIRROR, Look.WARM), recap().looks, "fixed order")
        m.toggleLook(Look.WARM)
        assertEquals(listOf(Look.MIRROR), recap().looks)
        m.setBurnCaptions(false)
        assertFalse(recap().burnCaptions)
    }

    @Test
    fun exportWaitsForAVoiceThenRendersTheRecap() = runTest {
        val m = recapModel(this)
        open(m)
        val e = exportModel(backgroundScope)
        val waiting = assertNotNull(e.state.value.recap)
        assertEquals("Approve the script first", waiting.blocker)
        assertFalse(e.state.value.canRender)
        e.render(Quality.PREVIEW)
        assertTrue(queue.jobs.isEmpty())

        assertTrue(m.approveScript())
        m.speak(0)!!.join()
        val rows = RecapFit.rows(recap(), m.state.value.script!!)
        assertEquals(FitStatus.NO_VOICE, rows[1].status)
        assertEquals("No voice", rows[1].label())
        assertTrue(fitFooter(recap(), rows).endsWith("· 1 of 2 voiced"))

        e.refresh()
        val ready = assertNotNull(e.state.value.recap)
        assertNull(ready.blocker)
        assertEquals(2, ready.segments)
        assertEquals(1, ready.voiced)
        assertTrue(e.state.value.canRender)

        e.render(Quality.EXPORT)
        val job = queue.jobs.single()
        assertEquals("exports/export", job.outputName)
        assertEquals(listOf("source/movie.mp4", "source/movie.mp4"), job.graph.clips.map { it.source })
        val cue = job.graph.voice!!.cues.single()
        assertTrue(cue.file.startsWith("artifacts/"), cue.file)
        assertEquals(RecapFit.LEAD_MS, cue.startMs)
        assertTrue(job.graph.overlays.isEmpty(), "no Burmese font set: .srt only")
        assertTrue(job.srt!!.contains("ကောင်လေး စောင့်နေတယ်"))
        assertNull(current.state.value!!.project.timeline, "a recap does not replace the music-video timeline")
    }
}
