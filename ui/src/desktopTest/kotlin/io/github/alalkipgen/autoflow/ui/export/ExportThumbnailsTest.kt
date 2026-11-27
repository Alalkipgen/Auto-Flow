package io.github.alalkipgen.autoflow.ui.export

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.BlockTake
import io.github.alalkipgen.autoflow.core.recap.PublishKit
import io.github.alalkipgen.autoflow.core.recap.RecapBlock
import io.github.alalkipgen.autoflow.core.recap.RecapClip
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.RecapStory
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.SpokenText
import io.github.alalkipgen.autoflow.core.recap.TakeSource
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.recap.Look
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * 5B-9 Exact-frame thumbnails and 5B-10 Publish Kit thumbnail through the Export model and screen, with a scripted
 * ffmpeg (what the commands ask for; the real pictures are checked in [ExportThumbnailsFfmpegTest]): made one by one
 * from the export job, bound to their project, out of date after a recap change, the thumbnail saved with a record
 * that a reopen checks; plus the "Exact frame" crash with two or more captions.
 */
@OptIn(ExperimentalTestApi::class)
class ExportThumbnailsTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val settings = SettingsStore(fs, "/a/settings.json")
    private val share = Share()
    private val stills = mutableListOf<List<String>>()

    /** Held before a still is written, when set (to switch projects mid-way). */
    @Volatile private var gate: CompletableDeferred<Unit>? = null

    private class Share : FileShare {
        val calls = mutableListOf<String>()
        override fun open(path: String, mime: String): PlatformResult<Unit> = PlatformResult.Ok(Unit).also { calls += "open $path" }
        override fun share(path: String, mime: String, title: String): PlatformResult<Unit> = PlatformResult.Ok(Unit).also { calls += "share $path $mime" }
        override suspend fun saveToGallery(path: String, mime: String, displayName: String): PlatformResult<String> = PlatformResult.Ok(displayName)
    }

    private class Locator : ToolLocator {
        override fun findFfmpeg(): String = "/bin/ffmpeg"
        override fun findVlc(): String? = null
        override fun isVlcDir(dir: String): Boolean = false
    }

    private fun image(format: String): ByteArray {
        val img = BufferedImage(32, 18, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 32) for (y in 0 until 18) img.setRGB(x, y, if (x < 16) 0x3366CC else 0xCC6633)
        return ByteArrayOutputStream().also { ImageIO.write(img, format, it) }.toByteArray()
    }

    private val png = image("png")
    private val jpg = image("jpg")

    /** ffprobe: a 20 s 1920×1080 movie; ffmpeg: writes the still it is asked for (PNG or JPEG bytes). */
    private val runner = ToolRunner { cmd, _, _, _, _ ->
        if (cmd[0].endsWith("ffprobe")) {
            ToolExec.Done(0, """{"streams":[{"codec_type":"video","codec_name":"h264","width":1920,"height":1080},{"codec_type":"audio","codec_name":"aac"}],"format":{"duration":"20"}}""", emptyList(), 1, false)
        } else {
            gate?.await()
            synchronized(stills) { stills += cmd }
            if ("-frames:v" in cmd) fs.writeDurably(cmd.last(), if (cmd.last().endsWith(".jpg")) jpg else png)
            ToolExec.Done(0, "", emptyList(), 1, false)
        }
    }

    private val frames = RecapFrames(session, settings, fs, MediaTools(settings, Locator(), runner))

    private val a = RecapBlock("b_a", listOf(RecapClip(0, 4_200)), "ကောင်လေး ရထားပေါ်တက်တယ်။")
    private val b = RecapBlock("b_b", listOf(RecapClip(6_000, 9_000)), "သူငယ်ချင်း စောင့်နေတယ်။")
    private val c = RecapBlock("b_c", listOf(RecapClip(12_000, 15_000)), "အဆုံး။")

    /** A v2 Movie Recap project in [dir]: approved three-block story, a movie file, takes for [voiced] blocks. */
    private fun recapProject(dir: String, name: String, voiced: List<RecapBlock> = emptyList()): OpenProject {
        val p = (store.create(dir, name, Mode.MOVIE_RECAP) as CoreResult.Ok).value
        val artifacts = ArtifactStore(fs, dir)
        fs.createDirectories("$dir/source")
        fs.writeDurably("$dir/source/movie.mp4", ByteArray(64))
        val story = RecapStory(source = RecapStory.SOURCE_MANUAL, blocks = listOf(a, b, c))
        val sid = (artifacts.put(CoreJson.encodeToString(RecapStory.serializer(), story).encodeToByteArray(), ArtifactKind.RECAP_SCRIPT, "json", provider = "manual") as CoreResult.Ok).value.id
        val takes = voiced.map { block ->
            val audio = (artifacts.put("RIFF-${block.id}".encodeToByteArray(), ArtifactKind.AUDIO, "wav", provider = "gemini") as CoreResult.Ok).value.id
            BlockTake(block.id, audio, TakeSource.TTS, provider = "gemini", durationMs = 2_000, spokenHash = SpokenText.hash(block))
        }
        val recap = RecapV2(
            rightsConfirmed = true,
            movie = RecapMovie("source/movie.mp4", 9, 20_000, 1920, 1080),
            scenes = listOf(RecapScene(0, 4_200, picked = true)),
            story = sid, storyApproved = true, takes = takes,
        )
        val saved = (store.save(dir, p.copy(recap = recap)) as CoreResult.Ok).value
        return OpenProject(dir, saved).also { current.set(it) }
    }

    private fun edit(change: (RecapV2) -> RecapV2) {
        val open = current.state.value!!
        val saved = (store.save(open.dir, open.project.copy(recap = change(open.project.recap!!))) as CoreResult.Ok).value
        current.set(OpenProject(open.dir, saved))
    }

    private fun model() = ExportModel(session, settings, fs, null, share, null, CoroutineScope(Dispatchers.Default), frames = frames)

    private fun await(what: String, done: () -> Boolean) = runBlocking {
        withTimeout(20_000) { while (!done()) { yield(); kotlinx.coroutines.delay(10) } }
        assertTrue(done(), what)
    }

    @Test
    fun exactFrameMustNotCrashWithTwoOrMoreCaptions() = runBlocking {
        val open = recapProject("/p/R.autoflow", "R", voiced = listOf(a, b, c))
        val job = assertNotNull(frames.job(open, Quality.PREVIEW, null, "exports/preview", "frames"))
        val mids = RecapFrames.sampleCandidates(job)
        assertTrue(mids.size >= 2, "the recap has several captions: $mids")
        // sampleTimes(1) divided by max - 1 = 0 and threw, so the Recap screen's "Exact frame" button crashed.
        assertEquals(mids.first(), frames.firstTime())
        val recapModel = RecapModel(session, fs, null, null, CoroutineScope(Dispatchers.Unconfined), frames = frames)
        val r = assertIs<RecapFrames.Result.Ok>(recapModel.exactFrame())
        assertEquals(mids.first(), r.frame.atMs)
        assertEquals(listOf(1L), RecapFrames.spread(listOf(1, 2, 3), 1))
        assertEquals(listOf(1L, 3L), RecapFrames.spread(listOf(1, 2, 3), 2))
        assertEquals(listOf(1L, 2L, 3L), RecapFrames.spread(listOf(1, 2, 3), 6))
        assertEquals(emptyList(), RecapFrames.spread(listOf(1, 2, 3), 0))
    }

    @Test
    fun exactFramesAreMadeFromTheExportJobAndShownOneByOne() = runDesktopComposeUiTest(width = 900, height = 2400) {
        recapProject("/p/R.autoflow", "R")
        val m = model()
        setContent { ExportScreen(m) }
        onNodeWithTag("export-thumbs-make").performClick()
        await("three stills") { m.state.value.thumbs?.working == false }
        val thumbs = assertNotNull(m.state.value.thumbs)
        // No takes = no captions: the middle of each clip in the output (4.2 s, 3 s, 3 s).
        assertEquals(listOf(2_100L, 5_700L, 8_700L), thumbs.times)
        assertEquals(thumbs.times, thumbs.frames.map { it.atMs })
        assertEquals(thumbs.times.map { "/p/R.autoflow/.cache/frames/$it.png" }, thumbs.frames.map { it.file })
        // Each still is the export graph cut to its clip and seeked frame-exactly (ExactFrame.stillArgs).
        val calls = synchronized(stills) { stills.toList() }
        assertEquals(3, calls.size)
        assertTrue(calls.all { "-frames:v" in it && "-filter_complex" in it && "[vout]" in it })
        waitForIdle()
        for (i in 0 until 3) onNodeWithTag("export-thumb-$i").assertExists()
        assertEquals(0, onAllNodesWithTag("export-thumbs-stale").fetchSemanticsNodes().size)

        // A change to the picture (a look) after they were made: marked out of date, not used for the thumbnail.
        edit { it.copy(looks = listOf(Look.WARM)) }
        m.refresh()
        assertTrue(m.state.value.thumbs!!.stale)
        waitForIdle()
        onNodeWithTag("export-thumbs-stale").assertExists()
        onNodeWithTag("export-thumb-use-0").assertIsNotEnabled()
    }

    @Test
    fun exactFramesStayInTheProjectTheyWereStartedIn() {
        recapProject("/p/R.autoflow", "R")
        val m = model()
        gate = CompletableDeferred()
        m.makeThumbnails()
        assertTrue(m.state.value.thumbs!!.working)
        recapProject("/p/S.autoflow", "S") // switched before the first still was written
        m.refresh()
        assertNull(m.state.value.thumbs, "R's stills are not shown in S")
        gate!!.complete(Unit)
        runBlocking { kotlinx.coroutines.delay(300) }
        assertNull(m.state.value.thumbs)
        assertFalse(fs.exists("/p/S.autoflow/.cache/frames"), "nothing of R is written into S")
        val written = synchronized(stills) { stills.map { it.last() } }
        assertTrue(written.all { it.startsWith("/p/R.autoflow/") }, "R's still went to R: $written")
        assertTrue(written.size <= 1, "no more stills after the switch: $written")
    }

    @Test
    fun publishThumbnailIsSavedWithItsRecordAndReopenedOrMarkedStale() = runDesktopComposeUiTest(width = 900, height = 2600) {
        val dir = "/p/R.autoflow"
        recapProject(dir, "R")
        val m = model()
        setContent { ExportScreen(m) }
        onNodeWithTag("publish-thumb-make").assertIsNotEnabled() // no title yet
        m.setPublishKit(PublishKit(title = "ရထား ဇာတ်လမ်း"))
        m.makeThumbnails(max = 2)
        await("stills") { m.state.value.thumbs?.working == false }
        val at = m.state.value.thumbs!!.frames[1].atMs
        waitForIdle()
        onNodeWithTag("export-thumb-use-1").performClick()
        await("thumbnail") { m.state.value.publishThumb?.working == false }
        val thumb = assertNotNull(m.state.value.publishThumb)
        assertNull(thumb.failed)
        assertEquals(at, thumb.atMs)
        assertEquals("$dir/exports/export.thumb.jpg", thumb.file)
        assertTrue(jpg.contentEquals(fs.read(thumb.file)))
        // Rendered at export size into the cache, with the title layer and without captions, then moved into exports.
        val call = synchronized(stills) { stills.last() }
        assertEquals("$dir/.cache/frames/thumb-$at.part.jpg", call.last())
        val graph = call[call.indexOf("-filter_complex") + 1]
        assertTrue("ass=" in graph || "subtitles=" in graph, "the title is drawn by libass: $graph")
        assertFalse(fs.exists("$dir/exports/export.thumb.jpg.tmp"))
        val record = CoreJson.decodeFromString(PublishThumbRecord.serializer(), fs.read("$dir/exports/export.thumb.json").decodeToString())
        assertEquals(PublishThumbRecord("ရထား ဇာတ်လမ်း", at, frames.fingerprint()!!, jpg.size.toLong()), record)
        waitForIdle()
        onNodeWithTag("publish-thumb-image").assertExists()
        onNodeWithTag("publish-thumb-share").performClick()
        assertEquals(listOf("share $dir/exports/export.thumb.jpg image/jpeg"), share.calls)

        // Reopened (a fresh model = app restart): shown as saved, not stale.
        val reopened = model()
        val again = assertNotNull(reopened.state.value.publishThumb)
        assertFalse(again.stale)
        assertEquals(at, again.atMs)
        // The title changes: out of date until made again.
        reopened.setPublishKit(PublishKit(title = "ခေါင်းစဉ်သစ်"))
        assertTrue(reopened.state.value.publishThumb!!.stale)
        // Another project: R's thumbnail is neither shown nor shared there.
        recapProject("/p/S.autoflow", "S")
        reopened.refresh()
        assertNull(reopened.state.value.publishThumb)
        reopened.sharePublishThumbnail()
        assertEquals(1, share.calls.size)
    }

    @Test
    fun publishFileHasChaptersFromTheBlocks() = runBlocking {
        val dir = "/p/R.autoflow"
        recapProject(dir, "R")
        val m = model()
        m.setPublishKit(PublishKit(title = "T", description = "D"))
        assertEquals(listOf(0L, 4_200L, 7_200L), m.state.value.recap!!.chapters.map { it.atMs })
        m.sharePublishFile("export")
        val text = fs.read("$dir/exports/export.publish.txt").decodeToString()
        assertTrue("# Chapters\n0:00 ကောင်လေး ရထားပေါ်တက်တယ်\n0:04 သူငယ်ချင်း စောင့်နေတယ်\n0:07 အဆုံး\n" in text, text)
    }
}
