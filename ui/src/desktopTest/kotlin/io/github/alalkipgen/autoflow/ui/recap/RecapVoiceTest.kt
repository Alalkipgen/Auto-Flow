package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ai.fake.FakeTtsProvider
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapRules
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.VoiceSource
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.VoiceRecorder
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Movie Recap slice (c): recorded and Gemini TTS takes, ⚠ Stale after an edit (docs/specs/recap.md §5, D-34). */
class RecapVoiceTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val tts = FakeTtsProvider()

    /** Writes a 1.5 s 8 kHz WAV on stop. */
    private inner class Recorder : VoiceRecorder {
        override val extension = "wav"
        var path: String? = null
        override suspend fun start(path: String): PlatformResult<Unit> { this.path = path; return PlatformResult.Ok(Unit) }
        override suspend fun stop(): PlatformResult<Long> {
            val samples = 12_000
            val wav = java.io.ByteArrayOutputStream().apply {
                fun i(v: Int) { write(v and 255); write(v shr 8 and 255); write(v shr 16 and 255); write(v shr 24 and 255) }
                fun s(v: Int) { write(v and 255); write(v shr 8 and 255) }
                write("RIFF".toByteArray()); i(36 + samples * 2); write("WAVE".toByteArray())
                write("fmt ".toByteArray()); i(16); s(1); s(1); i(8000); i(16000); s(2); s(16)
                write("data".toByteArray()); i(samples * 2); write(ByteArray(samples * 2))
            }.toByteArray()
            fs.writeDurably(path!!, wav)
            return PlatformResult.Ok(1_400)
        }
        override fun cancel() {}
    }

    private val recorder = Recorder()

    private fun model(scope: kotlinx.coroutines.CoroutineScope) =
        RecapModel(session, fs, null, null, scope, tts = tts, recorder = recorder)

    private fun recap() = current.state.value!!.project.recap!!

    private fun open(m: RecapModel) {
        val p = (store.create("/p/R.autoflow", "R", Mode.MOVIE_RECAP) as CoreResult.Ok).value
        val scenes = listOf(RecapScene(0, 4_000, picked = true), RecapScene(4_000, 9_000, picked = true))
        current.set(OpenProject("/p/R.autoflow", p.copy(recap = Recap(rightsConfirmed = true, movie = RecapMovie("source/movie.mp4", 9, 9_000), scenes = scenes))))
        m.refresh()
        assertTrue(m.writeManually())
        m.saveSegment(0, "ကောင်လေး စောင့်နေတယ်")
        m.saveSegment(1, "သူငယ်ချင်း ရောက်လာတယ်")
    }

    @Test
    fun voiceNeedsAnApprovedScript() = runTest {
        val m = model(this)
        open(m)
        assertNull(m.speakAll())
        assertEquals("Approve the script first", m.state.value.message)
    }

    @Test
    fun recordSpeakAndStale() = runTest {
        val m = model(this)
        open(m)
        assertTrue(m.approveScript())

        m.startRecording(0)!!.join()
        assertEquals(0, m.state.value.recording)
        m.stopRecording()!!.join()
        assertNull(m.state.value.recording)
        val take = assertNotNull(recap().takes.single())
        assertEquals(VoiceSource.RECORDED, take.source)
        assertEquals(1_500L, take.durationMs, "from the WAV, not the clock")
        assertTrue(m.state.value.takeFiles[0]!!.endsWith(".wav"))
        assertTrue(fs.exists(m.state.value.takeFiles[0]!!))

        m.setVoiceSource(VoiceSource.TTS)
        m.setTtsVoice("Kore")
        m.speakAll()!!.join()
        assertEquals(1, tts.calls, "only the scene without a voice")
        assertEquals("🔊 Spoken 1 scenes.", m.state.value.message)
        val script = m.state.value.script!!
        assertEquals(listOf(VoiceSource.RECORDED, VoiceSource.TTS), (0..1).map { RecapRules.currentTake(recap(), script, it)!!.source })
        assertTrue(RecapRules.canRender(recap(), script).allowed)

        // Editing a text makes its take stale (and clears the approval).
        m.saveSegment(1, "သူငယ်ချင်း နောက်ကျပြီး ရောက်လာတယ်")
        assertFalse(recap().scriptApproved)
        assertTrue(RecapRules.isStale(recap(), m.state.value.script!!, 1))
        assertTrue(m.approveScript())
        m.speak(1)!!.join()
        assertFalse(RecapRules.isStale(recap(), m.state.value.script!!, 1))
        assertEquals(2, tts.calls)
    }
}
