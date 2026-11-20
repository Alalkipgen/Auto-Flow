package io.github.alalkipgen.autoflow.ui.dubbing

import io.github.alalkipgen.autoflow.ai.fake.FakeTtsProvider
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.dubbing.DubFitKind
import io.github.alalkipgen.autoflow.core.dubbing.DubRules
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.DubOriginal
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.core.recap.RpmPacer
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.jobs.DesktopAiJobRunner
import io.github.alalkipgen.autoflow.ui.jobs.RecapJobProcessor
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** D-2b (specs/dubbing.md §4): voice / tone / original saved on the project, Speak confirm first, rows with the fit. */
class DubVoiceModelTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val dir = "/p/Dub.autoflow"
    private val config = ToolsConfig()
    private val doc = SubtitleDoc(source = "srt", nextId = 4, cues = listOf(
        SubtitleCue("c1", 0, 1_500, "Hi.", textMy = "Auto Flow ပါ။"),            // 13 chars → 2.6 s > slot 1.9 s
        SubtitleCue("c2", 2_000, 4_000, "How are you?", textMy = "နေကောင်းလား။"), // 12 chars → 2.4 s ≤ slot 2.4 s
        SubtitleCue("c3", 4_500, 6_000, "Fine.", textMy = "ကောင်းပါတယ်။"),
    ))

    init {
        val p = (store.create(dir, "Dub", Mode.DUBBING) as CoreResult.Ok).value
        val st = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(),
            ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
        val saved = (store.save(dir, p.copy(subtitles = SubtitlesTool(video = SourceVideo("source/movie.mp4", 9, 8_000, 1920, 1080, 25.0), current = st),
            dubbing = DubRules.approve(DubbingTool(), 8_000, config.dubbing.maxSourceMs, doc, 1)!!)) as CoreResult.Ok).value
        current.set(OpenProject(dir, saved))
    }

    private fun tool() = current.state.value!!.project.dubbing!!

    @Test fun voiceToneAndOriginalAreSavedThenSpeakConfirmsAndFillsTheFits() = runTest {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val speak = DubSpeakJob(session, fs, FakeTtsProvider(msPerChar = 200), config)
        val runner = DesktopAiJobRunner(AiJobStore(fs, "/files/ai-jobs"), scope, RecapJobProcessor({ null }, { null }, { null }, { null }, { speak }),
            pacer = { RpmPacer(1_000) })
        val m = DubVoiceModel(session, speak, runner, scope, config, models = listOf("tts-a"))
        assertEquals(listOf(false, false, false), m.rows(current.state.value, doc).map { it.take != null })
        m.ask()
        assertEquals(UiText.VA_NO_VOICE, m.state.value.notice?.key, "no voice chosen yet")

        m.setLock(m.defaultLock()!!)
        m.setTone("news")
        m.setOriginal(DubOriginal.MUTE)
        assertEquals(VoiceLock("gemini", "tts-a", "Charon", "my"), tool().lock)
        assertEquals("news", tool().tone)
        assertEquals(DubOriginal(DubOriginal.MUTE, 0.0), tool().original)
        m.setOriginal(DubOriginal.LOWER)
        assertEquals(DubOriginal(DubOriginal.LOWER, 0.15), tool().original)
        assertNotNull(tool().scriptApproved, "voice settings never touch the approval")

        m.ask()
        assertEquals(listOf("c1", "c2", "c3"), m.state.value.confirm?.lines?.map { it.id })
        m.start()
        advanceUntilIdle()
        assertEquals(UiText.DUB_SPEAK_DONE, m.state.value.notice?.key)
        val rows = m.rows(current.state.value, doc)
        assertEquals(listOf(DubFitKind.TOO_LONG, DubFitKind.USES_PAUSE, DubFitKind.USES_PAUSE), rows.map { it.fit?.kind })
        assertTrue(rows[0].fit!!.overMs in 690..700, "2.6 s voice in a 1.9 s slot: ${rows[0].fit}")
        m.ask()
        assertEquals(UiText.DUB_ALL_VOICED, m.state.value.notice?.key)

        m.setTone("calm") // another tone: every take is old now, kept as a file but not current
        assertTrue(m.rows(current.state.value, doc).all { it.take == null && it.old })
        m.ask(setOf("c2"))
        assertEquals(listOf("c2"), m.state.value.confirm?.lines?.map { it.id }, "↻ asks for exactly that line")
        m.dismiss()
        assertNull(m.state.value.confirm)
        scope.cancel()
    }
}
