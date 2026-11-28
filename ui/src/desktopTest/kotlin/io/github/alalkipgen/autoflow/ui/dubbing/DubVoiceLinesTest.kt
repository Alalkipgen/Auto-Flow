package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.ai.fake.FakeTtsProvider
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.dubbing.DubRules
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.DubTake
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
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
 * Group 4 F 6–9 (🆕-2, 🆕-5): the Voice step's lines — filters with counts (To fix = too long, No voice = no take or an old
 * one, Done = the rest; the empty state), the fit pill of each kind, Redo / Play, the too-long row's Edit line (Dub script
 * at that line, field focused) and Shorten (the same request, the card in view), and the sticky caption.
 */
@OptIn(ExperimentalTestApi::class)
class DubVoiceLinesTest {
    /** 12 lines, 5 s apart and 2 s long: each line's room is 4.9 s (the last one's to 60 s). */
    private val lines = (1..12).map { n -> Line(n * 5_000L - 4_000, n * 5_000L - 2_000, "Line $n.", "စာကြောင်း $n ပါ") }
    private val lock = VoiceLock("gemini", "tts-a", "Kore", "my")
    private val chosen = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "selected")

    private class Runner : AiJobRunner {
        override val jobs = MutableStateFlow<List<AiJob>>(emptyList())
        override suspend fun enqueue(job: AiJob): String = job.id
        override suspend fun cancel(id: String) = Unit
        override suspend fun refresh() = Unit
    }

    /**
     * c2 and c10 too long (9 s: 2.6 s over at 1.2 ×), c3 no take, c4 an old take (another tone), c5 faster (5.5 s; speed up to 1.2), c6 uses
     * the pause (3 s), the rest fit (1.5 s); [all] = every line fits.
     */
    private inner class Harness(scope: CoroutineScope, all: Boolean = false, val shorten: DubShortenModel? = null) {
        val f = SubtitlesFixture(lines, 60_000, "Dub", scope, dub = true)
        val script = DubScriptModel(f.session, f.fs)
        val voice = DubVoiceModel(f.session, DubSpeakJob(f.session, f.fs, FakeTtsProvider()), Runner(), scope, models = listOf("tts-a"),
            config = ToolsConfig(dubbing = ToolsConfig.Dubbing(maxVoiceSpeed = 1.2)))
        init {
            script.open(); check(script.approve())
            val open = f.session.open!!
            val art = ArtifactStore(f.fs, open.dir)
            val doc = script.state.value.doc!!
            val takes = doc.cues.filter { all || it.id != "c3" }.associate { c ->
                val ms = if (all) 1_500L else when (c.id) { "c2", "c10" -> 9_000L; "c5" -> 5_500L; "c6" -> 3_000L; else -> 1_500L }
                c.id to DubTake((art.put("RIFF-${c.id}".encodeToByteArray(), ArtifactKind.AUDIO, "wav") as CoreResult.Ok).value.id,
                    DubRules.takeKey(c, Lexicon.EMPTY, lock, if (!all && c.id == "c4") "news" else null), ms)
            }
            check(f.session.update(open.dir, open.project.id) { it.copy(dubbing = (it.dubbing ?: DubbingTool()).copy(lock = lock, takes = takes)) }
                is CoreResult.Ok)
        }
    }

    private fun ComposeUiTest.run(h: Harness, language: UiLanguage, font: Float) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
                LocalUiFontFamily provides BurmeseTestFont.family) {
                DubbingScreen(h.script, h.f.model, h.f.session, h.f.editor, onBack = {}, player = { _, _, m, _ -> androidx.compose.foundation.layout.Box(m) },
                    voice = h.voice, shorten = h.shorten)
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("dub-voice-rows").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.text(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
    private fun ComposeUiTest.to(key: String) { onNodeWithTag("dub-voice-rows").performScrollToKey(key); waitForIdle() }
    private fun ComposeUiTest.click(tag: String) { onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); waitForIdle() }
    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test fun theFiltersCountAndChooseTheLines() {
        for (language in UiLanguage.entries) for (font in listOf(1f, 2f)) runDesktopComposeUiTest(width = 360, height = 1600) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                run(Harness(scope), language, font)
                to("filters")
                assertEquals(My.breakSyllables(UiText.V3_ALL_COUNT.text(language, "n" to 12)), text("dub-filter-all"))
                assertEquals(My.breakSyllables(UiText.V3_DUB_FILTER_FIX.text(language, "n" to 2)), text("dub-filter-fix"))
                assertEquals(My.breakSyllables(UiText.V3_DUB_FILTER_NO_VOICE.text(language, "n" to 2)), text("dub-filter-none"))
                assertEquals(My.breakSyllables(UiText.V3_DUB_FILTER_DONE.text(language, "n" to 8)), text("dub-filter-done"))
                assertEquals(emptyList(), textFitProblems(360, font))
                click("dub-filter-fix"); to("filters")
                onNodeWithTag("dub-filter-fix").assert(chosen)
                to("c2"); onNodeWithTag("dub-voice-row-c2").assertExists()
                to("c10"); onNodeWithTag("dub-voice-row-c10").assertExists()
                for (id in listOf("c1", "c3", "c4", "c5")) onNodeWithTag("dub-voice-row-$id").assertDoesNotExist()
                to("filters"); click("dub-filter-none")
                to("c3"); to("c4")
                for (id in listOf("c1", "c2", "c5")) onNodeWithTag("dub-voice-row-$id").assertDoesNotExist()
                to("filters"); click("dub-filter-done")
                to("c1"); to("c5")
                for (id in listOf("c2", "c3", "c4")) onNodeWithTag("dub-voice-row-$id").assertDoesNotExist()
                onNodeWithTag("dub-filter-empty").assertDoesNotExist()
            } finally { scope.cancel() }
        }
    }

    @Test fun aFilterWithNoLinesSaysSo() {
        for (language in UiLanguage.entries) for (font in listOf(1f, 2f)) runDesktopComposeUiTest(width = 360, height = 1600) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                run(Harness(scope, all = true), language, font)
                to("filters"); click("dub-filter-fix"); to("empty")
                onNode(hasText(My.breakSyllables(UiText.V3_DUB_FILTER_EMPTY.text(language))) and hasAnyAncestor(hasTestTag("dub-filter-empty"))).assertExists()
                onNodeWithTag("dub-voice-row-c1").assertDoesNotExist()
                assertEquals(emptyList(), textFitProblems(360, font))
            } finally { scope.cancel() }
        }
    }

    @Test fun eachLineShowsItsFitPillAndButtons() {
        for (language in UiLanguage.entries) for (font in listOf(1f, 2f)) runDesktopComposeUiTest(width = 360, height = 1600) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val h = Harness(scope)
                run(h, language, font)
                val pills = mapOf(
                    "c1" to UiText.V3_FITS.text(language), "c2" to UiText.V3_TOO_LONG.text(language, "seconds" to 2.6),
                    "c3" to UiText.V3_NO_VOICE.text(language), "c4" to UiText.V3_SCRIPT_CHANGED.text(language),
                    "c5" to UiText.DUB_FASTER.text(language, "x" to 1.13), "c6" to UiText.DUB_USES_PAUSE.text(language))
                for ((id, pill) in pills) {
                    to(id)
                    onNodeWithTag("dub-fit-$id").assertTextEquals(My.breakSyllables(pill))
                    // Redo with a take or an old one; Play only with a take; the two fixes only when too long.
                    assertEquals(id != "c3", exists("dub-redo-$id"), "redo $id")
                    assertEquals(id != "c3" && id != "c4", exists("dub-take-$id"), "take $id")
                    assertEquals(id == "c2", exists("dub-edit-line-$id"), "edit $id")
                    assertEquals(emptyList(), textFitProblems(360, font), "$id $language $font")
                }
                to("c1"); onNodeWithTag("dub-redo-c1").assertContentDescriptionContains(UiText.DUB_REDO_LINE.text(language))
                // The sticky: Speak the two lines without a current take (c3 none, c4 old), and the caption says how far it is.
                assertEquals(My.breakSyllables(UiText.V3_DUB_SPEAK_CAPTION.text(language, "done" to 10, "n" to 12,
                    "minutes" to h.voice.minutes(2, lock))), text("dub-speak-caption"))
            } finally { scope.cancel() }
        }
    }

    @Test fun everyLineVoicedOffersExportWithTheGreenCaption() {
        for (language in UiLanguage.entries) runDesktopComposeUiTest(width = 360, height = 1600) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                run(Harness(scope, all = true), language, 1f)
                onNodeWithTag("dub-next-STYLE").assertIsDisplayed()
                onNodeWithTag("dub-speak").assertDoesNotExist()
                assertEquals(My.breakSyllables(UiText.DUB_ALL_VOICED.text(language)), text("dub-speak-caption"))
            } finally { scope.cancel() }
        }
    }

    /** 🆕-5: Edit line opens Dub script with that line in view and its spoken field focused. */
    @Test fun editLineOpensTheScriptAtTheLineWithItsFieldFocused() {
        for (font in listOf(1f, 2f)) runDesktopComposeUiTest(width = 360, height = 800) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                run(Harness(scope), UiLanguage.BURMESE, font)
                to("c10"); click("dub-edit-line-c10")
                waitUntil(timeoutMillis = 5_000) { exists("dub-rows") }
                waitForIdle()
                onNodeWithTag("dub-row-c10").assertIsDisplayed()
                onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("dub-spoken-c10"))).assertIsFocused()
                onNodeWithTag("dub-step-SCRIPT").assert(chosen)
            } finally { scope.cancel() }
        }
    }

    /** The too-long row's Shorten = the card's: the same request, then the card (with Gemini's lines) is in view. */
    @Test fun theRowsShortenAsksGeminiAndShowsTheCard() {
        runDesktopComposeUiTest(width = 360, height = 800) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val gemini = object : io.github.alalkipgen.autoflow.ai.TextProvider {
                    override suspend fun json(prompt: String, schema: io.github.alalkipgen.autoflow.ai.JsonSchema, system: String?) =
                        io.github.alalkipgen.autoflow.ai.AiResult.Ok(kotlinx.serialization.json.Json.parseToJsonElement(
                            """{"lines":[{"id":"c2","spokenMy":"တိုတို"},{"id":"c10","spokenMy":"တိုပြီ"}]}"""), io.github.alalkipgen.autoflow.ai.Usage("t"))
                }
                val shorten = DubShortenModel(io.github.alalkipgen.autoflow.ai.dubbing.DubShortener(gemini), scope)
                run(Harness(scope, shorten = shorten), UiLanguage.ENGLISH, 1f)
                to("c10"); click("dub-row-shorten-c10")
                waitUntil(timeoutMillis = 5_000) { exists("dub-shorten-row-c10") }
                waitForIdle()
                onNodeWithTag("dub-shorten-card").assertIsDisplayed()
                onNodeWithTag("dub-shorten-new-c2").assertTextEquals(My.breakSyllables("တိုတို"))
            } finally { scope.cancel() }
        }
    }
}
