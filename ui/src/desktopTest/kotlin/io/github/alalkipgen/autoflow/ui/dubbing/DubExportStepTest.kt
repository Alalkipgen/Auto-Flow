package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.dubbing.DubRules
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.DubTake
import io.github.alalkipgen.autoflow.core.model.DubbingTool
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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Group 4 G (PR 6): the Export step's checklist — the three real checks as rows (green when they pass, amber with the
 * reason and Go to the step that fixes it), the k / 3 pill and the title (Ready to export / Before the export:) — the
 * sticky Make the dubbed video with its reason while a check fails, the original sound with its volume, and §8.3: a
 * cancelled export says Cancelled.
 */
@OptIn(ExperimentalTestApi::class)
class DubExportStepTest {
    private val lines = listOf(
        Line(1_000, 3_000, "We have to leave tonight.", "ဒီည ထွက်သွားရမယ်။"),
        Line(10_000, 12_000, "Before the rain starts.", "မိုးမရွာခင် သွားရမယ်။"),
        Line(20_000, 22_000, "Trust me.", "ငါ့ကို ယုံပါ"))
    private val kore = VoiceLock("gemini", "tts-a", "Kore", "my")

    private inner class Harness(scope: CoroutineScope, approve: Boolean = true) {
        val f = SubtitlesFixture(lines, 60_000, "Dub", scope, dub = true)
        val script = DubScriptModel(f.session, f.fs)
        val queue = DubExportModelTest.Queue()
        val export = DubExportModel(f.session, f.fs, MediaTasks(queue, scope), scope)
        var went: DubStep? = null
        init { script.open(); if (approve) check(script.approve()) }
        val doc get() = script.state.value.doc!!
        /** Takes for the first [n] lines, each fitting its time ([long] = the first one too long). */
        fun takes(n: Int, long: Boolean = false) {
            val open = f.session.open!!
            val art = ArtifactStore(f.fs, open.dir)
            val t = doc.cues.take(n).withIndex().associate { (i, c) ->
                c.id to DubTake((art.put("RIFF-${c.id}".encodeToByteArray(), ArtifactKind.AUDIO, "wav") as CoreResult.Ok).value.id,
                    DubRules.takeKey(c, Lexicon.EMPTY, kore, null), if (long && i == 0) 15_000 else 1_500)
            }
            check(f.session.update(open.dir, open.project.id) { it.copy(dubbing = (it.dubbing ?: DubbingTool()).copy(lock = kore, takes = t)) } is CoreResult.Ok)
        }
    }

    private fun ComposeUiTest.run(h: Harness, language: UiLanguage, font: Float = 1f) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
                LocalUiFontFamily provides BurmeseTestFont.family) {
                ExportStep(h.export, h.f.session.open, h.doc, { h.went = it })
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.text(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
    /** Scrolls when the node sits in the step column; the sticky bar's Export (`dub-export-go`) has no scroll parent. */
    private fun ComposeUiTest.click(tag: String) {
        val node = onNodeWithTag(tag)
        if (tag != "dub-export-go") node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.OnClick); waitForIdle()
    }

    @Test fun aReadyExportShowsThreeGreenChecksAndNoReason() {
        for (language in UiLanguage.entries) for (font in listOf(1f, 2f)) runDesktopComposeUiTest(width = 360, height = 1600) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val h = Harness(scope); h.takes(3)
                run(h, language, font)
                onNodeWithTag("dub-export-checks").assertExists()
                onNodeWithTag("dub-export-blocks").assertDoesNotExist()
                onNode(hasText(My.breakSyllables(UiText.V3_DUB_READY.text(language))) and hasAnyAncestor(hasTestTag("dub-export-checks"))).assertExists()
                onNode(hasText("3/3") and hasAnyAncestor(hasTestTag("dub-export-checks"))).assertExists()
                assertEquals(My.breakSyllables(UiText.V3_DUB_CHECK_APPROVED.text(language)), text("dub-export-check-approval"))
                assertEquals(My.breakSyllables(UiText.V3_DUB_CHECK_VOICED.text(language, "n" to 3)), text("dub-export-check-voice"))
                assertEquals(My.breakSyllables(UiText.V3_DUB_CHECK_FITS.text(language)), text("dub-export-check-long"))
                listOf("approval", "voice", "long").forEach { onNodeWithTag("dub-export-go-$it").assertDoesNotExist() }
                assertEquals(My.breakSyllables(UiText.DUB_ORIGINAL.text(language) + ": " + UiText.V3_DUB_LOWER.text(language, "percent" to 15)),
                    text("dub-export-original"))
                onNodeWithTag("dub-export-reason").assertDoesNotExist()
                onNodeWithTag("dub-export-go").assertIsEnabled()
                assertEquals(emptyList(), textFitProblems(360, font))
            } finally { scope.cancel() }
        }
    }

    @Test fun aFailingCheckIsAmberWithGoToAndTheStickySaysWhy() {
        for (language in UiLanguage.entries) runDesktopComposeUiTest(width = 360, height = 1600) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val h = Harness(scope); h.takes(2, long = true)
                run(h, language)
                onNodeWithTag("dub-export-blocks").assertExists()
                onNode(hasText(My.breakSyllables(UiText.DUB_EXPORT_NOT_READY.text(language))) and hasAnyAncestor(hasTestTag("dub-export-checks"))).assertExists()
                onNode(hasText("1/3") and hasAnyAncestor(hasTestTag("dub-export-checks"))).assertExists()
                assertEquals(My.breakSyllables(UiText.V3_DUB_CHECK_APPROVED.text(language)), text("dub-export-check-approval"))
                assertEquals(My.breakSyllables(UiText.DUB_EXPORT_NO_VOICE.text(language, "n" to 1)), text("dub-export-block-voice"))
                assertEquals(My.breakSyllables(UiText.DUB_SHORTEN_N.text(language, "n" to 1)), text("dub-export-block-long"))
                onNodeWithTag("dub-export-go-approval").assertDoesNotExist()
                assertEquals(My.breakSyllables(UiText.DUB_GO_TO.text(language, "step" to UiText.DUB_STEP_VOICE.text(language))), text("dub-export-go-voice"))
                assertEquals(My.breakSyllables(UiText.V3_DUB_EXPORT_REASON.text(language)), text("dub-export-reason"))
                onNodeWithTag("dub-export-go").assertIsNotEnabled()
                click("dub-export-go-long")
                assertEquals(DubStep.VOICE, h.went)
            } finally { scope.cancel() }
        }
    }

    @Test fun notApprovedGoesToTheDubScript() = runDesktopComposeUiTest(width = 360, height = 1600) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val h = Harness(scope, approve = false)
            run(h, UiLanguage.ENGLISH)
            assertEquals(UiText.DUB_LOCK_NOT_APPROVED.en, text("dub-export-block-approval"))
            click("dub-export-go-approval")
            assertEquals(DubStep.SCRIPT, h.went)
        } finally { scope.cancel() }
    }

    /** §8.3: Cancel stops the export and the callout says "Cancelled." (the job's state), not the button's "Cancel.". */
    @Test fun aCancelledExportSaysCancelled() {
        for (language in UiLanguage.entries) runDesktopComposeUiTest(width = 360, height = 1600) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val h = Harness(scope); h.takes(3)
                run(h, language)
                click("dub-export-go")
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("dub-export-job").fetchSemanticsNodes().isNotEmpty() }
                h.queue.states.getValue("job1").value = RenderState.Running(.46, null); waitForIdle()
                click("dub-export-cancel")
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("dub-export-stopped").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(My.breakSyllables(UiText.DUB_EXPORT_STOPPED.text(language, "k" to 1, "detail" to UiText.JOB_CANCELLED.text(language) + ".")),
                    text("dub-export-stopped"))
            } finally { scope.cancel() }
        }
    }
}
