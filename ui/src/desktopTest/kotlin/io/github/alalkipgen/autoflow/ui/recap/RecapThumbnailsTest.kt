package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.ui.graphics.toAwtImage
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.media.tool.*
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlinx.coroutines.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class RecapThumbnailsTest {
    private val fs = InMemoryFileSystem()
    private val source = RecapThumbnails.Source("/projects/a", "a", "revision-1",
        RecapMovie("source/movie.mp4", 3, 10_000, 320, 180))
    private val calls = mutableListOf<List<String>>()
    private var colour = 0xff0000
    private var output = true
    private val caller = Thread.currentThread().id
    private val jpeg get() = ByteArrayOutputStream().also { out ->
        val image = BufferedImage(240, 136, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = java.awt.Color(colour); fillRect(0, 0, 240, 136); dispose() }
        check(ImageIO.write(image, "jpg", out))
    }.toByteArray()
    private val locator = object : ToolLocator {
        override fun findFfmpeg() = "/fake/ffmpeg"
        override fun findVlc(): String? = null
        override fun isVlcDir(dir: String) = false
    }
    private val runner = ToolRunner { cmd, _, _, _, _ ->
        assertNotEquals(caller, Thread.currentThread().id, "ffmpeg and image IO must not run on the caller/UI thread")
        calls += cmd
        if (output) fs.writeDurably(cmd.last(), jpeg)
        ToolExec.Done(0, "", emptyList(), 1, false)
    }
    private fun cache(tool: ToolRunner = runner, dir: String? = "/app/cache") =
        RecapThumbnails(fs, MediaTools(SettingsStore(fs, "/app/settings.json"), locator, tool), dir)
    private fun seed(s: RecapThumbnails.Source = source) {
        fs.createDirectories("${s.dir}/source")
        fs.createDirectories("${s.dir}/artifacts")
        fs.writeDurably("${s.dir}/${s.movie.file}", byteArrayOf(1, 2, 3))
        fs.writeDurably("${s.dir}/project.json", "unchanged project".encodeToByteArray())
        fs.writeDurably("${s.dir}/artifacts/immutable", byteArrayOf(9, 8, 7))
    }
    private fun projectBytes() = listOf("source/movie.mp4", "project.json", "artifacts/immutable").associateWith {
        fs.read("${source.dir}/$it").toList()
    }
    private fun slot(s: RecapThumbnails.Source = source, start: Long = 2_000, end: Long = 4_000) =
        "/app/cache/${s.key(start, end).take(2).toInt(16) % 64}.bin"

    @Test fun midpointDiskReuseAndAllProjectBytesStayUnchanged() = runBlocking {
        seed(); val before = projectBytes()
        val image = assertNotNull(cache().load(source, 2_000, 4_000))
        assertEquals(240, image.width); assertEquals(136, image.height)
        assertEquals("3.000", calls.single().let { it[it.indexOf("-ss") + 1] })
        assertEquals("${source.dir}/${source.movie.file}", calls.single().let { it[it.indexOf("-i") + 1] })
        assertNotNull(cache().load(source, 2_000, 4_000)) // New instance, persisted cache, not a remembered bitmap.
        assertEquals(1, calls.size)
        assertNotNull(cache().load(source, 2_000, 2_500))
        assertEquals("2.250", calls.last().let { it[it.indexOf("-ss") + 1] }, "end/trim participates in the cache key")
        assertEquals(before, projectBytes())
        assertEquals(listOf("artifacts", "project.json", "source").sorted(), fs.list(source.dir).sorted())
    }

    @Test fun sameSizeReplacementProjectIdentityAndSlotCollisionsNeverReuseAnotherFrame() = runBlocking {
        seed(); val store = cache()
        assertNotNull(store.load(source, 2_000, 4_000))
        val other = (1..512).map { source.copy(dir = "/projects/b", projectId = "b", revision = "b-$it") }
            .first { slot(it) == slot(source) }
        seed(other); colour = 0x0000ff
        val blue = assertNotNull(store.load(other, 2_000, 4_000)).toAwtImage().getRGB(120, 68)
        colour = 0xff0000
        val red = assertNotNull(store.load(source, 2_000, 4_000)).toAwtImage().getRGB(120, 68)
        assertNotEquals(blue, red); assertEquals(3, calls.size)
        // Same path, byte count and duration after a replacement: a new saved revision is still a new source.
        fs.writeDurably("${source.dir}/${source.movie.file}", byteArrayOf(4, 5, 6)); colour = 0x00ff00
        val green = assertNotNull(store.load(source.copy(revision = "replacement"), 2_000, 4_000)).toAwtImage().getRGB(120, 68)
        assertNotEquals(red, green); assertEquals(4, calls.size)
    }

    @Test fun corruptCacheAndSuccessfulProcessWithNoOutputCannotShowThePreviousImage() = runBlocking {
        seed(); val store = cache()
        assertNotNull(store.load(source, 2_000, 4_000))
        fs.writeDurably(slot(), "autoflow-scene-thumb-v1\n${source.key(2_000, 4_000)}\nnot an image".encodeToByteArray())
        assertNotNull(store.load(source, 2_000, 4_000)); assertEquals(2, calls.size)
        output = false
        assertNull(store.load(source.copy(revision = "no-output"), 2_000, 4_000))
        val failure = ToolRunner { _, _, _, _, _ -> ToolExec.CannotStart("missing") }
        assertNull(cache(failure).load(source.copy(revision = "failed"), 2_000, 4_000))
        assertTrue(fs.list("/app/cache").none { it.startsWith("working-") || it.startsWith("pending-") })
    }

    @Test fun invalidUnavailableAndProjectLocalCacheKeepTheTimeOnlyFallbackWithoutWrites() = runBlocking {
        seed(); val before = projectBytes()
        assertNull(cache(dir = null).load(source, 2_000, 4_000))
        assertNull(cache(dir = "${source.dir}/.cache/thumbs").load(source, 2_000, 4_000))
        assertNull(cache().load(source, -1, 4_000))
        assertNull(cache().load(source, 4_000, 4_000))
        assertNull(cache().load(source, 9_000, 11_000))
        assertNull(cache().load(source.copy(dir = "/missing"), 2_000, 4_000))
        assertEquals(0, calls.size); assertEquals(before, projectBytes())
        assertFalse(fs.exists("${source.dir}/.cache/thumbs"))
    }

    @Test fun concurrentRequestsCoalesceAndNormalCacheStorageIsBounded() = runBlocking {
        seed(); val store = cache()
        (1..12).map { async { assertNotNull(store.load(source, 2_000, 4_000)) } }.awaitAll()
        assertEquals(1, calls.size)
        repeat(96) { assertNotNull(store.load(source.copy(revision = "r$it"), 2_000, 4_000)) }
        assertTrue(fs.list("/app/cache").count { it.endsWith(".bin") } <= 64)
        assertTrue(fs.list("/app/cache").size <= 65, "completed requests leave only checked slots and a recyclable discard file")
    }

    @Test fun switchWhileQueuedOrDecodingDiscardsResultsAndCancellationPropagates() = runBlocking {
        seed(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var current = true
        val waiting = ToolRunner { cmd, _, _, _, _ ->
            started.complete(Unit); release.await()
            fs.writeDurably(cmd.last(), jpeg)
            ToolExec.Done(0, "", emptyList(), 1, false)
        }
        val store = cache(waiting)
        val job = async { store.load(source, 2_000, 4_000) { current } }
        started.await(); current = false; release.complete(Unit)
        assertNull(job.await()); assertFalse(fs.exists(slot()))
        assertNull(store.load(source, 2_000, 4_000) { false })
        val cancelledStarted = CompletableDeferred<Unit>()
        val blocked = cache(ToolRunner { _, _, _, _, _ -> cancelledStarted.complete(Unit); awaitCancellation() })
        val cancelled = async { blocked.load(source, 2_000, 4_000) }
        cancelledStarted.await(); cancelled.cancel()
        assertFailsWith<CancellationException> { cancelled.await() }
        assertTrue(fs.list("/app/cache").none { it.startsWith("working-") || it.startsWith("pending-") })
    }
}