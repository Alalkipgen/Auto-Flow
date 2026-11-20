package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.recap.SrtCue
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.DraftFile
import io.github.alalkipgen.autoflow.core.subtitles.Edit
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleOps
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.*

/** S-3c: the editor on the Subtitles screen — edit panel, Save · Discard, leave guard — and its fit (spec S-3). */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SubtitleEditorScreenTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    private class Fixture {
        val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
        val index = ProjectIndex(fs, "/app/projects.json")
        val dir = "/p/Clip.autoflow"
        val session = ProjectSession(fs, store, index, current)
        val first: String
        init {
            val doc = SubtitleOps.fromCues(listOf(
                SrtCue(1_000, 3_000, "ဒီည ထွက်သွားရမယ်။\nWe have to leave tonight, before the rain starts and the roads close."),
                SrtCue(2_500, 2_900, "Too short and overlapping"), SrtCue(4_000, 6_000, "Third line")), SubtitleDoc.SOURCE_SRT)
            val created = (store.create(dir, "Clip", Mode.SUBTITLES) as CoreResult.Ok).value
            val st = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(),
                ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
            first = st.value
            val project = created.copy(subtitles = SubtitlesTool(video = SourceVideo("source/movie.mp4", 9, 60_000, 1920, 1080, 25.0), current = st))
            current.set(OpenProject(dir, (store.save(dir, project) as CoreResult.Ok).value))
        }
        val model = SubtitlesModel(session, fs, DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), FakePicker(), current),
            null, MainScope(), probe = { null })
        val editor = SubtitleEditorModel(session, fs, MainScope())
        fun saved() = (store.open(dir) as CoreResult.Ok).value.subtitles!!.current!!.value
    }

    /** No decoder here: the preview says so and editing goes on. */
    private val noDecoder: SubtitlePlayer = { _, _, m, report ->
        LaunchedEffect(Unit) { report(MovieSnapshot(phase = MoviePhase.FAILED, problem = "no decoder")) }
        Box(m)
    }

    /** A decoder whose clock is 4.5 s past the seek while playing. */
    private val ahead: SubtitlePlayer = { _, req, m, report ->
        LaunchedEffect(req) {
            report(MovieSnapshot(positionMs = req.sourceMs + if (req.playing) 4_500 else 0, durationMs = 60_000,
                phase = if (req.playing) MoviePhase.PLAYING else MoviePhase.PAUSED))
        }
        Box(m)
    }

    private fun ComposeUiTest.show(f: Fixture, width: Int, font: Float, language: UiLanguage, player: SubtitlePlayer = noDecoder,
        onBack: (() -> Unit)? = null) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language,
            LocalUiFontFamily provides BurmeseTestFont.family) { SubtitlesScreen(f.model, f.session, f.editor, player = player, onBack = onBack) }
    }

    /** Scrolls the cue list when the node is in it (the header and the Save bar do not scroll). */
    private fun ComposeUiTest.click(tag: String) {
        val node = onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performMouseInput { click() }
    }

    /** The editor with the edit panel open and one unsaved change: 360/412/840/1440 × font 1.0/1.3/2.0 × EN/MY. */
    @Test fun editPanelFitsAtEverySizeInBothLanguages() {
        for (width in listOf(360, 412, 840, 1440)) for (font in listOf(1f, 1.3f, 2f)) for (language in UiLanguage.entries) {
            runDesktopComposeUiTest(width = width, height = 900) {
                val f = Fixture()
                show(f, width, font, language)
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-cue-c1").fetchSemanticsNodes().isNotEmpty() }
                assertTrue(io.github.alalkipgen.autoflow.core.subtitles.CueIssue.TOO_SHORT in f.editor.state.value.issues["c2"].orEmpty())
                onNodeWithTag("subtitles-issue-c1-OVERLAP", useUnmergedTree = true).assertExists()
                onNodeWithTag("subtitles-cue-edit-c1").performMouseInput { click() }
                onNodeWithTag("subtitles-edit-panel").assertExists()
                onNodeWithTag("subtitles-preview-unavailable").assertIsDisplayed()
                onNodeWithTag("subtitles-start-plus").performScrollTo().performMouseInput { click() }
                onNodeWithTag("subtitles-save").assertIsDisplayed().assertIsEnabled()
                onNodeWithTag("subtitles-cues").performScrollToIndex(0)
                onNodeWithTag("subtitles-change-source").assertIsNotEnabled() // first Save or Discard
                onNodeWithTag("subtitles-shift").performScrollTo().performMouseInput { click() }
                onNodeWithTag("subtitles-shift-p1000").performScrollTo().assertIsDisplayed()
                assertEquals(emptyList(), textFitProblems(width, font), "shift · $width × $font × $language")
                onNodeWithTag("subtitles-cues").performScrollToKey("c1")
                onNodeWithTag("subtitles-start-plus").performScrollTo()
                assertEquals(emptyList(), textFitProblems(width, font), "$width × $font × $language")
                if (font != 1.3f) {
                    val shots = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/ui-v3", "subtitles").apply { mkdirs() }
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png",
                        File(shots, "03-editor-$width-$font-${language.name.lowercase()}.png"))
                }
            }
        }
    }

    @Test fun editThenSaveMakesANewVersionAndDiscardGoesBack() = runDesktopComposeUiTest(width = 412, height = 900) {
        val f = Fixture()
        show(f, 412, 1f, UiLanguage.ENGLISH)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-cue-c3").fetchSemanticsNodes().isNotEmpty() }
        click("subtitles-cue-edit-c3")
        onNode(hasAnyAncestor(hasTestTag("subtitles-edit-text")) and hasSetTextAction()).performTextReplacement("The third line, typed")
        click("subtitles-done")
        onNodeWithTag("subtitles-edit-panel").assertDoesNotExist()
        onNodeWithTag("subtitles-cue-c3").assertTextContains("The third line, typed")
        click("subtitles-undo")
        onNodeWithTag("subtitles-cue-c3").assertTextContains("Third line")
        click("subtitles-redo")
        click("subtitles-save")
        onNodeWithTag("subtitles-editor-notice").assertTextEquals(UiText.SUB_SAVED_EDIT.text(UiLanguage.ENGLISH, "n" to 3))
        assertNotEquals(f.first, f.saved())
        onNodeWithTag("subtitles-save").assertDoesNotExist()

        click("subtitles-cue-edit-c1")
        click("subtitles-delete")
        assertEquals(2, f.editor.state.value.draft!!.doc.cues.size)
        click("subtitles-discard")
        assertEquals(3, f.editor.state.value.draft!!.doc.cues.size)
        onNodeWithTag("subtitles-save").assertDoesNotExist()
    }

    @Test fun backWithUnsavedChangesAsksFirst() = runDesktopComposeUiTest(width = 412, height = 900) {
        val f = Fixture()
        var left = 0
        show(f, 412, 1f, UiLanguage.BURMESE, onBack = { left++ })
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-cue-c1").fetchSemanticsNodes().isNotEmpty() }
        click("subtitles-back")
        assertEquals(1, left) // nothing unsaved: straight back
        click("subtitles-cue-edit-c2")
        click("subtitles-delete")
        click("subtitles-back")
        onNodeWithTag("subtitles-leave").assertIsDisplayed()
        onNodeWithText(My.breakSyllables(UiText.SUB_LEAVE.text(UiLanguage.BURMESE))).assertExists()
        click("subtitles-leave-stay")
        onNodeWithTag("subtitles-leave").assertDoesNotExist()
        assertEquals(1, left)
        click("subtitles-back")
        click("subtitles-leave-save")
        assertEquals(2, left)
        assertNotEquals(f.first, f.saved())
        assertEquals(2, f.editor.state.value.draft!!.doc.cues.size)
    }

    @Test fun anEarlierDraftIsOfferedBack() = runDesktopComposeUiTest(width = 412, height = 900) {
        val f = Fixture()
        f.editor.open()
        f.editor.edit { SubtitleOps.delete(it, "c3") }
        f.editor.save() // the draft file now holds this version; craft an older unsaved one for the new current
        val now = f.saved()
        val doc = f.editor.state.value.draft!!.doc
        val changed = (SubtitleOps.setText(doc, "c1", "From the earlier session") as Edit.Done).doc
        val file = DraftFile(base = ArtifactId(now), savedAtMs = 0, doc = changed)
        f.fs.writeDurably("${f.dir}/.cache/st-draft.json", CoreJson.encodeToString(DraftFile.serializer(), file).encodeToByteArray())
        // A new editor reads the file, as after a restart.
        val editor = SubtitleEditorModel(f.session, f.fs, MainScope())
        setContent {
            CompositionLocalProvider(LocalUiLanguage provides UiLanguage.ENGLISH) { SubtitlesScreen(f.model, f.session, editor, player = noDecoder) }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-restore").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("subtitles-undo").assertIsNotEnabled() // answer the offer first
        click("subtitles-restore-yes")
        onNodeWithTag("subtitles-cue-c1").assertTextContains("From the earlier session")
        onNodeWithTag("subtitles-save").assertIsDisplayed()
    }

    @Test fun thePlayheadSeeksPlaysAndFeedsTheEditPanel() = runDesktopComposeUiTest(width = 412, height = 900) {
        val f = Fixture()
        show(f, 412, 1f, UiLanguage.ENGLISH, ahead)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-cue-c1").fetchSemanticsNodes().isNotEmpty() }
        click("subtitles-cue-c3") // tap = seek + select
        onNodeWithTag("subtitles-playhead").assertTextEquals("0:04.0")
        onNodeWithTag("subtitles-preview-line").assertTextEquals("Third line")
        onNodeWithTag("subtitles-scrubber").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.5f) }
        onNodeWithTag("subtitles-playhead").assertTextEquals("0:30.0")
        onNodeWithTag("subtitles-preview-line").assertDoesNotExist() // no line at 0:30
        click("subtitles-cue-c1")
        click("subtitles-play")
        onNodeWithTag("subtitles-playhead").assertTextEquals("0:05.5") // the decoder's clock moves the playhead
        onNodeWithTag("subtitles-preview-line").assertTextEquals("Third line")
        click("subtitles-play") // pause
        click("subtitles-cue-edit-c3")
        click("subtitles-end-playhead")
        assertEquals(5_500, f.editor.state.value.draft!!.doc.cues.first { it.id == "c3" }.endMs)
        click("subtitles-play-line") // from 0:04.0; the clock passes the line's end → paused
        onNodeWithTag("subtitles-playhead").assertTextEquals("0:08.5")
        onNodeWithTag("subtitles-play").assertTextEquals(UiText.SUB_PLAY.en)
    }

    @Test fun shiftTimesMovesAllOrFromTheSelectedLine() = runDesktopComposeUiTest(width = 412, height = 900) {
        val f = Fixture()
        show(f, 412, 1f, UiLanguage.ENGLISH)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("subtitles-cue-c1").fetchSemanticsNodes().isNotEmpty() }
        fun starts() = f.editor.state.value.draft!!.doc.cues.map { it.id to it.startMs }
        click("subtitles-shift")
        onNodeWithTag("subtitles-shift-from").assertIsNotEnabled() // no line selected yet
        click("subtitles-shift-p1000")
        assertEquals(listOf("c1" to 2_000L, "c2" to 3_500L, "c3" to 5_000L), starts())
        onNodeWithTag("subtitles-editor-notice").assertTextEquals(UiText.SUB_SHIFTED.text(UiLanguage.ENGLISH, "lines" to 3, "s" to 1.0))
        onNodeWithTag("subtitles-cues").performScrollToKey("c3")
        click("subtitles-cue-c3")
        onNodeWithTag("subtitles-cues").performScrollToIndex(0)
        onNodeWithTag("subtitles-shift-from").assertTextEquals(UiText.SUB_SHIFT_FROM.text(UiLanguage.ENGLISH, "n" to 3)).performMouseInput { click() }
        click("subtitles-shift-m100")
        assertEquals(listOf("c1" to 2_000L, "c2" to 3_500L, "c3" to 4_900L), starts())
        click("subtitles-shift-all")
        repeat(2) { click("subtitles-shift-m1000") }
        assertEquals(listOf("c1" to 0L, "c2" to 1_500L, "c3" to 2_900L), starts())
        click("subtitles-shift-m100") // before 0:00: refused, nothing moves
        assertEquals(listOf("c1" to 0L, "c2" to 1_500L, "c3" to 2_900L), starts())
        onNodeWithTag("subtitles-editor-notice").assertTextEquals(UiText.SUB_SHIFT_LIMIT.text(UiLanguage.ENGLISH, "s" to 0.0))
        onNodeWithTag("subtitles-save").assertIsDisplayed()
    }
}
