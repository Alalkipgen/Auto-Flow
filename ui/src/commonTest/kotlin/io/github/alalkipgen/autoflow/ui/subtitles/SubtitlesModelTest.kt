package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.probe.MediaInfo
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.projects.StepClock
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest

/** S-1b create-on-pick (specs/quick-tools.md §3): Cancel and non-videos create nothing; the app never deletes. */
class SubtitlesModelTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val index = ProjectIndex(fs, "/app/projects.json", StepClock())
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, index, current)
    private val picker = FakePicker()
    private val home = "/ext/projects"
    private val dashboard = DashboardModel(fs, store, index, SettingsStore(fs, "/app/settings.json"), picker, current, projectsHome = home)
    private val video = MediaInfo(durationMs = 65_000, width = 1920, height = 1080, fps = 29.97, videoCodec = "h264", audioCodec = "aac")
    private val noon = object : Clock { override fun now() = Instant.parse("2026-11-20T05:30:00Z") }

    private fun model(scope: CoroutineScope, probe: (String) -> MediaInfo?) =
        SubtitlesModel(session, fs, dashboard, picker, scope, probe = { probe(it) }, clock = noon, offsetMs = { 23_400_000 })

    private fun pick(path: String = "/dl/My clip.mp4"): ByteArray {
        val bytes = "video-bytes".encodeToByteArray()
        fs.createDirectories(path.substringBeforeLast('/'))
        fs.writeDurably(path, bytes)
        picker.files = listOf(path)
        return bytes
    }

    @Test
    fun cancelCreatesNothing() = runTest {
        picker.files = emptyList()
        var opened = false
        model(this) { video }.start { opened = true }!!.join()
        assertFalse(opened)
        assertTrue(index.entries.value.isEmpty())
        assertFalse(fs.exists(home))
    }

    @Test
    fun aFileWithoutPictureCreatesNothingAndHomeSaysWhy() = runTest {
        pick("/dl/song.mp4")
        val m = model(this) { video.copy(videoCodec = null) }
        m.start {}!!.join()
        assertTrue(index.entries.value.isEmpty())
        assertNull(m.state.value.dir)
        assertEquals(UiText.MOVIE_NO_PICTURE, m.state.value.notice?.key)
    }

    @Test
    fun pickedVideoBecomesAnOpenSubtitlesProjectWithItsFacts() = runTest {
        val bytes = pick()
        var opened = 0
        val m = model(this) { video }
        m.start { opened++ }!!.join()
        val open = current.state.value!!
        assertEquals(1, opened)
        assertEquals(Mode.SUBTITLES, open.project.mode)
        assertEquals("My clip · 2026-11-20 12:00", open.project.name) // local time (+6:30), not UTC
        assertEquals("$home/My clip · 2026-11-20 12_00.autoflow", open.dir)
        assertEquals(SourceVideo("source/movie.mp4", bytes.size.toLong(), 65_000, 1920, 1080, 29.97, hasAudio = true), open.project.subtitles?.video)
        assertContentEquals(bytes, fs.read("${open.dir}/source/movie.mp4"))
        assertEquals(open.project, store.open(open.dir).let { (it as io.github.alalkipgen.autoflow.core.CoreResult.Ok).value })
        assertEquals(listOf(Mode.SUBTITLES), index.entries.value.map { it.mode })
        assertTrue(m.state.value.ok && m.state.value.dir == open.dir)
        assertEquals(UiText.SUBTITLES_VIDEO_READY, m.state.value.notice?.key)

        m.start {}!!.join() // same file, same minute: a free name, never a clash
        assertEquals("My clip · 2026-11-20 12:00 2", current.state.value!!.project.name)
    }

    @Test
    fun unreadableAfterTheCopyKeepsTheProjectWithTheError() = runTest {
        pick()
        val m = model(this) { null } // e.g. no ffprobe here
        m.start {}!!.join()
        val open = current.state.value!!
        assertTrue(fs.exists("${open.dir}/source/movie.mp4"))
        assertNull(open.project.subtitles?.video)
        assertEquals(open.dir, m.state.value.dir)
        assertEquals(UiText.MOVIE_UNREADABLE, m.state.value.notice?.key)
        assertEquals(1, index.entries.value.size)
    }

    @Test
    fun localStampUsesTheDeviceOffset() {
        val t = Instant.parse("2026-12-31T20:15:00Z")
        assertEquals("2027-01-01 02:45", localStamp(t) { 23_400_000 })
        assertEquals("2026-12-31 20:15", localStamp(t) { 0 })
    }
}
