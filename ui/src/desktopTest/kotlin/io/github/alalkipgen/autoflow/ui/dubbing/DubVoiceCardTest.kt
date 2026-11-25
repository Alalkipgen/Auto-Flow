package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.ai.config.EdgeTtsConfig
import io.github.alalkipgen.autoflow.ai.config.EdgeVoice
import io.github.alalkipgen.autoflow.ai.fake.FakeTtsProvider
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.DubOriginal
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlesFixture
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlesFixture.Line
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Group 4 F 1–5 (🆕-3, 🆕-4, §8.4): the Voice step's voice card — a summary row ("Nilar · Edge", Locked, "Calm · Original
 * sound: Lower · 15%"), closed when a voice is chosen and models exist, open with no voice or no models, closed with the
 * caret off while Speak runs; the original sound as two segments with their hint; the Speak confirm as a glass dialog that
 * names the voice as people say it (Edge: its own facts line); the Speak job card with progress, Resume and Cancel.
 */
@OptIn(ExperimentalTestApi::class)
class DubVoiceCardTest {
    private val lines = listOf(
        Line(1_000, 3_000, "We have to leave tonight.", "ဒီည ထွက်သွားရမယ်။"),
        Line(10_000, 12_000, "Before the rain starts.", "မိုးမရွာခင် သွားရမယ်။"),
        Line(20_000, 22_000, "Trust me.", "ငါ့ကို ယုံပါ"))
    private val edge = EdgeTtsConfig(model = "edge-tts", voices = listOf(EdgeVoice("my-MM-ThihaNeural", "Thiha", "male", "my"),
        EdgeVoice("my-MM-NilarNeural", "Nilar", "female", "my")))
    private val nilar = VoiceLock("edge", "edge-tts", "my-MM-NilarNeural", "my")
    private val kore = VoiceLock("gemini", "tts-a", "Kore", "my")

    private class Runner : AiJobRunner {
        override val jobs = MutableStateFlow<List<AiJob>>(emptyList())
        override suspend fun enqueue(job: AiJob): String = job.id
        override suspend fun cancel(id: String) = Unit
        override suspend fun refresh() = Unit
    }

    private inner class Harness(scope: CoroutineScope, models: List<String> = listOf("tts-a")) {
        val f = SubtitlesFixture(lines, 60_000, "Dub", scope, dub = true)
        val script = DubScriptModel(f.session, f.fs)
        val runner = Runner()
        val voice = DubVoiceModel(f.session, DubSpeakJob(f.session, f.fs, FakeTtsProvider()), runner, scope, models = models, edgeVoices = { edge })
        val dir get() = f.session.open!!.dir
        init { script.open(); check(script.approve()) }
        fun set(lock: VoiceLock?, tone: String? = "calm", mute: Boolean = false) {
            val open = f.session.open!!
            check(f.session.update(open.dir, open.project.id) {
                it.copy(dubbing = (it.dubbing ?: DubbingTool()).copy(lock = lock, tone = tone,
                    original = if (mute) DubOriginal(DubOriginal.MUTE, 0.0) else DubOriginal()))
            } is CoreResult.Ok)
        }
        fun job(state: AiJobState, left: List<String>, error: String? = null) {
            runner.jobs.value = listOf(AiJob("speak-1", AiJobKind.DUB_TTS, dir, left, state, error, initialItemCount = 3))
        }
    }

    private fun ComposeUiTest.run(h: Harness, language: UiLanguage, font: Float) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
                LocalUiFontFamily provides BurmeseTestFont.family) {
                DubbingScreen(h.script, h.f.model, h.f.session, h.f.editor, onBack = {}, player = { _, _, m, _ -> androidx.compose.foundation.layout.Box(m) },
                    voice = h.voice)
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("dub-voice-rows").fetchSemanticsNodes().isNotEmpty() } // approved → Voice
        onNodeWithTag("dub-voice-rows").performScrollToKey("settings"); waitForIdle()
    }

    private fun ComposeUiTest.text(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
    private fun ComposeUiTest.expandState() = onNodeWithTag("dub-voice-expand").fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    @Test fun aChosenVoiceStartsClosedAndTheCaretOpensTheSameControls() {
        for (language in UiLanguage.entries) for (font in listOf(1f, 2f)) runDesktopComposeUiTest(width = 360, height = 1600) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val h = Harness(scope); h.set(nilar)
                run(h, language, font)
                onNode(hasText("Nilar · Edge") and hasAnyAncestor(hasTestTag("dub-voice-summary"))).assertExists()
                onNodeWithTag("dub-voice-locked").assertExists()
                val lower = UiText.V3_DUB_LOWER.text(language, "percent" to 15)
                assertEquals(My.breakSyllables(UiText.V3_DUB_VOICE_SUMMARY.text(language, "tone" to UiText.VA_TONE_CALM.text(language), "original" to lower)),
                    text("dub-voice-summary-text"))
                // 🆕-4: closed — no chips until the caret opens the card.
                onNodeWithTag("dub-model-tts-a").assertDoesNotExist()
                assertEquals(UiText.V3_COLLAPSED.text(language), expandState())
                assertEquals(emptyList(), textFitProblems(360, font))
                onNodeWithTag("dub-voice-expand").performSemanticsAction(SemanticsActions.OnClick); waitForIdle()
                assertEquals(UiText.V3_EXPANDED.text(language), expandState())
                listOf("dub-model-tts-a", "dub-voice-Kore", "dub-edge-Nilar", "dub-tone-calm").forEach { onNodeWithTag(it).assertExists() }
                // The original sound: two segments (Lower · 15% / Mute) and one hint line under them.
                onNodeWithTag("dub-original-lower").assertIsSelected()
                assertEquals(My.breakSyllables(UiText.V3_DUB_LOWER_HINT.text(language)), text("dub-original-hint"))
                onNodeWithTag("dub-original-mute").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
                waitUntil(timeoutMillis = 5_000) { h.f.session.open!!.project.dubbing!!.original.mode == DubOriginal.MUTE }
                onNodeWithTag("dub-original-mute").assertIsSelected()
                // Muted, the Lower segment still says the volume a tap sets (not 0 %).
                onNodeWithTag("dub-original-lower").assert(hasText(My.breakSyllables(lower)))
                assertEquals(My.breakSyllables(UiText.V3_DUB_MUTE_HINT.text(language)), text("dub-original-hint"))
                assertEquals(My.breakSyllables(UiText.V3_DUB_VOICE_SUMMARY.text(language, "tone" to UiText.VA_TONE_CALM.text(language),
                    "original" to UiText.V3_DUB_MUTE.text(language))), text("dub-voice-summary-text"))
                // A Gemini chip locks Kore; the title follows.
                onNodeWithTag("dub-voice-Kore").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
                waitUntil(timeoutMillis = 5_000) { h.f.session.open!!.project.dubbing!!.lock?.voice == "Kore" }
                onNodeWithTag("dub-voice-rows").performScrollToKey("settings"); waitForIdle()
                onNode(hasText("Kore · Gemini") and hasAnyAncestor(hasTestTag("dub-voice-summary"))).assertExists()
                assertEquals(emptyList(), textFitProblems(360, font))
            } finally { scope.cancel() }
        }
    }

    @Test fun noVoiceOrNoModelsOpensTheCard() = runDesktopComposeUiTest(width = 360, height = 1600) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val h = Harness(scope, models = emptyList())
            run(h, UiLanguage.ENGLISH, 1f)
            assertEquals(UiText.V3_EXPANDED.text(UiLanguage.ENGLISH), expandState())
            onNode(hasText(UiText.VA_NO_VOICE.text(UiLanguage.ENGLISH)) and hasAnyAncestor(hasTestTag("dub-voice-summary"))).assertExists()
            onNodeWithTag("dub-voice-locked").assertDoesNotExist()
            onNode(hasText(My.breakSyllables(UiText.BURMESE_MODELS_MISSING.text(UiLanguage.ENGLISH)))).assertExists()
            onNodeWithTag("dub-voice-Kore").assertIsNotEnabled()
            onNodeWithTag("dub-edge-Nilar").assertIsEnabled()
        } finally { scope.cancel() }
    }

    @Test fun speakAsksInAGlassDialogThatNamesTheVoice() {
        for (language in UiLanguage.entries) for (font in listOf(1f, 2f)) runDesktopComposeUiTest(width = 360, height = 1600) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val h = Harness(scope); h.set(nilar)
                run(h, language, font)
                onNodeWithTag("dub-speak").performSemanticsAction(SemanticsActions.OnClick); waitForIdle()
                onNodeWithTag("dub-speak-confirm").assertExists()
                onNode(hasText(My.breakSyllables(UiText.V3_DUB_SPEAK_TITLE.text(language, "n" to 3)))).assertExists()
                // §8.4: Edge says Edge and names "Nilar · Edge", not the raw id and model.
                assertEquals(My.breakSyllables(UiText.V3_DUB_EDGE_CONFIRM.text(language, "n" to 3, "voice" to "Nilar · Edge", "minutes" to h.voice.minutes(3, nilar))),
                    text("dub-speak-facts"))
                assertEquals(emptyList(), textFitProblems(360, font))
                onNodeWithTag("dub-speak-back").performSemanticsAction(SemanticsActions.OnClick); waitForIdle()
                onNodeWithTag("dub-speak-confirm").assertDoesNotExist()
                h.set(kore)
                waitForIdle()
                onNodeWithTag("dub-speak").performSemanticsAction(SemanticsActions.OnClick); waitForIdle()
                assertEquals(My.breakSyllables(UiText.VA_SPEAK_CONFIRM.text(language, "n" to 3, "voice" to "Kore · Gemini", "minutes" to h.voice.minutes(3, kore))),
                    text("dub-speak-facts"))
                onNodeWithTag("dub-speak-start").assertIsEnabled()
            } finally { scope.cancel() }
        }
    }

    @Test fun theSpeakJobCardShowsProgressResumeAndCancelAndKeepsTheVoiceCardClosed() {
        for (language in UiLanguage.entries) for (font in listOf(1f, 2f)) runDesktopComposeUiTest(width = 360, height = 1600) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val h = Harness(scope); h.set(nilar)
                h.job(AiJobState.RUNNING, listOf("c2", "c3"))
                run(h, language, font)
                // While Speak runs: closed, caret off, the paused row says why.
                onNodeWithTag("dub-model-tts-a").assertDoesNotExist()
                onNodeWithTag("dub-voice-expand").assertIsNotEnabled()
                onNode(hasText(My.breakSyllables(UiText.DUB_VOICE_EDIT_PAUSED.text(language)))).assertExists()
                onNodeWithTag("dub-voice-rows").performScrollToKey("speak"); waitForIdle()
                onNodeWithTag("dub-speak-job").assertExists()
                assertEquals(My.breakSyllables(UiText.DUB_SPEAK_PROGRESS.text(language, "k" to 2, "n" to 3)), text("dub-speak-progress"))
                onNodeWithTag("dub-speak-resume").assertDoesNotExist()
                onNodeWithTag("dub-speak-cancel").assertIsEnabled()
                assertEquals(emptyList(), textFitProblems(360, font))
                h.job(AiJobState.PAUSED, listOf("c2", "c3")); waitForIdle()
                assertEquals(My.breakSyllables(UiText.DUB_SPEAK_PAUSED.text(language, "done" to 1, "n" to 3)), text("dub-speak-progress"))
                onNodeWithTag("dub-speak-resume").assertIsEnabled()
                h.job(AiJobState.FAILED, listOf("c3"), "Edge: 429 Too Many Requests."); waitForIdle()
                assertEquals(My.breakSyllables(UiText.DUB_SPEAK_STOPPED.text(language, "detail" to "Edge: 429 Too Many Requests.", "done" to 2, "n" to 3)),
                    text("dub-speak-progress"))
                onNodeWithTag("dub-speak-resume").assertIsEnabled()
                assertEquals(emptyList(), textFitProblems(360, font))
            } finally { scope.cancel() }
        }
    }
}
