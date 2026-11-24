package io.github.alalkipgen.autoflow.ui.export

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.lyrics.LyricLine
import io.github.alalkipgen.autoflow.core.lyrics.LyricTimings
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Group 3 H (PR 11, 🆕-4): the Music Video Export page of Studio v2 — Preview / Export in the sticky bar, the Render
 * card only once there is a run, and the Video card's facts as icon rows with amber callouts (no "♪ " / "⚠ " text).
 */
@OptIn(ExperimentalTestApi::class)
class MusicVideoExportTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val settings = SettingsStore(fs, "/a/settings.json")
    private val dir = "/p/Bagan.autoflow"

    private class Queue : RenderQueue {
        val jobs = mutableListOf<RenderJob>()
        val run = MutableStateFlow<RenderState>(RenderState.Queued)
        override fun enqueue(job: RenderJob): String { jobs += job; return "job${jobs.size}" }
        override fun observe(id: String) = run
        override fun cancel(id: String) { run.value = RenderState.Cancelled }
    }

    private fun openProject() {
        val p = (store.create(dir, "Bagan Sunset", Mode.MUSIC_VIDEO, ProjectSettings()) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
        session.save(p.copy(shots = listOf(Shot(1, 0, 8_000), Shot(2, 8_000, 8_000))))
    }

    private val inBar = hasAnyAncestor(hasTestTag("sticky-action-bar"))
    private fun ComposeUiTest.texts(): List<String> = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
        .flatMap { n -> n.config[SemanticsProperties.Text].map { it.text } }

    @Test
    fun previewAndExportSitInTheStickyBarAndTheRenderCardWaitsForARun() = runDesktopComposeUiTest(width = 720, height = 1600) {
        openProject()
        val q = Queue()
        val m = ExportModel(session, settings, fs, q, null, null, CoroutineScope(Dispatchers.Unconfined))
        setContent { CompositionLocalProvider(LocalUiLanguage provides UiLanguage.ENGLISH) { MusicVideoExportScreen(m) } }
        onNode(hasTestTag("export-render") and inBar).assertIsEnabled()
        onNode(hasTestTag("export-preview") and inBar).assertIsEnabled()
        onNodeWithText(UiText.RENDER_BACKGROUND_HINT.en).assertExists()
        assertTrue(onAllNodesWithText(UiText.RENDER_HEADING.en).fetchSemanticsNodes().isEmpty(), "no Render card before a run")
        assertTrue(onAllNodesWithText(UiText.EXPORT.en).fetchSemanticsNodes().isEmpty(), "no page title (the bar names the page)")
        assertEquals(1, onAllNodesWithTag("export-render").fetchSemanticsNodes().size, "one Export button")

        onNode(hasTestTag("export-render") and inBar).performClick()
        q.run.value = RenderState.Running(.62, null)
        waitForIdle()
        assertEquals(listOf("exports/${ExportModel.EXPORT_NAME}"), q.jobs.map { it.outputName }, "the 1080p Export was queued")
        onNodeWithText(UiText.RENDER_HEADING.en).assertExists()
        onNodeWithTag("export-status").assertExists()
        onNode(hasTestTag("export-render") and inBar).assertIsNotEnabled()
        onNode(hasTestTag("export-preview") and inBar).assertIsNotEnabled()
    }

    @Test
    fun theVideoFactsAreIconRowsAndTheGapsAmberCallouts() = runDesktopComposeUiTest(width = 720, height = 1600) {
        openProject()
        session.writeLyrics("ရွှေရောင် နေဝင်ချိန်")
        session.saveLyricTimings(LyricTimings(lines = listOf(LyricLine("an older line", 0, 4_000))))
        val m = ExportModel(session, settings, fs, Queue(), null, null, CoroutineScope(Dispatchers.Unconfined))
        setContent { CompositionLocalProvider(LocalUiLanguage provides UiLanguage.ENGLISH) { MusicVideoExportScreen(m) } }
        onNodeWithTag("export-video").assertExists()
        listOf(UiText.NO_SONG_EXPORT, UiText.NO_BURMESE_FONT_LYRICS, UiText.LYRICS_TIMING_STALE).forEach { onNodeWithText(it.en).assertExists() }
        assertFalse(UiText.LYRICS_TIMING_STALE.en.startsWith("⚠") || UiText.LYRICS_TIMING_STALE.my.orEmpty().startsWith("⚠"), "the icon carries the warning")
        assertEquals(0, onAllNodesWithTag("export-song").fetchSemanticsNodes().size)

        fs.createDirectories("$dir/source")
        fs.writeDurably("$dir/source/song.mp3", ByteArray(16))
        m.refresh()
        waitForIdle()
        onNodeWithTag("export-song").assertExists()
        assertTrue(onAllNodesWithText(UiText.NO_SONG_EXPORT.en).fetchSemanticsNodes().isEmpty())
        assertTrue(texts().none { it.startsWith("♪") || it.startsWith("⚠") }, texts().toString())
    }
}
