package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.ttsVoiceKind
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.TtsProvider
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.ai.config.ModelConfig
import io.github.alalkipgen.autoflow.ai.config.TtsCandidate
import io.github.alalkipgen.autoflow.ai.config.TtsModels
import io.github.alalkipgen.autoflow.ai.fake.FakeTtsProvider
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.projects.*
import kotlin.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class VoiceChangeTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/index.json"), current)
    private val dir = "/p/R.autoflow"
    private val old = VoiceLock("gemini", "test-my", "Charon", "my")
    private val next = old.copy(voice = "Kore")
    private val config = ModelConfig(tts = TtsModels(listOf(TtsCandidate("test-my", listOf("my")), TtsCandidate("test-en", listOf("en")))))
    private val sent = mutableListOf<Pair<String, String?>>()
    private val fake = FakeTtsProvider()
    private val tts = object : TtsProvider by fake {
        override suspend fun speak(text: String, voice: VoiceRef, lang: String, style: String?, lockedModel: String?): AiResult<ByteArray> {
            sent += voice.id to lockedModel
            val bytes = (fake.speak(text, voice, lang, style, lockedModel) as AiResult.Ok).value
            return AiResult.Ok(bytes, Usage(model = lockedModel ?: "test-my"))
        }
    }

    private fun open(): RecapModel {
        val p = (store.create(dir, "R", Mode.MOVIE_RECAP) as CoreResult.Ok).value
        val arts = ArtifactStore(fs, dir)
        val block = RecapBlock("b_one", listOf(RecapClip(0, 4_000)), "written caption")
        val story = RecapStory(source = "manual", blocks = listOf(block))
        val sid = (arts.put(CoreJson.encodeToString(RecapStory.serializer(), story).encodeToByteArray(), ArtifactKind.RECAP_SCRIPT, "json") as CoreResult.Ok).value.id
        val aid = (arts.put("old-audio".encodeToByteArray(), ArtifactKind.AUDIO, "wav") as CoreResult.Ok).value.id
        val recap = RecapV2(rightsConfirmed = true, movie = RecapMovie("source/movie.mp4", 10, 8_000), story = sid, storyApproved = true,
            voiceLock = old, takes = listOf(BlockTake(block.id, aid, TakeSource.TTS, old.provider, old.model, old.voice, 1_000, SpokenText.hash(block))),
            chosenTakes = mapOf(block.id to aid))
        val saved = (store.save(dir, p.copy(recap = recap)) as CoreResult.Ok).value
        current.set(OpenProject(dir, saved))
        return RecapModel(session, fs, null, null, CoroutineScope(Dispatchers.Unconfined), tts = tts, modelConfig = config)
    }

    @Test fun sheetKeepPersistsLockAndMarksOldTakeDifferentWithoutChangingHistory() = runComposeUiTest {
        val m = open()
        val before = m.state.value.recap!!
        setContent { androidx.compose.runtime.CompositionLocalProvider(io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage provides io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.BURMESE) { val s by m.state.collectAsState(); Column(Modifier.verticalScroll(rememberScrollState())) { VoiceCard(m, s, s.recap!!) } } }
        onNodeWithTag("change-voice").performScrollTo().performClick()
        onNodeWithText("test-en").assertDoesNotExist()
        onNodeWithText("Kore · ${ttsVoiceKind("Kore", UiLanguage.BURMESE)}").assertIsDisplayed().performMouseInput { click() }
        onNodeWithTag("voice-change-impact").assertExists()
        onNodeWithTag("voice-keep").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText(DIFFERENT_VOICE).assertExists()
        val saved = (store.open(dir) as CoreResult.Ok).value.recap!!
        assertEquals(next, saved.voiceLock)
        assertEquals(before.takes, saved.takes)
        assertEquals(before.chosenTakes, saved.chosenTakes)
        assertEquals(before.story, saved.story)
        assertTrue(saved.storyApproved)
        assertTrue(sent.isEmpty())
    }

    @Test fun sheetRegenerateDoesNotSkipCurrentTextWithDifferentVoice() = runComposeUiTest {
        val m = open()
        setContent { androidx.compose.runtime.CompositionLocalProvider(io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage provides io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.BURMESE) { val s by m.state.collectAsState(); Column(Modifier.verticalScroll(rememberScrollState())) { VoiceCard(m, s, s.recap!!) } } }
        onNodeWithTag("change-voice").performClick()
        onNodeWithText("Kore · ${ttsVoiceKind("Kore", UiLanguage.BURMESE)}").assertIsDisplayed().performMouseInput { click() }
        onNodeWithText("အပိုင်း 1 ခု ပြန်ပြောမယ် (≈ 1 မိနစ်)").assertExists()
        onNodeWithTag("voice-regenerate").performScrollTo().performClick()
        waitForIdle()
        assertEquals<List<Pair<String, String?>>>(listOf("Kore" to "test-my"), sent)
        val saved = (store.open(dir) as CoreResult.Ok).value.recap!!
        assertEquals(2, saved.takes.size, "new take appended, old artifact retained")
        assertEquals(saved.takes.last().audio, saved.chosenTakes["b_one"])
        assertEquals(TakeVoiceStatus.CURRENT, saved.takes.last().voiceStatus(next))
        assertEquals("written caption", m.state.value.story!!.blocks.single().textMy)
    }

    @Test fun staleSheetCannotChangeAnotherProjectOrRevision() {
        val m = open()
        val draft = m.voiceSettings.begin()!!
        m.setTtsVoice("Puck")
        assertNotNull(m.voiceSettings.apply(draft, next))
        assertEquals("Puck", session.open!!.project.recap!!.voiceLock!!.voice)
        val p2 = (store.create("/p/Other.autoflow", "Other", Mode.MOVIE_RECAP) as CoreResult.Ok).value
        current.set(OpenProject("/p/Other.autoflow", p2))
        assertNotNull(m.voiceSettings.apply(draft, next))
        assertEquals(p2, session.open!!.project)
    }

    @Test fun unknownTtsAndRecordedTakesHaveDifferentIndications() {
        val m = open()
        val take = m.state.value.recap!!.takes.single()
        assertEquals(TakeVoiceStatus.UNKNOWN, take.copy(model = null).voiceStatus(old))
        assertEquals(TakeVoiceStatus.NOT_TTS, take.copy(source = TakeSource.RECORDED).voiceStatus(next))
        val draft = m.voiceSettings.begin()!!
        assertNotNull(m.voiceSettings.apply(draft, next.copy(model = "test-en")), "English-only model is not offered for Burmese")
        assertNotNull(m.voiceSettings.apply(draft, next.copy(provider = "azure")), "no new provider or silent fallback")
    }
    @Test fun activeProjectJobPreventsChangingItsLockButPausedJobAllowsExplicitRecovery() {
        open()
        val jobsState = kotlinx.coroutines.flow.MutableStateFlow(listOf(AiJob("voice", AiJobKind.TTS_ALL, dir, listOf("b_one"), AiJobState.RUNNING)))
        val runner = object : AiJobRunner {
            override val jobs = jobsState
            override suspend fun enqueue(job: AiJob) = job.id
            override suspend fun cancel(id: String) = Unit
            override suspend fun refresh() = Unit
        }
        val controls = RecapVoiceSettings(session, config, runner) {}
        val draft = controls.begin()!!
        val bytes = fs.read("$dir/project.json")
        assertNotNull(controls.apply(draft, next))
        assertContentEquals(bytes, fs.read("$dir/project.json"))
        jobsState.value = jobsState.value.map { it.copy(state = AiJobState.PAUSED) }
        assertNull(controls.apply(draft, next), "a busy/unavailable paused lock can be changed explicitly, never silently")
        assertEquals(next, (store.open(dir) as CoreResult.Ok).value.recap!!.voiceLock)
    }

}
