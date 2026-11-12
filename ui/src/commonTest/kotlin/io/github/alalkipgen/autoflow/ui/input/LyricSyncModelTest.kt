package io.github.alalkipgen.autoflow.ui.input

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.Transcript
import io.github.alalkipgen.autoflow.ai.TranscribeProvider
import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.fake.FakeTranscribeProvider
import io.github.alalkipgen.autoflow.core.lyrics.LyricTimings
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardGenerator
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.lyrics.LyricLine
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class LyricSyncModelTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val dir = "/p/Rain.autoflow"

    private fun TestScope.model(transcribe: TranscribeProvider? = null): InputModel {
        val p = (store.create(dir, "Rain", Mode.MUSIC_VIDEO, ProjectSettings()) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
        val jobs = StoryboardJobs(StoryboardService(session, StoryboardGenerator(FakeTextProvider())), backgroundScope)
        return InputModel(session, jobs, transcribe = transcribe)
    }

    private fun song(name: String = "s.mp3") {
        fs.createDirectories("/c")
        fs.writeDurably("/c/$name", byteArrayOf(1, 2))
    }

    @Test
    fun tapSaveAndStatusFollowTheLyrics() = runTest {
        val m = model()
        assertFalse(m.startSync(), "no lyrics")
        assertEquals(LyricSyncModel.NO_LINES, m.sync.state.value.message)
        m.setLyrics("[Verse]\nမိုးတွေ\nရွာနေတယ်")
        assertFalse(m.startSync(), "no song")
        assertEquals(LyricSyncModel.NO_SONG, m.sync.state.value.message)
        song()
        m.importSongFile("/c/s.mp3")

        assertTrue(m.startSync())
        assertEquals("[Verse]\nမိုးတွေ\nရွာနေတယ်", session.readLyrics(), "the draft is saved first")
        val sync = m.sync
        sync.tap(1_000)
        assertFalse(sync.save(null), "not every line yet")
        sync.tap(4_000)
        assertEquals(4_000, sync.undo())
        sync.tap(4_500)
        assertTrue(sync.save(songMs = 8_000))
        assertNull(sync.state.value.sync, "the panel closes")
        assertEquals(TimingStatus.SYNCED, sync.state.value.status)
        assertEquals(2, sync.state.value.savedLines)

        val id = assertNotNull(session.open!!.project.lyricTiming)
        assertEquals(ArtifactKind.TRANSCRIPT, id.kind)
        assertEquals(listOf(LyricLine("မိုးတွေ", 1_000, 4_500), LyricLine("ရွာနေတယ်", 4_500, 8_000)), session.readLyricTimings()!!.lines)

        m.setLyrics("[Verse]\nမိုးတွေ\nရွာနေတယ်\nnew")
        m.save()
        assertEquals(TimingStatus.OUTDATED, sync.state.value.status)

        song("t.wav")
        m.importSongFile("/c/t.wav")
        assertNull(session.open!!.project.lyricTiming, "a new song clears the timings")
        assertEquals(TimingStatus.NONE, sync.state.value.status)
        assertEquals(LyricSyncModel.CLEARED_NEW_SONG, sync.state.value.message)
    }

    @Test
    fun cancelKeepsTheSavedTimingsAndClearRemovesThem() = runTest {
        val m = model()
        m.setLyrics("one\ntwo")
        song()
        m.importSongFile("/c/s.mp3")
        assertTrue(m.startSync())
        m.sync.tap(0)
        m.sync.tap(2_000)
        m.sync.tap(5_000)
        assertTrue(m.sync.save(null))
        assertEquals(5_000, session.readLyricTimings()!!.lines.last().endMs, "the end tap")

        assertTrue(m.startSync())
        m.sync.tap(100)
        m.sync.cancel()
        assertNull(m.sync.state.value.sync)
        assertEquals(TimingStatus.SYNCED, m.sync.state.value.status)

        m.sync.clear()
        assertNull(session.open!!.project.lyricTiming)
        assertEquals(TimingStatus.NONE, m.sync.state.value.status)
    }

    @Test
    fun suggestAsksTheProviderWithTheLinesAndSavesGeminiTimings() = runTest {
        val fake = FakeTranscribeProvider()
        val m = model(fake)
        m.setLyrics("[Verse]\nမိုးတွေ\nရွာနေတယ်")
        song()
        m.importSongFile("/c/s.mp3")
        assertTrue(m.suggestTimings())
        assertEquals(1, fake.calls)
        val saved = session.readLyricTimings()!!
        assertEquals(LyricTimings.SOURCE_GEMINI, saved.source)
        assertEquals(
            listOf(LyricLine("မိုးတွေ", 0, 10_000), LyricLine("ရွာနေတယ်", 15_000, 25_000)),
            saved.lines,
            "the fake spreads 2 lines over 30 s; each is capped at 10 s",
        )
        assertEquals(TimingStatus.SYNCED, m.sync.state.value.status)
        assertFalse(m.sync.state.value.suggesting)
        assertEquals("my", LyricSyncModel.langOf("မိုး"))
        assertEquals("en", LyricSyncModel.langOf("rain"))
    }

    @Test
    fun suggestFailuresKeepTheOldTimings() = runTest {
        var answer: AiResult<Transcript> = AiResult.RateLimited(null)
        var sent: Pair<String, List<String>>? = null
        val provider = object : TranscribeProvider {
            override suspend fun transcribe(audio: ByteArray, lang: String, mime: String, lines: List<String>): AiResult<Transcript> {
                sent = mime to lines
                return answer
            }
        }
        val m = model(provider)
        assertFalse(m.suggestTimings())
        assertEquals(LyricSyncModel.NO_LINES, m.sync.state.value.message)
        m.setLyrics("one\ntwo")
        song()
        m.importSongFile("/c/s.mp3")
        assertFalse(m.suggestTimings())
        assertEquals("audio/mp3" to listOf("one", "two"), sent, "no ffmpeg here: the mp3 itself is sent")
        assertEquals(LyricSyncModel.RATE_LIMITED, m.sync.state.value.message)
        answer = AiResult.Failed("bad_request", "nope")
        assertFalse(m.suggestTimings())
        assertEquals("Gemini: nope", m.sync.state.value.message)
        answer = AiResult.Ok(Transcript("en", emptyList()))
        assertFalse(m.suggestTimings())
        assertEquals(LyricSyncModel.INCOMPLETE, m.sync.state.value.message)
        assertNull(session.open!!.project.lyricTiming)
    }
}
