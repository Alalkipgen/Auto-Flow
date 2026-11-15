package io.github.alalkipgen.autoflow.platform

import io.github.alalkipgen.autoflow.core.CoreError
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Project
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Audit F04 on a real disk: two saves of one project folder — two threads, many threads, or two processes — never
 * interleave, and a save made from an older copy is refused instead of reporting success for bytes it did not write.
 */
class ProjectSaveConcurrencyTest {
    private val tmp: Path = Files.createTempDirectory("autoflow-lock")
    private val dir = tmp.resolve("P.autoflow").toString().toPortablePath()

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = tmp.deleteRecursively()

    private fun <T> CoreResult<T>.ok(): T = assertIs<CoreResult.Ok<T>>(this).value

    @Test
    fun concurrentSaveMustNotReturnSuccessForDifferentBytes() {
        val original = ProjectStore(JvmFs()).create(dir, "original", Mode.MUSIC_VIDEO).ok()
        val aWroteTmp = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val bAsked = CountDownLatch(1)
        val bInside = AtomicBoolean(false)
        val disk = JvmFs()
        val fs = object : FileSystem by disk {
            override fun writeDurably(path: String, bytes: ByteArray) {
                disk.writeDurably(path, bytes)
                if (path.endsWith("project.json.tmp") && Thread.currentThread().name == "writer-A") {
                    aWroteTmp.countDown()
                    check(releaseA.await(20, TimeUnit.SECONDS))
                }
            }

            override fun <T> withFolderLock(dir: String, block: () -> T): T {
                if (Thread.currentThread().name == "writer-B") bAsked.countDown()
                return disk.withFolderLock(dir) {
                    if (Thread.currentThread().name == "writer-B") bInside.set(true)
                    block()
                }
            }
        }
        val store = ProjectStore(fs)
        val a = AtomicReference<CoreResult<Project>>()
        val b = AtomicReference<CoreResult<Project>>()
        val writerA = Thread({ a.set(store.save(dir, original.copy(name = "A"))) }, "writer-A").apply { start() }
        assertTrue(aWroteTmp.await(20, TimeUnit.SECONDS))
        val writerB = Thread({ b.set(store.save(dir, original.copy(name = "B"))) }, "writer-B").apply { start() }
        assertTrue(bAsked.await(20, TimeUnit.SECONDS))
        Thread.sleep(300)
        // A is paused between writing its temp file and renaming it: B must be waiting, not writing the same temp file.
        assertFalse(bInside.get(), "B entered the folder while A's save was in progress")
        releaseA.countDown()
        writerA.join(20_000)
        writerB.join(20_000)

        val savedA = a.get().ok()
        assertEquals("A", savedA.name)
        assertEquals(CoreError.Conflict("$dir/project.json"), assertIs<CoreResult.Err>(b.get()).error)
        assertEquals(CoreJson.encodeToString(savedA), File(dir, "project.json").readText(), "A's reported bytes are on disk")
        assertTrue(File(dir).list()!!.none { it.endsWith(".tmp") })
    }

    @Test
    fun manyThreadsUpdatingOneFolderLoseNothing() {
        ProjectStore(JvmFs()).create(dir, "0", Mode.MUSIC_VIDEO).ok()
        val threads = 8
        val each = 25
        val start = CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val workers = (1..threads).map { t ->
            // A store and a JvmFs per thread: the lock is per folder for the whole process, not per instance.
            val store = ProjectStore(JvmFs())
            Thread({
                start.await()
                repeat(each) {
                    val r = store.update(dir) { p -> p.copy(name = (p.name.toInt() + 1).toString()) }
                    if (r !is CoreResult.Ok) failures += r.toString()
                }
            }, "updater-$t").apply { start() }
        }
        start.countDown()
        workers.forEach { it.join(60_000) }
        assertEquals(emptyList(), failures.toList())
        assertEquals((threads * each).toString(), ProjectStore(JvmFs()).open(dir).ok().name)
    }

    @Test
    fun aSecondProcessHoldingTheFolderWaitsTheSave() {
        val store = ProjectStore(JvmFs())
        val created = store.create(dir, "original", Mode.MUSIC_VIDEO).ok()
        val ready = tmp.resolve("ready").toFile()
        val release = tmp.resolve("release").toFile()
        val child = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp", childClassPath(),
            FolderLockHolder::class.java.name, dir, ready.path, release.path,
        ).redirectErrorStream(true).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!ready.exists()) {
                check(child.isAlive) { "lock holder exited: " + child.inputStream.bufferedReader().readText() }
                check(System.nanoTime() < deadline) { "lock holder did not start" }
                Thread.sleep(20)
            }
            val result = AtomicReference<CoreResult<Project>>()
            val saver = Thread { result.set(store.save(dir, created.copy(name = "after the other process"))) }.apply { start() }
            saver.join(700)
            assertTrue(saver.isAlive, "the save must wait while another process holds the folder")
            assertEquals("original", store.open(dir).ok().name)
            release.createNewFile()
            saver.join(20_000)
            assertEquals("after the other process", result.get().ok().name)
            assertTrue(child.waitFor(20, TimeUnit.SECONDS))
            assertEquals(0, child.exitValue())
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    fun theLockNeverCreatesAMissingFolder() {
        val missing = tmp.resolve("missing.autoflow").toString().toPortablePath()
        assertEquals(1, JvmFs().withFolderLock(missing) { 1 })
        assertFalse(File(missing).exists())
    }

    private fun childClassPath(): String = listOf(FolderLockHolder::class.java, JvmFs::class.java, FileSystem::class.java, Unit::class.java)
        .map { File(it.protectionDomain.codeSource.location.toURI()).path }
        .distinct()
        .joinToString(File.pathSeparator)
}

/** Child-process main for [ProjectSaveConcurrencyTest]: holds the folder lock of `args[0]` until `args[2]` exists. */
object FolderLockHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        val (dir, ready, release) = args
        JvmFs().withFolderLock(dir) {
            File(ready).createNewFile()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!File(release).exists() && System.nanoTime() < deadline) Thread.sleep(20)
        }
    }
}
