package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.io.*
import io.github.alalkipgen.autoflow.media.tool.*
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlinx.coroutines.*
import kotlin.test.*

/** Deterministic port tests. Actual decoder proof is separately required in TakeWaveformFfmpegTest. */
class RecapTakeWaveformsTest {
    private val fs = InMemoryFileSystem()
    private val source = RecapTakeWaveforms.Source("/projects/a", "a", "r1", "block-a", "au_123",
        "/projects/a/artifacts/au_123.wav", 1_000)
    private val caller = Thread.currentThread().id
    private val calls = mutableListOf<List<String>>()
    private var output = pcmBytes(IntArray(8_000) { if (it % 2 == 0) -8192 else 16384 })
    private var writeOutput = true
    private var exit = 0
    private var timedOut = false
    private val locator = object : ToolLocator {
        override fun findFfmpeg() = "/fake/ffmpeg"
        override fun findVlc(): String? = null
        override fun isVlcDir(dir: String) = false
    }
    private val runner = ToolRunner { cmd, _, timeout, keep, _ ->
        assertNotEquals(caller, Thread.currentThread().id)
        assertEquals(30_000L, timeout); assertFalse(keep)
        calls += cmd
        if (writeOutput) fs.writeDurably(cmd.last(), output)
        ToolExec.Done(exit, "", emptyList(), 1, timedOut)
    }
    private fun cache(tool: ToolRunner? = runner, dir: String? = "/app/cache", files: FileSystem = fs) =
        RecapTakeWaveforms(files, MediaTools(SettingsStore(fs, "/app/settings.json"), locator, tool), dir)
    private fun seed(s: RecapTakeWaveforms.Source = source) {
        fs.createDirectories("${s.dir}/artifacts")
        fs.writeDurably(s.file, byteArrayOf(1, 2, 3))
        fs.writeDurably("${s.dir}/project.json", "untouched".encodeToByteArray())
    }
    private fun slot(s: RecapTakeWaveforms.Source = source) = "/app/cache/${s.key(3).take(2).toInt(16) % 64}.bin"
    private fun projectBytes() = fs.list("${source.dir}/artifacts").associateWith { fs.read("${source.dir}/artifacts/$it").toList() } +
        ("project.json" to fs.read("${source.dir}/project.json").toList())

    @Test fun signedLittleEndianBinsPreserveExtremaSilenceAndFullScaleWithoutNormalization() {
        val e = assertNotNull(RecapTakeWaveforms.pcm(pcmBytes(intArrayOf(-32768, -1, 0, 1, 32767))))
        assertEquals(listOf(-32768, -1, 0, 1, 32767), e.peaks.map { it.low })
        assertEquals(e.peaks.map { it.low }, e.peaks.map { it.high })
        val silence = assertNotNull(RecapTakeWaveforms.pcm(ByteArray(16_000)))
        assertEquals(1_000L, silence.durationMs); assertEquals(128, silence.peaks.size)
        assertTrue(silence.peaks.all { it.low == 0 && it.high == 0 })
        val varied = assertNotNull(RecapTakeWaveforms.pcm(pcmBytes(IntArray(256) { if (it % 2 == 0) -3 else 17 })))
        assertTrue(varied.peaks.all { it.low == -3 && it.high == 17 })
        assertNull(RecapTakeWaveforms.pcm(byteArrayOf())); assertNull(RecapTakeWaveforms.pcm(byteArrayOf(1)))
    }

    @Test fun workerDecodesOnlyItsAudioAndReusesCheckedDiskCacheWithoutTouchingAnyProjectFile() = runBlocking {
        seed(); val before = projectBytes()
        val checked = object : FileSystem by fs {
            override fun read(path: String): ByteArray {
                assertNotEquals(caller, Thread.currentThread().id, "PCM/cache IO must stay off caller/UI thread")
                return fs.read(path)
            }
        }
        val e = assertNotNull(cache(files = checked).load(source))
        assertEquals(8_000, e.samples); assertTrue(e.peaks.all { it.low == -8192 && it.high == 16384 })
        val cmd = calls.single()
        assertEquals(source.file, cmd[cmd.indexOf("-i") + 1])
        assertEquals("0:a:0", cmd[cmd.indexOf("-map") + 1])
        assertEquals("pcm_s16le", cmd[cmd.indexOf("-c:a") + 1]); assertEquals("s16le", cmd[cmd.indexOf("-f") + 1])
        assertEquals("600.001", cmd[cmd.indexOf("-t") + 1]); assertFalse("-ss" in cmd)
        assertEquals(e, cache(files = checked).load(source)); assertEquals(1, calls.size)
        assertEquals(before, projectBytes()); assertEquals(listOf("artifacts", "project.json"), fs.list(source.dir).sorted())
    }

    @Test fun collidingSlotsProjectsRevisionsAndTakesNeverShareAnEnvelope() = runBlocking {
        seed(); val e = assertNotNull(cache().load(source))
        val other = (1..512).map { source.copy(dir = "/projects/b", projectId = "b", revision = "r$it",
            file = "/projects/b/artifacts/au_123.wav") }.first { slot(it) == slot() }
        seed(other); output = ByteArray(16_000)
        val quiet = assertNotNull(cache().load(other)); assertNotEquals(e, quiet)
        output = pcmBytes(IntArray(8_000) { 200 })
        assertNotEquals(quiet, assertNotNull(cache().load(source)))
        assertNotNull(cache().load(source.copy(revision = "saved-again")))
        val take = source.copy(audioId = "au_456", file = "/projects/a/artifacts/au_456.wav")
        seed(take); assertNotNull(cache().load(take))
        assertEquals(5, calls.size)
    }

    @Test fun corruptCacheNoOutputFailureTimeoutOddOrTooLongPcmCannotReuseEarlierBars() = runBlocking {
        seed(); assertNotNull(cache().load(source))
        fs.writeDurably(slot(), "autoflow-take-waveform-v1\n${source.key(3)}\n8000\n-99999,1".encodeToByteArray())
        assertNotNull(cache().load(source)); assertEquals(2, calls.size)
        writeOutput = false; assertNull(cache().load(source.copy(revision = "no-output")))
        writeOutput = true; exit = 1; assertNull(cache().load(source.copy(revision = "failed-with-partial-output")))
        exit = 0; timedOut = true; assertNull(cache().load(source.copy(revision = "timeout")))
        timedOut = false; output = byteArrayOf(1); assertNull(cache().load(source.copy(revision = "odd")))
        output = ByteArray(9_600_002); assertNull(cache().load(source.copy(revision = "over-limit")))
        assertTrue(fs.list("/app/cache").none { it.startsWith("working-") || it.startsWith("pending-") })
    }

    @Test fun unavailableOrMissingInputUnsafeCacheAndOversizedMetadataStayFallbackWithoutWrites() = runBlocking {
        seed(); val before = projectBytes()
        assertNull(cache(dir = null).load(source))
        assertNull(cache(dir = "${source.dir}/.cache/waves").load(source))
        assertNull(cache(dir = "/app/cache/../../projects/a/cache").load(source))
        assertNull(cache().load(source.copy(file = "${source.dir}/artifacts/../project.json")))
        assertNull(cache().load(source.copy(file = "/projects/a/artifacts/missing.wav")))
        assertNull(cache().load(source.copy(durationMs = 600_001)))
        assertNull(cache().load(source.copy(durationMs = 0)))
        assertNull(cache(tool = null).load(source))
        assertNull(cache(ToolRunner { _, _, _, _, _ -> ToolExec.CannotStart("unavailable") }).load(source))
        assertEquals(0, calls.size); assertEquals(before, projectBytes())
        assertFalse(fs.exists("${source.dir}/.cache/waves"))
    }

    @Test fun missingOrUnreadableInputCannotDisplayEvenAValidCachedEnvelope() = runBlocking {
        seed(); assertNotNull(cache().load(source))
        val missing = object : FileSystem by fs {
            override fun exists(path: String) = path != source.file && fs.exists(path)
        }
        assertNull(cache(files = missing).load(source))
        val unreadable = object : FileSystem by fs {
            override fun size(path: String): Long = throw IllegalStateException("unreadable")
        }
        assertNull(cache(files = unreadable).load(source)); assertEquals(1, calls.size)
    }

    @Test fun coalescedConcurrentRequestsAndRepeatedRevisionsHaveBoundedNormalCacheStorage() = runBlocking {
        seed(); val store = cache()
        (1..12).map { async { assertNotNull(store.load(source)) } }.awaitAll()
        assertEquals(1, calls.size)
        repeat(96) { assertNotNull(store.load(source.copy(revision = "r$it"))) }
        assertTrue(fs.list("/app/cache").count { it.endsWith(".bin") } <= 64)
        assertTrue(fs.list("/app/cache").size <= 65)
    }

    @Test fun lateQueuedAndCancelledWorkNeverPublishesOrCachesAnOldSource() = runBlocking {
        seed(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var current = true
        val waiting = ToolRunner { cmd, _, _, _, _ ->
            started.complete(Unit); release.await(); fs.writeDurably(cmd.last(), output)
            ToolExec.Done(0, "", emptyList(), 1, false)
        }
        val store = cache(waiting)
        val job = async { store.load(source) { current } }
        started.await()
        val queued = async { store.load(source.copy(revision = "queued")) { current } }
        current = false; release.complete(Unit)
        assertNull(job.await()); assertNull(queued.await()); assertFalse(fs.exists(slot()))
        val cancellation = CompletableDeferred<Unit>()
        val cancelled = async { cache(ToolRunner { _, _, _, _, _ -> cancellation.complete(Unit); awaitCancellation() }).load(source) }
        cancellation.await(); cancelled.cancel(); assertFailsWith<CancellationException> { cancelled.await() }
        assertTrue(fs.list("/app/cache").none { it.startsWith("working-") || it.startsWith("pending-") })
    }

    companion object {
        internal fun pcmBytes(samples: IntArray): ByteArray = ByteArray(samples.size * 2).also { bytes ->
            samples.forEachIndexed { i, sample -> bytes[i * 2] = sample.toByte(); bytes[i * 2 + 1] = (sample shr 8).toByte() }
        }
    }
}