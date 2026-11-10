package io.github.alalkipgen.autoflow.platform

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The same behaviour from the in-memory fake (used by :core tests) and the real disk implementation. */
abstract class FileSystemContractTest {
    protected abstract val fs: FileSystem
    protected abstract val root: String

    private fun path(vararg parts: String) = parts.fold(root) { dir, name -> joinPath(dir, name) }

    @Test
    fun createDirectoriesMakesParentsAndIsIdempotent() {
        fs.createDirectories(path("a", "b", "c"))
        fs.createDirectories(path("a", "b", "c"))
        assertTrue(fs.exists(path("a", "b", "c")))
        assertTrue(fs.exists(path("a")))
    }

    @Test
    fun listReturnsSortedChildNamesAndEmptyForNonDirectories() {
        fs.createDirectories(path("d", "sub"))
        fs.writeDurably(path("d", "b.txt"), byteArrayOf(1))
        fs.writeDurably(path("d", "a.txt"), byteArrayOf(2))
        assertEquals(listOf("a.txt", "b.txt", "sub"), fs.list(path("d")))
        assertEquals(emptyList(), fs.list(path("d", "a.txt")))
        assertEquals(emptyList(), fs.list(path("missing")))
    }

    @Test
    fun writeDurablyCreatesAndTruncates() {
        fs.createDirectories(root)
        fs.writeDurably(path("f.bin"), ByteArray(100) { 7 })
        fs.writeDurably(path("f.bin"), byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), fs.read(path("f.bin")))
    }

    @Test
    fun writeDurablyNeedsAnExistingParent() {
        fs.createDirectories(root)
        assertFailsWith<Exception> { fs.writeDurably(path("nope", "f.bin"), byteArrayOf(1)) }
        assertFalse(fs.exists(path("nope")))
    }

    @Test
    fun readMissingFileFails() {
        fs.createDirectories(root)
        assertFailsWith<Exception> { fs.read(path("missing.bin")) }
    }

    @Test
    fun atomicMoveRenamesAndReplaces() {
        fs.createDirectories(root)
        fs.writeDurably(path("new.tmp"), byteArrayOf(9))
        fs.writeDurably(path("target"), byteArrayOf(1))
        fs.atomicMove(path("new.tmp"), path("target"))
        assertFalse(fs.exists(path("new.tmp")))
        assertContentEquals(byteArrayOf(9), fs.read(path("target")))
        assertEquals(listOf("target"), fs.list(root))
    }

    @Test
    fun atomicMoveOfMissingSourceFails() {
        fs.createDirectories(root)
        assertFailsWith<Exception> { fs.atomicMove(path("missing"), path("target")) }
    }

    @Test
    fun createDirectoriesOverAFileFails() {
        fs.createDirectories(root)
        fs.writeDurably(path("file"), byteArrayOf(1))
        assertFailsWith<Exception> { fs.createDirectories(path("file")) }
    }
}

class InMemoryFileSystemContractTest : FileSystemContractTest() {
    override val fs = InMemoryFileSystem()
    override val root = "/work"
}

class JvmFsContractTest : FileSystemContractTest() {
    private val tmp: Path = Files.createTempDirectory("autoflow-fs")
    override val fs = JvmFs()
    override val root = tmp.resolve("root").toString().toPortablePath()

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = tmp.deleteRecursively()
}
