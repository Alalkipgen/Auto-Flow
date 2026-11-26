package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.ai.subtitles.SubtitleTranslator
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.core.recap.RpmPacer
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.myHash
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.jobs.DesktopAiJobRunner
import io.github.alalkipgen.autoflow.ui.jobs.RecapJobProcessor
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.*

/**
 * S-6b: Translate to Burmese in the Subtitles editor with the spec's fake translator (`"MY:" + text`): offered only
 * for lines that are not Burmese; the confirm card sends nothing; while the job runs the editor is read-only with
 * Cancel; Done opens one new version; a failed batch says how many lines and Retry sends only those.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SubtitleTranslateTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    private inner class Fixture(lang: String? = "en") {
        val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
        val index = ProjectIndex(fs, "/app/projects.json")
        val dir = "/p/Film.autoflow"
        val session = ProjectSession(fs, store, index, current)
        val settings = SettingsStore(fs, "/app/s.json")
        val config = ToolsConfig(translate = ToolsConfig.Translate(batchLines = 2, contextLines = 1))
        val sent = mutableListOf<JsonObject>()
        val bad = mutableSetOf<Int>()
        var gate: Pair<Int, CompletableDeferred<Unit>>? = null
        val text = object : TextProvider {
            override suspend fun json(prompt: String, schema: JsonSchema, system: String?): AiResult<JsonElement> {
                val input = Json.parseToJsonElement(prompt.substringBefore("\n\n")).jsonObject
                sent += input
                val n = sent.size
                gate?.takeIf { it.first == n }?.second?.await()
                val lines = input["lines"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["text"]!!.jsonPrimitive.content }
                    .let { if (n in bad) it.reversed() else it }
                return AiResult.Ok(buildJsonObject { putJsonArray("lines") { lines.forEach { (id, t) -> addJsonObject { put("id", id); put("textMy", "MY:$t") } } } }, Usage("t"))
            }
        }
        val old = run {
            val created = (store.create(dir, "Film", Mode.SUBTITLES) as CoreResult.Ok).value
            val doc = SubtitleDoc(source = SubtitleDoc.SOURCE_SRT, lang = lang, nextId = 5, cues = listOf(
                SubtitleCue("c1", 1_000, 2_000, "One"), SubtitleCue("c2", 3_000, 4_000, "Two"), SubtitleCue("c3", 5_000, 6_000, "Three"),
                SubtitleCue("c4", 7_000, 8_000, "Four", textMy = "ကိုယ်ပြင်", myFrom = myHash("Four"), myEdited = true)))
            val id = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(), ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
            val project = created.copy(subtitles = SubtitlesTool(video = SourceVideo("source/movie.mp4", 9, 60_000, 1920, 800, 25.0, true), current = id))
            current.set(OpenProject(dir, (store.save(dir, project) as CoreResult.Ok).value))
            id
        }
        val runScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = SubtitleTranslateJob(session, fs, SubtitleTranslator(text), config)
        val jobs = AiJobStore(fs, "/app/ai-jobs")
        val runner = DesktopAiJobRunner(jobs, runScope, RecapJobProcessor({ null }, { null }, { job }), pacer = { RpmPacer(6_000) })
        val model = SubtitlesModel(session, fs, DashboardModel(fs, store, index, settings, FakePicker(), current), null, MainScope(), probe = { null })
        val editor = SubtitleEditorModel(session, fs, MainScope())
        val translate = SubtitleTranslateModel(session, job, runner, config, MainScope())
        fun tool() = (store.open(dir) as CoreResult.Ok).value.subtitles!!
        fun doc() = CoreJson.decodeFromString(SubtitleDoc.serializer(), (ArtifactStore(fs, dir).readBytes(tool().current!!) as CoreResult.Ok).value.decodeToString())
    }

    private val noDecoder: SubtitlePlayer = { _, _, m, report ->
        LaunchedEffect(Unit) { report(MovieSnapshot(phase = MoviePhase.FAILED, problem = "no decoder")) }
        Box(m)
    }

    private fun ComposeUiTest.show(f: Fixture, language: UiLanguage = UiLanguage.ENGLISH, font: Float = 1f) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
            SubtitlesScreen(f.model, f.session, f.editor, player = noDecoder, exporter = null, translate = f.translate, onBack = {}) // 🆕-8: Undo in the top bar
        }
    }

    private fun ComposeUiTest.tap(tag: String) = onNodeWithTag(tag).performScrollTo().performMouseInput { click() }
    private fun ComposeUiTest.press(tag: String) = onNodeWithTag(tag).performMouseInput { click() }
    private fun ComposeUiTest.type(tag: String, text: String) = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag))).performTextInput(text)
    private fun ComposeUiTest.waitTag(tag: String, ms: Long = 10_000) = waitUntil(timeoutMillis = ms) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private val selected = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "selected")

    @Test fun confirmSendsNothingThenTheJobLocksTheEditorAndDoneOpensTheNewVersion() = runDesktopComposeUiTest(width = 412, height = 900) {
        val f = Fixture()
        f.gate = 1 to CompletableDeferred()
        show(f)
        waitTag("subtitles-translate")
        press("subtitles-translate")
        waitTag("subtitles-translate-confirm")
        onNodeWithTag("subtitles-translate-facts").assertTextEquals("Translate 3 lines: 2 Gemini requests.")
        onNodeWithTag("subtitles-translate-style-spoken").assert(selected)
        onNodeWithTag("subtitles-translate-replace").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "off"))
        tap("subtitles-translate-replace")
        onNodeWithTag("subtitles-translate-facts").assertTextEquals("Translate 4 lines: 2 Gemini requests.")
        tap("subtitles-translate-replace")
        assertTrue(f.sent.isEmpty() && f.jobs.loadAll().isEmpty(), "nothing is sent or queued before Start")
        tap("subtitles-translate-back")
        onNodeWithTag("subtitles-translate-confirm").assertDoesNotExist()
        assertTrue(f.sent.isEmpty())

        press("subtitles-translate")
        waitTag("subtitles-translate-start")
        onNodeWithTag("subtitles-translate-style-spoken").assert(selected)
        tap("subtitles-translate-style-formal")
        onNodeWithTag("subtitles-translate-style-formal").assert(selected)
        tap("subtitles-translate-name-add")
        type("subtitles-translate-name-from-0", "One")
        type("subtitles-translate-name-my-0", "ဝမ်း")
        tap("subtitles-translate-name-add")
        tap("subtitles-translate-start")
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Translating: batch 1 of 2").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Editing pauses while translating").assertExists()
        onNodeWithTag("subtitles-translate").assertDoesNotExist()
        onNodeWithTag("subtitles-translate-cancel").assertExists()
        onNodeWithTag("subtitles-cue-edit-c1").assertIsNotEnabled()
        onNodeWithTag("subtitles-undo").assertIsNotEnabled()
        onNodeWithTag("subtitles-change-source").assertIsNotEnabled()
        f.gate!!.second.complete(Unit)
        waitTag("subtitles-translate-notice")
        onNodeWithTag("subtitles-translate-notice").assertTextContains("Done: 3 lines have Burmese", substring = true)
        assertEquals("formal", f.sent.first()["style"]!!.jsonPrimitive.content)
        assertTrue(f.sent.first().toString().contains("ဝမ်း"), "the name list is sent; the blank row is dropped")
        assertEquals(2, f.sent.size)
        val doc = f.doc()
        assertEquals(listOf("MY:One", "MY:Two", "MY:Three", "ကိုယ်ပြင်"), doc.cues.map { it.textMy })
        assertEquals(f.old, doc.derivedFrom)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-cue-edit-c1").fetchSemanticsNodes().singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false }
        onNodeWithTag("subtitles-translate").assertExists()
        f.runScope.cancel()
    }

    @Test fun aFailedBatchSaysHowManyAndRetrySendsOnlyThose() = runDesktopComposeUiTest(width = 412, height = 900) {
        val f = Fixture()
        f.bad += setOf(1, 2) // the first batch stays unusable after its one retry
        show(f)
        waitTag("subtitles-translate")
        press("subtitles-translate")
        waitTag("subtitles-translate-start")
        tap("subtitles-translate-start")
        waitTag("subtitles-translate-retry")
        onNodeWithTag("subtitles-translate-notice").assertTextEquals("2 lines not translated. The rest is kept.")
        assertEquals(listOf(null, null, "MY:Three", "ကိုယ်ပြင်"), f.doc().cues.map { it.textMy })
        tap("subtitles-translate-retry")
        waitTag("subtitles-translate-facts")
        onNodeWithTag("subtitles-translate-facts").assertTextEquals("Translate 2 lines: 1 Gemini requests.")
        tap("subtitles-translate-start")
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Done: 2 lines", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("MY:One", "MY:Two", "MY:Three", "ကိုယ်ပြင်"), f.doc().cues.map { it.textMy })
        assertEquals(2, f.jobs.loadAll().size)
        f.runScope.cancel()
    }

    @Test fun cancelKeepsTheSubtitlesAndEditingComesBack() = runDesktopComposeUiTest(width = 412, height = 900) {
        val f = Fixture()
        f.gate = 1 to CompletableDeferred()
        show(f)
        waitTag("subtitles-translate")
        press("subtitles-translate")
        waitTag("subtitles-translate-start")
        tap("subtitles-translate-start")
        waitTag("subtitles-translate-cancel")
        press("subtitles-translate-cancel")
        waitTag("subtitles-translate-notice")
        onNodeWithTag("subtitles-translate-notice").assertTextEquals("Translate cancelled. Your subtitles did not change.")
        f.gate!!.second.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { f.jobs.loadAll().single().state == AiJobState.CANCELLED }
        assertEquals(f.old, f.tool().current)
        onNodeWithTag("subtitles-cue-edit-c1").assertIsEnabled()
        f.runScope.cancel()
    }

    @Test fun burmeseLinesAreNotOfferedAndEveryLineDoneSaysSo() = runDesktopComposeUiTest(width = 412, height = 900) {
        val f = Fixture(lang = "my")
        show(f)
        waitTag("subtitles-cue-edit-c1")
        onNodeWithTag("subtitles-translate").assertDoesNotExist()
        assertTrue(f.translate.offers(null) && f.translate.offers("en") && !f.translate.offers("my") && !f.translate.offers("mya"))
        val g = Fixture()
        g.translate.ask()
        assertEquals(SubtitleTranslateConfirm(3, 2, "spoken", emptyList(), false), g.translate.state.value.confirm)
        assertTrue(g.sent.isEmpty())
        f.runScope.cancel(); g.runScope.cancel()
    }

    @Test fun confirmAndJobCardsFit() {
        for (width in listOf(360, 1440)) for (font in listOf(1f, 2f)) for (language in UiLanguage.entries) {
            runDesktopComposeUiTest(width = width, height = 900) {
                val f = Fixture()
                f.gate = 1 to CompletableDeferred()
                show(f, language, font)
                waitTag("subtitles-translate")
                assertIconButton("subtitles-translate", UiText.SUB_TRANSLATE, language, scroll = false) // P9-12d: spark, as Recap's AI
                press("subtitles-translate")
                waitTag("subtitles-translate-confirm")
                tap("subtitles-translate-name-add")
                assertCardIcon("subtitles-translate-confirm", My.breakSyllables(UiText.SUB_TRANSLATE.text(language)), 44f, "glass-dialog-tile") // 🆕-4
                assertIconOnly("subtitles-translate-name-remove-0", UiText.SUB_NAME_REMOVE, language)
                // E 1: the names grid — the In Burmese label sits over the second field.
                val label = onAllNodes(hasText(My.breakSyllables(UiText.SUB_NAME_MY.text(language))) and hasAnyAncestor(hasTestTag("subtitles-translate-confirm")),
                    useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot
                assertEquals(onNodeWithTag("subtitles-translate-name-my-0").fetchSemanticsNode().boundsInRoot.left, label.left, 1f, "$width × $font × $language names grid")
                assertIconButton("subtitles-translate-name-add", UiText.SUB_NAME_ADD, language, iconDp = 16f) // E 1: a small glass button
                assertIconButton("subtitles-translate-start", UiText.START, language)
                assertEquals(emptyList(), textFitProblems(width, font, strict = true), "$width × $font × $language confirm")
                tap("subtitles-translate-name-remove-0")
                onNodeWithTag("subtitles-translate-name-remove-0").assertDoesNotExist()
                tap("subtitles-translate-start")
                waitTag("subtitles-translate-progress")
                assertEquals(emptyList(), textFitProblems(width, font, strict = true), "$width × $font × $language running")
                f.gate!!.second.complete(Unit)
                f.runScope.cancel()
            }
        }
    }
}
