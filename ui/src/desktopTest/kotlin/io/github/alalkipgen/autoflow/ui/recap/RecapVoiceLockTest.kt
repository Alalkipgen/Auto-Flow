package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.Availability
import io.github.alalkipgen.autoflow.ai.TtsCapabilities
import io.github.alalkipgen.autoflow.ai.TtsProvider
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.AiItemPaused
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.jobs.DesktopAiJobRunner
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * 5A-1 voice lock in the Recap voice step and the jobs (D-35, studio-v2 §3): a voice-chip lock without a model is
 * never sent as model "", the first TTS take sets the lock, a busy locked model pauses the job (429 / 503) and a
 * missing one fails it (404) — the same model every time.
 */
class RecapVoiceLockTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    /** 1 s of silence, 8 kHz 16-bit mono WAV. */
    private val wav = java.io.ByteArrayOutputStream().apply {
        val samples = 8_000
        fun i(v: Int) { write(v and 255); write(v shr 8 and 255); write(v shr 16 and 255); write(v shr 24 and 255) }
        fun s(v: Int) { write(v and 255); write(v shr 8 and 255) }
        write("RIFF".toByteArray()); i(36 + samples * 2); write("WAVE".toByteArray())
        write("fmt ".toByteArray()); i(16); s(1); s(1); i(8000); i(16000); s(2); s(16)
        write("data".toByteArray()); i(samples * 2); write(ByteArray(samples * 2))
    }.toByteArray()

    private inner class StubTts(var reply: AiResult<ByteArray>) : TtsProvider {
        val locks = mutableListOf<String?>()
        override val capabilities = TtsCapabilities(replication = Availability.UNAVAILABLE)
        override suspend fun speak(text: String, voice: VoiceRef, lang: String, style: String?, lockedModel: String?): AiResult<ByteArray> {
            locks += lockedModel
            return reply
        }
    }

    private fun recap() = current.state.value!!.project.recap!!

    private fun open(m: RecapModel) {
        val p = (store.create("/p/R.autoflow", "R", Mode.MOVIE_RECAP) as CoreResult.Ok).value
        val scenes = listOf(RecapScene(0, 4_000, picked = true), RecapScene(4_000, 9_000, picked = true))
        current.set(OpenProject("/p/R.autoflow", p.copy(recap = RecapV2(rightsConfirmed = true, movie = RecapMovie("source/movie.mp4", 9, 9_000), scenes = scenes))))
        m.refresh()
        assertTrue(m.writeManually())
        m.saveSegment(0, "ကောင်လေး စောင့်နေတယ်")
        m.saveSegment(1, "သူငယ်ချင်း ရောက်လာတယ်")
        assertTrue(m.approveScript())
    }

    @Test fun chipLockWithoutModel_isNotSentAsModel_andTheFirstTakeSetsTheLock() = runTest {
        val tts = StubTts(AiResult.Ok(wav, Usage("gemini-3.8-flash-tts")))
        val m = RecapModel(session, fs, null, null, this, tts = tts)
        open(m)
        m.setTtsVoice("Kore")
        assertEquals("", recap().voiceLock?.model, "a voice chip before any take stores no model")

        m.speak(0)!!.join()
        assertEquals(listOf<String?>(null), tts.locks, "model \"\" must never reach the provider")
        assertEquals(VoiceLock("gemini", "gemini-3.8-flash-tts", "Kore", "my"), recap().voiceLock, "first take sets the lock")

        m.speak(1)!!.join()
        assertEquals("gemini-3.8-flash-tts", tts.locks[1], "later takes use the lock")
    }

    @Test fun busyLockedModel_pausesTheJob_missingModelFailsIt() = runTest {
        val locked = "gemini-3.8-flash-tts"
        val tts = StubTts(AiResult.RateLimited(120_000, "quota"))
        val m = RecapModel(session, fs, null, null, this, tts = tts)
        open(m)
        assertTrue(m.update { it.copy(voiceLock = VoiceLock("gemini", locked, "Kore", "my")) })

        val paused = assertFailsWith<AiItemPaused> { m.speakForJob(0) }
        assertTrue(locked in paused.message.orEmpty() && "busy" in paused.message.orEmpty(), paused.message)

        tts.reply = AiResult.Failed("http_503", "overloaded")
        assertFailsWith<AiItemPaused> { m.speakForJob(0) }

        tts.reply = AiResult.Failed("model_unavailable", "model not found")
        assertFailsWith<IllegalStateException> { m.speakForJob(0) } // a FAILED job, not PAUSED

        assertEquals(List<String?>(3) { locked }, tts.locks, "always the locked model, never another")
        assertEquals(VoiceLock("gemini", locked, "Kore", "my"), recap().voiceLock, "the lock is not changed by failures")
    }

    @Test fun runnerPausesOnAiItemPaused_andFailsOnOtherErrors() = runTest {
        val jobs = AiJobStore(fs, "/files/ai-jobs")
        val runner = DesktopAiJobRunner(jobs, this) { _, item ->
            if (item == "0") throw AiItemPaused("gemini-3.8-flash-tts is busy · Resume") else error("Model unavailable")
        }
        runner.enqueue(AiJob("busy", AiJobKind.TTS_ALL, "p", items = listOf("0", "1")))
        runner.enqueue(AiJob("gone", AiJobKind.TTS_ALL, "p", items = listOf("1")))
        advanceUntilIdle()

        val busy = jobs.load("busy")!!
        assertEquals(AiJobState.PAUSED, busy.state)
        assertEquals(listOf("0", "1"), busy.items, "nothing is skipped: Resume starts at the paused item")
        assertEquals("gemini-3.8-flash-tts is busy · Resume", busy.error)
        assertEquals(AiJobState.FAILED, jobs.load("gone")!!.state)

        // Resume = enqueue again: a PAUSED job is re-queued.
        runner.enqueue(AiJob("busy", AiJobKind.TTS_ALL, "p"))
        advanceUntilIdle()
        assertEquals(AiJobState.PAUSED, jobs.load("busy")!!.state, "still busy → paused again, still not skipped")
    }
}
