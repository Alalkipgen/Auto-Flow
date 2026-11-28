package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.input.key.Key
import io.github.alalkipgen.autoflow.ai.fake.FakeTtsProvider
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.dubbing.DubRules
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.DubTake
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.media.MediaTasks
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
import kotlin.test.assertNull

/**
 * Group 4 H (PR 7): Dubbing on a ≥ 840 dp window — two panes. Voice: the lines with their source line on the left, the
 * selected line open in place (read-only spoken line, room, Speak this line again) and its waveform card `dub-line-wave` on
 * the right with the voice card; Dub script: the cards with the two fields side by side from 1200 dp and the selected line's
 * facts on the right; Export: the checklist and options on the left, the job card on the right. A phone keeps one column.
 */
@OptIn(ExperimentalTestApi::class)
class DubDesktopPanesTest {
    private val lines = listOf(
        Line(1_000, 3_000, "We have to leave tonight.", "ဒီည ထွက်သွားရမယ်။"),
        Line(10_000, 12_000, "Before the rain starts.", "မိုးမရွာခင် သွားရမယ်။"),
        Line(20_000, 22_000, "Trust me.", "ငါ့ကို ယုံပါ"))
    private val kore = VoiceLock("gemini", "tts-a", "Kore", "my")

    private class Runner : AiJobRunner {
        override val jobs = MutableStateFlow<List<AiJob>>(emptyList())
        override suspend fun enqueue(job: AiJob): String = job.id
        override suspend fun cancel(id: String) = Unit
        override suspend fun refresh() = Unit
    }

    /** A 16-bit PCM mono `.wav`: [n] samples rising to full scale. */
    private fun wav(n: Int): ByteArray {
        val data = ByteArray(n * 2).also { b -> for (i in 0 until n) { val v = (i * 32767 / n); b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() } }
        fun le(v: Int, k: Int) = ByteArray(k) { (v shr (8 * it)).toByte() }
        return "RIFF".encodeToByteArray() + le(36 + data.size, 4) + "WAVE".encodeToByteArray() + "fmt ".encodeToByteArray() + le(16, 4) +
            le(1, 2) + le(1, 2) + le(24_000, 4) + le(48_000, 4) + le(2, 2) + le(16, 2) + "data".encodeToByteArray() + le(data.size, 4) + data
    }

    private inner class Harness(scope: CoroutineScope) {
        val f = SubtitlesFixture(lines, 60_000, "Dub", scope, dub = true)
        val script = DubScriptModel(f.session, f.fs)
        val voice = DubVoiceModel(f.session, DubSpeakJob(f.session, f.fs, FakeTtsProvider()), Runner(), scope, models = listOf("tts-a"))
        val queue = DubExportModelTest.Queue()
        val export = DubExportModel(f.session, f.fs, MediaTasks(queue, scope), scope)
        init {
            script.open(); check(script.approve())
            val open = f.session.open!!
            val art = ArtifactStore(f.fs, open.dir)
            val doc = script.state.value.doc!!
            // c1 too long (9 s for its 2 s and the pause), c2 fits, c3 no voice.
            val t = doc.cues.take(2).withIndex().associate { (i, c) ->
                c.id to DubTake((art.put(wav(24_000), ArtifactKind.AUDIO, "wav") as CoreResult.Ok).value.id,
                    DubRules.takeKey(c, Lexicon.EMPTY, kore, null), if (i == 0) 9_500 else 1_500)
            }
            check(f.session.update(open.dir, open.project.id) { it.copy(dubbing = (it.dubbing ?: DubbingTool()).copy(lock = kore, takes = t)) } is CoreResult.Ok)
        }
    }

    private fun ComposeUiTest.run(h: Harness, language: UiLanguage, font: Float = 1f) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
                LocalUiFontFamily provides BurmeseTestFont.family) {
                DubbingScreen(h.script, h.f.model, h.f.session, h.f.editor, onBack = {}, player = { _, _, m, _ -> androidx.compose.foundation.layout.Box(m) },
                    voice = h.voice, export = h.export)
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("dub-voice-rows").fetchSemanticsNodes().isNotEmpty() } // approved → Voice
    }

    /** Scrolls only when the node sits in a scrolling pane (the step bar and the sticky bar do not scroll). */
    private fun ComposeUiTest.click(tag: String) {
        if (onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) onNodeWithTag(tag).performScrollTo()
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); waitForIdle()
    }
    private fun inPane(tag: String) = hasAnyAncestor(hasTestTag(tag))

    @Test fun voiceHasTheLinesLeftAndTheWaveformAndVoiceCardRight() {
        for (language in UiLanguage.entries) runDesktopComposeUiTest(width = 1440, height = 900) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val h = Harness(scope)
                run(h, language)
                // The voice card and the waveform are not in the lines list; the source line is under the spoken one.
                onNode(hasTestTag("dub-voice-settings") and inPane("dub-voice-rows")).assertDoesNotExist()
                onNodeWithTag("dub-voice-settings").assertExists()
                onNode(hasText("Before the rain starts.") and inPane("dub-voice-row-c2")).assertExists()
                // At first the too-long line is chosen: open in place with its room and Redo, its waveform on the right.
                onNode(hasText("#1 · 0:01.0 → 0:03.0") and inPane("dub-line-wave")).assertExists()
                onNodeWithTag("dub-voice-slot-c1").assertExists()
                onNodeWithTag("dub-edit-line-c1").assertExists()
                onNodeWithTag("dub-redo-c1").assertIsEnabled()
                onNodeWithTag("dub-redo-c2").assertDoesNotExist()
                // Another line's head chooses it.
                click("dub-voice-head-c2")
                onNode(hasText("#2 · 0:10.0 → 0:12.0") and inPane("dub-line-wave")).assertExists()
                onNodeWithTag("dub-redo-c2").assertIsEnabled()
                onNodeWithTag("dub-voice-slot-c1").assertDoesNotExist()
                onNode(hasTestTag("dub-fit-c2") and inPane("dub-line-wave")).assertDoesNotExist() // the wave card's pill is its own
                assertEquals(emptyList(), textFitProblems(1440, 1f))
            } finally { scope.cancel() }
        }
    }

    @Test fun dubScriptShowsTheFieldsSideBySideAndTheSelectedLineOnTheRight() {
        for (language in UiLanguage.entries) runDesktopComposeUiTest(width = 1440, height = 900) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val h = Harness(scope)
                run(h, language)
                click("dub-step-SCRIPT")
                onNode(hasText("#1 · 0:01.0 → 0:03.0")).assertExists()
                onNode(hasText("We have to leave tonight.")).assertExists() // the source line, in the facts card
                val sub = onNodeWithTag("dub-subtitle-c2").fetchSemanticsNode().boundsInRoot
                val spoken = onNodeWithTag("dub-spoken-c2").fetchSemanticsNode().boundsInRoot
                assert(spoken.left > sub.right) { "side by side: $sub $spoken" }
                click("dub-row-head-c2")
                onNode(hasText("#2 · 0:10.0 → 0:12.0")).assertExists()
                onNode(hasText("Before the rain starts.")).assertExists()
                onNode(hasText(My.breakSyllables(UiText.DUB_TAKE.text(language, "s" to 1.5)))).assertExists()
                assertEquals(emptyList(), textFitProblems(1440, 1f))
            } finally { scope.cancel() }
        }
    }

    @Test fun exportHasTheJobCardOnTheRight() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val h = Harness(scope)
            run(h, UiLanguage.ENGLISH)
            click("dub-step-EXPORT")
            onNode(hasTestTag("dub-export-checks") and inPane("dub-export")).assertExists()
            onNode(hasTestTag("dub-preview-card") and inPane("dub-export")).assertDoesNotExist()
            // The checklist sends to Voice; a ready export puts its job card on the right, not under the options.
            val open = h.f.session.open!!
            val art = ArtifactStore(h.f.fs, open.dir)
            val doc = h.script.state.value.doc!!
            val t = doc.cues.associate { c -> c.id to DubTake((art.put(wav(24_000), ArtifactKind.AUDIO, "wav") as CoreResult.Ok).value.id,
                DubRules.takeKey(c, Lexicon.EMPTY, kore, null), 1_500) }
            check(h.f.session.update(open.dir, open.project.id) { it.copy(dubbing = it.dubbing!!.copy(takes = t)) } is CoreResult.Ok)
            waitForIdle()
            click("dub-export-go")
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("dub-export-job").fetchSemanticsNodes().isNotEmpty() }
            h.queue.states.getValue("job1").value = RenderState.Running(.4, null); waitForIdle()
            onNode(hasTestTag("dub-export-job") and inPane("dub-export")).assertDoesNotExist()
            onNodeWithTag("dub-export-job").assertExists()
        } finally { scope.cancel() }
    }

    /** D-105: Style on a desktop — the Captions options on the left, the preview on the right; Previous step / Next: Export. */
    @Test fun styleHasTheOptionsLeftAndThePreviewRight() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val h = Harness(scope)
            run(h, UiLanguage.BURMESE)
            click("dub-step-STYLE")
            onNode(hasTestTag("dub-style-captions") and inPane("dub-style")).assertExists()
            onNode(hasTestTag("dub-preview-card") and inPane("dub-style")).assertDoesNotExist()
            onNodeWithTag("dub-preview-card").assertExists()
            click("dub-burn")
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("dub-burn-lines-1").fetchSemanticsNodes().isNotEmpty() }
            onNode(hasTestTag("dub-burn-size-MEDIUM") and inPane("dub-style")).assertExists()
            onNodeWithTag("project-shortcuts").assertExists() // Space still plays the preview
            assertEquals(emptyList(), textFitProblems(1440, 1f))
            click("dub-back-VOICE")
            onNodeWithTag("dub-voice-rows").assertExists()
            click("dub-step-STYLE")
            click("dub-next-EXPORT")
            onNode(hasTestTag("dub-export-burn") and inPane("dub-export")).assertExists()
            onNodeWithTag("dub-burn").assertDoesNotExist()
        } finally { scope.cancel() }
    }

    @Test fun aPhoneKeepsOneColumn() = runDesktopComposeUiTest(width = 360, height = 1600) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val h = Harness(scope)
            run(h, UiLanguage.ENGLISH)
            onNodeWithTag("dub-line-wave").assertDoesNotExist()
            onNodeWithTag("dub-voice-rows").performScrollToKey("c2"); waitForIdle()
            onNodeWithTag("dub-redo-c2").assertExists()
            onNode(hasText("Before the rain starts.")).assertDoesNotExist() // 🆕-7: no source line on the phone
        } finally { scope.cancel() }
    }

    @Test fun wavePeaksReadsPlainPcmOnly() {
        val p = wavePeaks(wav(24_000), 10)!!
        assertEquals(10, p.size)
        assertEquals(1f, p.last())
        assert(p.first() < .2f)
        assertNull(wavePeaks("RIFF-c1".encodeToByteArray()))
        assertNull(wavePeaks(wav(5), 10))
    }

    /** 🆕-6 (PR 8): Space = the preview's Play / Pause, ↑ / ↓ = previous / next line (chosen + seek), Enter = Edit line; the hint row. */
    @Test fun voiceKeysChooseTheLineAndEnterOpensTheScript() {
        for (language in UiLanguage.entries) runDesktopComposeUiTest(width = 1440, height = 900) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val h = Harness(scope)
                run(h, language)
                assertEquals(My.breakSyllables(UiText.V3_DUB_SHORTCUTS.text(language)), onNodeWithTag("dub-shortcuts").fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text })
                val host = onNodeWithTag("project-shortcuts").assertIsFocused()
                onNodeWithTag("dub-voice-slot-c1").assertExists() // at first the too-long line
                host.performKeyInput { pressKey(Key.DirectionDown) }; waitForIdle()
                onNodeWithTag("dub-voice-slot-c2").assertExists()
                onNodeWithTag("dub-voice-slot-c1").assertDoesNotExist()
                onNode(hasText("#2 · 0:10.0 → 0:12.0") and inPane("dub-line-wave")).assertExists()
                onNode(hasTestTag("dub-playhead") and hasText("0:10.0", substring = true)).assertExists() // the preview seeks there
                host.performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.DirectionUp) }; waitForIdle()
                onNodeWithTag("dub-voice-slot-c1").assertExists() // stops at the first line, no wrap
                host.performKeyInput { pressKey(Key.Spacebar) }; waitForIdle()
                onNode(hasTestTag("dub-play") and hasContentDescription(UiText.SUB_PAUSE.text(language))).assertExists()
                host.performKeyInput { pressKey(Key.Spacebar) }; waitForIdle()
                onNode(hasTestTag("dub-play") and hasContentDescription(UiText.SUB_PLAY.text(language))).assertExists()
                host.performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.Enter) }; waitForIdle()
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("dub-spoken-c2"))).fetchSemanticsNodes().isNotEmpty() }
                onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("dub-spoken-c2"))).assertIsFocused() // Dub script, field focused
                assertEquals(emptyList(), textFitProblems(1440, 1f))
            } finally { scope.cancel() }
        }
    }

    @Test fun scriptKeysMoveTheChosenLineAndEnterFocusesItsField() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val h = Harness(scope)
            run(h, UiLanguage.ENGLISH)
            click("dub-step-SCRIPT")
            onNodeWithTag("dub-shortcuts").assertExists()
            val host = onNodeWithTag("project-shortcuts")
            host.performSemanticsAction(SemanticsActions.RequestFocus); waitForIdle()
            onNode(hasText("#1 · 0:01.0 → 0:03.0")).assertExists()
            host.performKeyInput { pressKey(Key.DirectionDown) }; waitForIdle()
            onNode(hasText("#2 · 0:10.0 → 0:12.0")).assertExists()
            host.performKeyInput { pressKey(Key.Enter) }; waitForIdle()
            onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("dub-spoken-c2"))).assertIsFocused()
        } finally { scope.cancel() }
    }

    @Test fun exportAndPhonesHaveNoLineHint() {
        runDesktopComposeUiTest(width = 1440, height = 900) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                run(Harness(scope), UiLanguage.ENGLISH)
                click("dub-step-EXPORT")
                onNodeWithTag("project-shortcuts").assertExists() // Space still plays the preview
                onNodeWithTag("dub-shortcuts").assertDoesNotExist()
            } finally { scope.cancel() }
        }
        runDesktopComposeUiTest(width = 360, height = 1600) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                run(Harness(scope), UiLanguage.ENGLISH)
                onNodeWithTag("project-shortcuts").assertDoesNotExist()
                onNodeWithTag("dub-shortcuts").assertDoesNotExist()
            } finally { scope.cancel() }
        }
    }
}
