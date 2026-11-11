package io.github.alalkipgen.autoflow.ui.input

import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardGenerator
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.FakeTools
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardService
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class SongImportTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val dir = "/p/Rain.autoflow"
    private val probed = mutableListOf<List<String>>()
    private var probeOutput = """{"streams":[{"codec_type":"audio","codec_name":"mp3"}],"format":{"duration":"205.432"}}"""

    private val runner = ToolRunner { cmd, _, _, _, _ ->
        probed += cmd
        ToolExec.Done(0, probeOutput, emptyList(), 5, timedOut = false)
    }

    private fun open() {
        val p = (store.create(dir, "Rain", Mode.MUSIC_VIDEO, ProjectSettings()) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
    }

    private fun file(path: String, bytes: ByteArray): String {
        fs.createDirectories(path.substringBeforeLast('/'))
        fs.writeDurably(path, bytes)
        return path
    }

    private fun TestScope.model(picker: FakePicker = FakePicker(), tools: FakeTools = FakeTools(ffmpeg = "/bin/ffmpeg")): InputModel {
        val jobs = StoryboardJobs(StoryboardService(session, StoryboardGenerator(FakeTextProvider())), backgroundScope)
        return InputModel(session, jobs, picker, MediaTools(SettingsStore(fs, "/a/settings.json"), tools, runner))
    }

    @Test
    fun importCopiesTheSongIntoSourceAndKeepsOnlyOne() {
        open()
        assertNull(session.songPath())
        val mp3 = byteArrayOf(1, 2, 3)
        assertEquals("$dir/source/song.mp3", (session.importSong(file("/cache/a.MP3", mp3)) as CoreResult.Ok).value)
        assertContentEquals(mp3, fs.read("$dir/source/song.mp3"))
        assertEquals("$dir/source/song.mp3", session.songPath())

        val wav = byteArrayOf(9, 9)
        assertEquals("$dir/source/song.wav", (session.importSong(file("/cache/b.wav", wav)) as CoreResult.Ok).value)
        assertEquals(listOf("song.wav"), fs.list("$dir/source").filter(ProjectSession::isSongName))
        assertContentEquals(mp3, fs.read("$dir/.cache/replaced/song.mp3"), "the old song is moved, not deleted")
        assertFalse(fs.exists("$dir/source/song.wav.tmp"))

        assertTrue(session.importSong(file("/cache/c.txt", wav)) is CoreResult.Err, "not audio")
        assertTrue(session.importSong(file("/cache/d.mp3", ByteArray(0))) is CoreResult.Err, "empty")
        assertEquals("$dir/source/song.wav", session.songPath())
    }

    @Test
    fun withoutAProjectNothingIsImported() {
        assertTrue(session.importSong(file("/cache/a.mp3", byteArrayOf(1))) is CoreResult.Err)
        assertNull(session.songPath())
    }

    @Test
    fun pickedSongSetsTheDurationFromFfprobe() = runTest {
        open()
        val m = model(FakePicker(files = listOf(file("/cache/x.m4a", byteArrayOf(4, 5)))))
        assertNull(m.state.value.songFile)
        m.importSong()
        val s = m.state.value
        assertEquals("song.m4a", s.songFile)
        assertEquals("3:25", s.durationText)
        assertEquals(205_432, s.settings.durationMs)
        assertEquals("Song imported · 3:25", s.songMessage)
        assertTrue(s.songMessageOk)
        assertFalse(s.importingSong)
        assertEquals(205_432, (store.open(dir) as CoreResult.Ok).value.settings.durationMs, "saved at once")
        assertEquals("/bin/ffprobe", probed.single().first())
        assertEquals("$dir/source/song.m4a", probed.single().last())
        assertEquals("song.m4a", model().state.value.songFile, "a new screen finds the song again")
    }

    @Test
    fun unreadableLengthKeepsTheTypedDuration() = runTest {
        open()
        probeOutput = "Invalid data found when processing input"
        val m = model()
        m.setDuration("2:00")
        m.importSongFile(file("/cache/x.mp3", byteArrayOf(1)))
        val s = m.state.value
        assertEquals("song.mp3", s.songFile)
        assertEquals(120_000, s.settings.durationMs)
        assertEquals(InputModel.SONG_NO_LENGTH, s.songMessage)
        assertFalse(s.songMessageOk)
    }

    @Test
    fun cancelledOrUnsupportedPickerChangesNothing() = runTest {
        open()
        val m = model(FakePicker())
        m.importSong()
        assertNull(m.state.value.songFile)
        assertNull(m.state.value.songMessage)
        val none = model(FakePicker(unsupported = true))
        none.importSong()
        assertEquals(InputModel.NO_PICKER, none.state.value.songMessage)
        assertTrue(probed.isEmpty())
    }

    @Test
    fun ffprobeIsFoundNextToFfmpeg() {
        assertEquals("C:\\ff\\bin\\ffprobe.exe", MediaTools.probeNextTo("C:\\ff\\bin\\ffmpeg.exe"))
        assertEquals("/n/lib/libffprobe.so", MediaTools.probeNextTo("/n/lib/libffmpeg.so"))
        assertEquals("ffprobe", MediaTools.probeNextTo("ffmpeg"))
        val bundled = FakeTools(ffmpeg = "/n/libffmpeg.so", bundled = true)
        assertEquals("/n/libffprobe.so", MediaTools(SettingsStore(fs, "/a/s.json"), bundled, runner).ffprobe())
    }
}
