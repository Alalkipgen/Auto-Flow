package io.github.alalkipgen.autoflow.ui.export

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.audio.LoudnessMeasurement
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.media.render.RenderResult
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 5B-4: how the loudness target was met reaches the Export screen — live from the render, and again from the
 * `exports/<name>.result.json` record when a fresh model opens the project (an app restart; WorkManager pruned).
 */
@OptIn(ExperimentalTestApi::class)
class ExportLoudnessTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val settings = SettingsStore(fs, "/a/settings.json")
    private val share = Share()
    private val measured = LoudnessMeasurement(-29.7, -18.2, 3.1, -40.0, 0.4)
    private val fallbackNote = "the loudness could not be measured, so one-pass loudnorm was used"

    private class Share : FileShare {
        val calls = mutableListOf<String>()
        override fun open(path: String, mime: String): PlatformResult<Unit> = PlatformResult.Ok(Unit).also { calls += "open $path" }
        override fun share(path: String, mime: String, title: String): PlatformResult<Unit> = PlatformResult.Ok(Unit).also { calls += "share $path" }
        override suspend fun saveToGallery(path: String, mime: String, displayName: String): PlatformResult<String> = PlatformResult.Ok(displayName)
    }

    private class Queue : RenderQueue {
        val states = mutableMapOf<String, MutableStateFlow<RenderState>>()
        override fun enqueue(job: RenderJob): String = "job${states.size + 1}".also { states[it] = MutableStateFlow(RenderState.Queued) }
        override fun observe(id: String): Flow<RenderState> = states.getValue(id)
        override fun cancel(id: String) {}
    }

    private fun openProject(dir: String, name: String) {
        val p = (store.create(dir, name, Mode.MUSIC_VIDEO, ProjectSettings()) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
    }

    /** A render that really finished earlier: the mp4 and its record, as RenderJobRunner leaves them. */
    private fun finished(dir: String, name: String, bytes: Int, loudness: RenderState.Loudness?) {
        fs.createDirectories("$dir/exports")
        fs.writeDurably("$dir/exports/$name.mp4", ByteArray(bytes))
        fs.writeDurably("$dir/exports/$name.result.json", RenderResult("$name.mp4", 61_000, 9_000, bytes.toLong(), loudness).encode())
    }

    private fun model(queue: RenderQueue? = Queue()) =
        ExportModel(session, settings, fs, queue, share, null, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun reopenedExportScreenSaysHowTheLoudnessWasMet() = runDesktopComposeUiTest(width = 900, height = 1600) {
        val dir = "/p/Rain.autoflow"
        openProject(dir, "Rain")
        finished(dir, "export", 2_000, RenderState.Loudness(-14.0, measured, null))
        finished(dir, "preview", 500, RenderState.Loudness(-14.0, null, fallbackNote))
        val m = model() // a fresh model = the app restarted; no render of this session
        assertEquals(listOf(Quality.PREVIEW, Quality.EXPORT), m.state.value.lastRenders.map { it.quality })
        setContent { ExportScreen(m) }
        val lines = onAllNodesWithTag("export-last-loudness").fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString() }
        assertEquals(listOf("$LOUDNESS: $fallbackNote", "$LOUDNESS: two-pass ဖြင့် -14.0 LUFS (တိုင်းတာချက် -29.7 LUFS)"), lines)
        assertTrue(lines.none { it.contains("two-pass") && it.contains("could not") })
        onAllNodesWithText(io.github.alalkipgen.autoflow.ui.i18n.My.OPEN)[1].performClick()
        assertEquals(listOf("open $dir/exports/export.mp4"), share.calls)
    }

    @Test
    fun aStaleOrForeignRecordIsNotShownAndActionsStayInTheirProject() {
        val rain = "/p/Rain.autoflow"
        openProject(rain, "Rain")
        finished(rain, "export", 2_000, RenderState.Loudness(-14.0, measured, null))
        finished(rain, "preview", 500, null)
        fs.writeDurably("$rain/exports/preview.mp4", ByteArray(800)) // replaced after its record was written
        val m = model()
        val last = m.state.value.lastRenders.single()
        assertEquals(Quality.EXPORT, last.quality)

        openProject("/p/Sun.autoflow", "Sun")
        m.refresh()
        assertEquals(emptyList(), m.state.value.lastRenders, "another project's renders are not shown")
        m.openLast(last)
        m.shareLast(last)
        assertEquals(emptyList(), share.calls, "a render of the closed project is not opened from another project")
    }

    @Test
    fun liveRenderShowsItsLoudnessAndHidesTheOlderRecord() = runDesktopComposeUiTest(width = 900, height = 1600) {
        val dir = "/p/Rain.autoflow"
        openProject(dir, "Rain")
        session.save((store.open(dir) as CoreResult.Ok).value.copy(shots = listOf(io.github.alalkipgen.autoflow.core.model.Shot(1, 0, 5_000))))
        finished(dir, "export", 2_000, RenderState.Loudness(-14.0, null, fallbackNote))
        val q = Queue()
        val m = model(q)
        setContent { ExportScreen(m) }
        onNodeWithTag("export-last-loudness").assertTextEquals("$LOUDNESS: $fallbackNote")
        m.render(Quality.EXPORT)
        q.states.getValue("job1").value = RenderState.Done("$dir/exports/export.mp4", 9_000, 61_000, null, RenderState.Loudness(-14.0, measured, null))
        waitForIdle()
        onNodeWithTag("export-loudness").assertTextEquals("$LOUDNESS: two-pass ဖြင့် -14.0 LUFS (တိုင်းတာချက် -29.7 LUFS)")
        assertEquals(0, onAllNodesWithTag("export-last-loudness").fetchSemanticsNodes().size, "the older record is not shown over the live run")
    }
}
