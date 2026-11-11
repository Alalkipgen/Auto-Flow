package io.github.alalkipgen.autoflow.ui.export

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.render.BatteryReader
import io.github.alalkipgen.autoflow.media.render.BatteryReading
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderQueue
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ExportModelTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val settings = SettingsStore(fs, "/a/settings.json")
    private val dir = "/p/Rain.autoflow"
    private val queue = FakeQueue()
    private val share = FakeShare()
    private var battery = BatteryReading(80, charging = false)
    private val font: ByteArray = Base64.getMimeDecoder().decode(
        javaClass.getResourceAsStream("/fonts/NotoSansMyanmar-subset.ttf.b64")!!.readBytes(),
    )

    private class FakeQueue : RenderQueue {
        val jobs = mutableListOf<RenderJob>()
        val states = mutableMapOf<String, MutableStateFlow<RenderState>>()
        val cancelled = mutableListOf<String>()

        override fun enqueue(job: RenderJob): String {
            jobs += job
            return "job${jobs.size}".also { states[it] = MutableStateFlow(RenderState.Queued) }
        }

        override fun observe(id: String): Flow<RenderState> = states.getValue(id)

        override fun cancel(id: String) {
            cancelled += id
            states.getValue(id).value = RenderState.Cancelled
        }
    }

    private class FakeShare : FileShare {
        val calls = mutableListOf<String>()
        override fun open(path: String, mime: String): PlatformResult<Unit> = PlatformResult.Ok(Unit).also { calls += "open $path $mime" }
        override fun share(path: String, mime: String, title: String): PlatformResult<Unit> =
            PlatformResult.Unsupported("No app can receive the video").also { calls += "share $path $mime" }
    }

    private fun open(withVideo: Boolean = true) {
        val p = (store.create(dir, "Rain", Mode.MUSIC_VIDEO, ProjectSettings()) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
        val video = if (withVideo) (session.artifacts()!!.put(byteArrayOf(1, 2, 3), ArtifactKind.VIDEO, "mp4", 1) as CoreResult.Ok).value.id else null
        val shots = listOf(
            Shot(1, 0, 8_000, lyricsMy = "မိုးတွေရွာနေတယ်", acceptedVideo = video),
            Shot(2, 8_000, 5_000, lyricsMy = ""),
        )
        session.save(p.copy(shots = shots))
    }

    private fun withSongAndFont() {
        fs.createDirectories("/cache")
        fs.writeDurably("/cache/s.mp3", byteArrayOf(7))
        session.importSong("/cache/s.mp3")
        fs.createDirectories("/fonts")
        fs.writeDurably("/fonts/noto.ttf", font)
        settings.update { it.copy(fontPaths = listOf("/fonts/noto.ttf")) }
    }

    private fun model(scope: CoroutineScope, q: RenderQueue? = queue) =
        ExportModel(session, settings, fs, q, share, BatteryReader { battery }, scope)

    @Test
    fun withoutAProjectNothingRenders() = runTest {
        val m = model(backgroundScope)
        assertNull(m.state.value.projectName)
        assertFalse(m.state.value.canRender)
        m.render(Quality.EXPORT)
        assertTrue(queue.jobs.isEmpty())
    }

    @Test
    fun summaryShowsWhatGoesIntoTheVideo() = runTest {
        open()
        val bare = model(backgroundScope).state.value
        assertEquals(2, bare.shots)
        assertEquals(1, bare.accepted)
        assertEquals(13_000, bare.durationMs)
        assertNull(bare.songFile)
        assertNull(bare.font)
        assertEquals(1, bare.lyricShots)
        assertTrue(bare.canRender)

        withSongAndFont()
        val m = model(backgroundScope)
        assertEquals("song.mp3", m.state.value.songFile)
        assertEquals("Noto Sans Myanmar", m.state.value.font)
        assertFalse(m.state.value.lowBattery)
        battery = BatteryReading(15, charging = false)
        m.refresh()
        assertTrue(m.state.value.lowBattery, "warns, does not block")
        assertTrue(m.state.value.canRender)
    }

    @Test
    fun exportQueuesTheProjectJobAndFollowsIt() = runTest {
        open()
        withSongAndFont()
        val m = model(backgroundScope)
        m.render(Quality.EXPORT)
        val job = queue.jobs.single()
        assertEquals("Rain · Export 1080p", job.title)
        assertEquals(dir, job.workDir)
        assertEquals("exports/export", job.outputName)
        assertEquals("source/song.mp3", job.songPath)
        assertEquals(".cache/fonts", job.fontsDir)
        assertEquals("Noto Sans Myanmar", job.placeholderFont)
        assertEquals(1080, job.graph.output.width)
        assertEquals(listOf(true, false), job.graph.clips.map { it.artifact != null })
        assertEquals(job.graph.clips[0].artifact!!.value, job.files.keys.single())
        assertTrue(job.files.values.single().startsWith("artifacts/vd_"))
        assertEquals("မိုးတွေရွာနေတယ်", job.graph.overlays.single().text)
        assertContentEquals(font, fs.read("$dir/.cache/fonts/NotoSansMyanmar.ttf"))
        assertNotNull((store.open(dir) as CoreResult.Ok).value.timeline, "the graph is kept as project.timeline")

        assertEquals(RenderState.Queued, m.state.value.run?.state)
        assertTrue(m.state.value.busy)
        m.render(Quality.PREVIEW)
        assertEquals(1, queue.jobs.size, "one render at a time from this screen")

        runCurrent()
        queue.states.getValue("job1").value = RenderState.Running(0.5, 1.2)
        runCurrent()
        assertEquals(RenderState.Running(0.5, 1.2), m.state.value.run?.state)

        queue.states.getValue("job1").value = RenderState.Done("$dir/exports/export.mp4", 30_000, 13_000, null)
        runCurrent()
        assertFalse(m.state.value.busy)
        m.open()
        m.share()
        assertEquals(listOf("open $dir/exports/export.mp4 video/mp4", "share $dir/exports/export.mp4 video/mp4"), share.calls)
        assertEquals("No app can receive the video", m.state.value.message)

        m.refresh()
        assertNotNull(m.state.value.run, "the finished run stays on screen after a refresh")
    }

    @Test
    fun previewWithoutSongOrFontAndCancel() = runTest {
        open()
        val m = model(backgroundScope)
        m.render(Quality.PREVIEW)
        val job = queue.jobs.single()
        assertEquals("exports/preview", job.outputName)
        assertEquals(540, job.graph.output.width)
        assertNull(job.songPath)
        assertNull(job.graph.audio)
        assertTrue(job.graph.overlays.isEmpty(), "no font → no lyrics")
        runCurrent()
        m.cancel()
        runCurrent()
        assertEquals(listOf("job1"), queue.cancelled)
        assertEquals(RenderState.Cancelled, m.state.value.run?.state)
        assertTrue(m.state.value.canRender, "a new render may start")
    }

    @Test
    fun withoutARenderQueueTheScreenSaysSo() = runTest {
        open()
        val m = model(backgroundScope, q = null)
        assertEquals(ExportModel.NO_QUEUE, m.state.value.unavailable)
        assertFalse(m.state.value.canRender)
        m.render(Quality.EXPORT)
        assertNull(m.state.value.run)
    }
}
