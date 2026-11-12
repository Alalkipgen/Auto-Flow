package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.Scenes
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.shell.ShellModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Movie Recap slice (a): movie import, scenes, picks, thumbnails, shell locks (docs/specs/recap.md §2–3, §7). */
class RecapModelTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val picker = FakePicker(files = listOf("/dl/film.MP4"))
    val ffmpegArgs = mutableListOf<List<String>>()

    private class Locator : ToolLocator {
        override fun findFfmpeg(): String = "/bin/ffmpeg"
        override fun findVlc(): String? = null
        override fun isVlcDir(dir: String): Boolean = false
    }

    /** ffprobe: a 60 s 1920×1080 movie with sound; ffmpeg scdet: cuts at 10 s and 30.5 s; thumbs: writes the jpg. */
    private val runner = ToolRunner { cmd, _, _, _, onLine ->
        if (cmd[0].endsWith("ffprobe")) {
            ToolExec.Done(0, """{"streams":[{"codec_type":"video","codec_name":"h264","width":1920,"height":1080},{"codec_type":"audio","codec_name":"aac"}],"format":{"duration":"60"}}""", emptyList(), 1, false)
        } else {
            ffmpegArgs += cmd
            if ("-frames:v" in cmd) fs.writeDurably(cmd.last(), byteArrayOf(1, 2, 3))
            else {
                onLine("[scdet @ 0x1] lavfi.scd.score: 30.1, lavfi.scd.time: 10")
                onLine("out_time_us=30000000")
                onLine("progress=continue")
                onLine("[scdet @ 0x1] lavfi.scd.score: 22.0, lavfi.scd.time: 30.5")
            }
            ToolExec.Done(0, "", emptyList(), 1, false)
        }
    }

    private fun model(scope: kotlinx.coroutines.CoroutineScope) =
        RecapModel(session, fs, MediaTools(SettingsStore(fs, "/h/settings.json"), Locator(), runner), picker, scope)

    private fun openRecap() {
        val p = (store.create("/p/R.autoflow", "R", Mode.MOVIE_RECAP) as io.github.alalkipgen.autoflow.core.CoreResult.Ok).value
        current.set(OpenProject("/p/R.autoflow", p.copy(recap = Recap(rightsConfirmed = true))))
        fs.createDirectories("/dl")
        fs.writeDurably("/dl/film.MP4", ByteArray(2048) { 7 })
    }

    @Test
    fun importFindPickAndThumbnail() = runTest {
        openRecap()
        val m = model(this)
        m.importMovie()!!.join()
        val movie = assertNotNull(current.state.value?.project?.recap?.movie)
        assertEquals("source/movie.mp4", movie.file)
        assertEquals(60_000L, movie.durationMs)
        assertEquals(2048L, movie.sizeBytes)
        assertTrue(fs.exists("/p/R.autoflow/source/movie.mp4"))
        assertEquals("movie.mp4", m.state.value.movieName)

        m.findScenes()!!.join()
        val scenes = current.state.value!!.project.recap!!.scenes
        // 0–10, 10–30.5 (20.5 s → two halves), 30.5–60 (29.5 s → two halves)
        assertEquals(listOf(0L, 10_000L, 20_250L, 30_500L, 45_250L), scenes.map { it.startMs })
        assertTrue(ffmpegArgs.last().contains("scale=320:-2,scdet=threshold=${Scenes.THRESHOLD_NORMAL}"))
        assertEquals("Found 5 scenes. Tick the ones to tell the story with.", m.state.value.message)

        m.togglePick(1)
        m.togglePick(3)
        m.setTarget(60_000)
        val r = current.state.value!!.project.recap!!
        assertEquals(listOf(10_000L, 30_500L), r.picked.map { it.startMs })
        assertEquals(60_000L, r.targetMs)

        // Finding again keeps picks that start at the same time.
        m.findScenes(Scenes.THRESHOLD_FEWER)!!.join()
        assertEquals(listOf(10_000L, 30_500L), current.state.value!!.project.recap!!.picked.map { it.startMs })
        assertEquals(Scenes.THRESHOLD_FEWER, current.state.value!!.project.recap!!.sceneThreshold)

        val thumb = m.thumbnail(RecapScene(10_000, 20_000))
        assertEquals(3, thumb?.size)
        val calls = ffmpegArgs.size
        m.thumbnail(RecapScene(10_000, 20_000))
        assertEquals(calls, ffmpegArgs.size, "cached")
        assertTrue(fs.exists("/p/R.autoflow/.cache/thumbs/2048-10000.jpg"))

        m.clearPicks()
        assertTrue(current.state.value!!.project.recap!!.picked.isEmpty())
    }

    @Test
    fun withoutTheRightsTickNothingIsImported() = runTest {
        openRecap()
        current.set(current.state.value!!.copy(project = current.state.value!!.project.copy(recap = Recap())))
        val m = model(this)
        assertNull(m.importMovie())
        assertEquals("Tick the rights box (New Movie Recap) first", m.state.value.message)
    }

    @Test
    fun shellLocksTheMusicVideoScreensOfARecap() {
        openRecap()
        val shell = ShellModel(session).current()
        assertEquals(ShellModel.RECAP_LOCK, shell.locks["Storyboard"])
        assertEquals("Write the script first", shell.locks["Export"])
        assertNull(shell.locks["Movie Recap"])
        assertTrue(shell.steps.isEmpty())
    }
}
