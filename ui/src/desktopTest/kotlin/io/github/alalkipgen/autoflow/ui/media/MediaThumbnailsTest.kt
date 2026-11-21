package io.github.alalkipgen.autoflow.ui.media

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.media.tool.*
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlinx.coroutines.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

/** P9-7 (DESIGN.md §9.5, D-99): row thumbnails are app cache only, made off the caller's thread, one at a time. */
class MediaThumbnailsTest {
    private val fs = InMemoryFileSystem()
    private val dir = "/projects/a"
    private val source = MediaThumbnails.Source(dir, "source/movie.mp4", "saved-1", 120_000)
    private val calls = mutableListOf<List<String>>()
    private var output = true
    private val caller = Thread.currentThread().id
    private val locator = object : ToolLocator {
        override fun findFfmpeg() = "/fake/ffmpeg"
        override fun findVlc(): String? = null
        override fun isVlcDir(dir: String) = false
    }
    private val runner = ToolRunner { cmd, _, _, _, _ ->
        assertNotEquals(caller, Thread.currentThread().id, "ffmpeg and image IO must not run on the caller / UI thread")
        calls += cmd
        if (output) fs.writeDurably(cmd.last(), jpeg())
        ToolExec.Done(0, "", emptyList(), 1, false)
    }
    private fun store(tool: ToolRunner = runner, cache: String? = "/app/cache/media", busy: () -> Boolean = { false }) =
        MediaThumbnails(fs, MediaTools(SettingsStore(fs, "/app/settings.json"), locator, tool), cache, busy)
    private fun seed() {
        fs.createDirectories("$dir/source"); fs.createDirectories("$dir/artifacts")
        fs.writeDurably("$dir/source/movie.mp4", byteArrayOf(1, 2, 3))
        fs.writeDurably("$dir/source/cover.png", byteArrayOf(4, 5))
        fs.writeDurably("$dir/project.json", "unchanged project".encodeToByteArray())
        fs.writeDurably("$dir/artifacts/immutable", byteArrayOf(9, 8, 7))
    }
    private fun projectBytes() = listOf("source/movie.mp4", "project.json", "artifacts/immutable").associateWith { fs.read("$dir/$it").toList() }
    private fun at(i: Int = calls.lastIndex) = calls[i].let { it[it.indexOf("-ss") + 1] }

    @Test fun frameAtTenPercentIsCachedOutsideTheProjectAndProjectBytesStayUnchanged() = runBlocking {
        seed(); val before = projectBytes()
        val image = assertNotNull(store().load(source))
        assertEquals(240, image.width)
        assertEquals("12.000", at()); assertEquals("$dir/source/movie.mp4", calls.single().let { it[it.indexOf("-i") + 1] })
        assertNotNull(store().load(source)) // a new instance reads the disk slot
        assertEquals(1, calls.size)
        assertNotNull(store().load(source.copy(revision = "saved-2"))); assertEquals(2, calls.size, "a new save makes a new frame")
        mapOf(5_000L to "1.000", 1_800_000L to "60.000", null to "1.000").forEach { (ms, ss) ->
            store().load(source.copy(revision = "d$ms", durationMs = ms)); assertEquals(ss, at())
        }
        store().load(MediaThumbnails.Source(dir, "source/cover.png")); assertEquals("0.000", at())
        assertEquals(before, projectBytes())
        assertEquals(listOf("artifacts", "project.json", "source"), fs.list(dir).sorted())
        assertTrue(fs.list("/app/cache/media").all { it.startsWith("media-") && it.endsWith(".bin") || it == "discard.tmp" })
    }

    @Test fun aFailureGivesNullOnceSoTheRowKeepsItsPlaceholder() = runBlocking {
        seed(); output = false; val s = store()
        assertNull(s.load(source))
        assertEquals(listOf("12.000", "0.000"), calls.indices.map { at(it) }, "a short video gets one more try at its first frame")
        assertNull(s.load(source)); assertEquals(2, calls.size, "a failed source is not retried while the app runs")
        assertNull(store(ToolRunner { _, _, _, _, _ -> ToolExec.CannotStart("missing") }).load(source.copy(revision = "x")))
        assertNull(store(cache = null).load(source))
        assertNull(store(cache = "$dir/.cache/thumbs").load(source))
        assertNull(store().load(source.copy(file = "source/missing.mp4")))
        assertEquals(2, calls.size); assertFalse(fs.exists("$dir/.cache"))
        assertTrue(fs.list("/app/cache/media").none { it.endsWith(".jpg") || it.endsWith(".part") || it.endsWith(".bin") })
    }

    @Test fun waitsWhileAnExportRunsAndRunsOneAtATime() = runBlocking {
        seed(); var busy = true; val s = store(busy = { busy })
        val job = async { s.load(source) }
        delay(400); assertEquals(0, calls.size, "nothing runs while an export runs")
        busy = false
        assertNotNull(job.await()); assertEquals(1, calls.size)
        (1..8).map { async { s.load(source.copy(revision = "same")) } }.awaitAll()
        assertEquals(2, calls.size, "concurrent requests for one source share one ffmpeg run")
        repeat(40) { s.load(source.copy(revision = "r$it")) }
        assertTrue(s.remembered() <= 30, "small in-memory LRU")
        assertTrue(fs.list("/app/cache/media").count { it.endsWith(".bin") } <= 128)
    }

    private fun jpeg() = ByteArrayOutputStream().also { out ->
        val image = BufferedImage(240, 136, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = java.awt.Color(0x3366ff); fillRect(0, 0, 240, 136); dispose() }
        check(ImageIO.write(image, "jpg", out))
    }.toByteArray()
}
