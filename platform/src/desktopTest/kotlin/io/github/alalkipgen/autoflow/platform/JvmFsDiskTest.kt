package io.github.alalkipgen.autoflow.platform

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/** The :core stores on a real disk through [JvmFs]. */
class JvmFsDiskTest {
    private val tmp: Path = Files.createTempDirectory("autoflow-disk")
    private val fs = JvmFs()

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = tmp.deleteRecursively()

    @Test
    fun projectRoundTripsOnDiskWithBurmeseFolderName() {
        val dir = tmp.resolve("မိုးညသီချင်း.autoflow").toString().toPortablePath()
        val store = ProjectStore(fs)
        val created = store.create(dir, "မိုးည", Mode.MUSIC_VIDEO).ok()
        val saved = store.save(dir, created.copy(name = "မိုးည ၂")).ok()

        assertEquals(saved, ProjectStore(fs).open(dir).ok())
        assertEquals("မိုးည", store.openBackup(dir).ok().name)
        assertTrue(File(dir).list()!!.none { it.endsWith(".tmp") }, "no temp files left behind")
    }

    @Test
    fun artifactsAreDedupedAndRebuiltByScanFromAFreshStore() {
        val dir = tmp.resolve("p.autoflow").toString()
        ProjectStore(fs).create(dir, "p", Mode.MUSIC_VIDEO).ok()
        val bytes = "frame".encodeToByteArray()
        val first = ArtifactStore(fs, dir).put(bytes, ArtifactKind.IMAGE, "png", shot = 1).ok()
        assertEquals(first, ArtifactStore(fs, dir).put(bytes, ArtifactKind.IMAGE, "png", shot = 1).ok())

        val scan = ArtifactStore(fs, dir).scan().ok()
        assertEquals(listOf(first), scan.artifacts)
        assertEquals(emptyList(), scan.unreadable)
        assertContentEquals(bytes, ArtifactStore(fs, dir).readBytes(first.id).ok())
        val names = File(joinPath(dir, "artifacts")).list()!!.toList()
        assertTrue(names.none { it.startsWith(".tmp-") }, "no temp files left behind: $names")
    }

    @Test
    fun atomicMoveRetriesBrieflyLockedTargets() {
        var calls = 0
        val sleeps = mutableListOf<Long>()
        val fs = JvmFs(
            move = { _, _ -> if (++calls < 3) throw AccessDeniedException("target") },
            sleep = { sleeps += it },
            moveAttempts = 5,
        )
        fs.atomicMove(tmp.resolve("a").toString(), tmp.resolve("b").toString())
        assertEquals(3, calls)
        assertEquals(listOf(20L, 40L), sleeps)
    }

    @Test
    fun atomicMoveGivesUpAfterTheLastAttemptAndDoesNotRetryOtherErrors() {
        var calls = 0
        val locked = JvmFs(move = { _, _ -> calls++; throw AccessDeniedException("target") }, sleep = {}, moveAttempts = 3)
        assertFailsWith<AccessDeniedException> { locked.atomicMove(tmp.resolve("a").toString(), tmp.resolve("b").toString()) }
        assertEquals(3, calls)

        calls = 0
        val missing = JvmFs(move = { _, _ -> calls++; throw NoSuchFileException("a") }, sleep = {}, moveAttempts = 3)
        assertFailsWith<NoSuchFileException> { missing.atomicMove(tmp.resolve("a").toString(), tmp.resolve("b").toString()) }
        assertEquals(1, calls)
    }
}

internal fun <T> CoreResult<T>.ok(): T = when (this) {
    is CoreResult.Ok -> value
    is CoreResult.Err -> fail("expected Ok, got ${error.message}")
}
