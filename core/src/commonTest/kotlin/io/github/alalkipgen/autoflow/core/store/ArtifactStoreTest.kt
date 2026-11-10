package io.github.alalkipgen.autoflow.core.store

import io.github.alalkipgen.autoflow.core.CoreError
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArtifactStoreTest {
    private val fs = InMemoryFileSystem()
    private val store = ArtifactStore(fs, "/p", TestClock())
    private val png = byteArrayOf(1, 2, 3)

    private fun <T> CoreResult<T>.ok(): T = assertIs<CoreResult.Ok<T>>(this).value
    private fun CoreResult<*>.err(): CoreError = assertIs<CoreResult.Err>(this).error

    @Test
    fun putNamesByKindAndContentHash() {
        val a = store.put(png, ArtifactKind.IMAGE, "png", shot = 3, inputs = mapOf("promptIr" to "pr_444444444444")).ok()
        assertEquals(ArtifactId("im_" + Sha256.hex(png).take(12)), a.id)
        assertEquals("${a.id}.png", a.file)
        assertContentEquals(png, fs.read("/p/artifacts/${a.file}"))
        assertTrue(fs.exists("/p/artifacts/${a.id}.meta.json"))
        assertEquals(a, store.get(a.id).ok())
        assertContentEquals(png, store.readBytes(a.id).ok())
        assertTrue(store.exists(a.id))
    }

    @Test
    fun identicalBytesDedupeToTheFirstRecord() {
        val first = store.put(png, ArtifactKind.IMAGE, "png", shot = 3).ok()
        val writes = fs.log.count { it.startsWith("write") }
        val second = store.put(png, ArtifactKind.IMAGE, "png", shot = 5, note = "again").ok()
        assertEquals(first, second)
        assertEquals(writes, fs.log.count { it.startsWith("write") })
        assertTrue(store.put(byteArrayOf(9), ArtifactKind.IMAGE, "png").ok().id != first.id)
    }

    @Test
    fun neverReplacesAnExistingArtifactFile() {
        repeat(3) { store.put(png, ArtifactKind.IMAGE, "png") }
        store.put("""{"a":1}""".encodeToByteArray(), ArtifactKind.SHOT_SPEC, "json", shot = 1)
        assertTrue(fs.log.none { it.endsWith("(replace)") }, fs.log.joinToString("\n"))
    }

    @Test
    fun crashBetweenDataAndSidecarIsCompletedByTheNextPut() {
        fs.failWhen = { op, path -> op == "write" && path.endsWith(".meta.json") }
        assertIs<CoreError.Io>(store.put(png, ArtifactKind.IMAGE, "png").err())
        fs.failWhen = { _, _ -> false }
        val a = store.put(png, ArtifactKind.IMAGE, "png").ok()
        assertTrue(store.exists(a.id))
    }

    @Test
    fun differentBytesUnderAnExistingIdAreAHashCollision() {
        val a = store.put(png, ArtifactKind.IMAGE, "png").ok()
        fs.writeDurably("/p/artifacts/${a.file}", byteArrayOf(7, 7)) // simulate a foreign file under the same name
        assertEquals(CoreError.HashCollision(a.id), store.put(png, ArtifactKind.IMAGE, "png").err())
        assertContentEquals(byteArrayOf(7, 7), fs.read("/p/artifacts/${a.file}"))
    }

    @Test
    fun listByShotFiltersAndSortsOldestFirst() {
        val a = store.put(byteArrayOf(1), ArtifactKind.IMAGE, "png", shot = 2).ok()
        store.put(byteArrayOf(2), ArtifactKind.IMAGE, "png", shot = 1).ok()
        val c = store.put(byteArrayOf(3), ArtifactKind.VIDEO, "mp4", shot = 2).ok()
        store.put(byteArrayOf(4), ArtifactKind.ANALYSIS, "json").ok()
        assertEquals(listOf(a, c), store.listByShot(2).ok())
        assertEquals(emptyList(), store.listByShot(9).ok())
    }

    @Test
    fun rejectsBadExtensionAndReportsMissingOrCorrupt() {
        assertIs<CoreError.InvalidInput>(store.put(png, ArtifactKind.IMAGE, "../x").err())
        assertIs<CoreError.InvalidInput>(store.put(png, ArtifactKind.IMAGE, "PNG").err())
        val missing = ArtifactId("im_000000000000")
        assertIs<CoreError.NotFound>(store.get(missing).err())
        assertFalse(store.exists(missing))
        fs.createDirectories("/p/artifacts")
        fs.writeDurably("/p/artifacts/$missing.meta.json", "{}".encodeToByteArray())
        assertIs<CoreError.Corrupt>(store.get(missing).err())
        assertIs<CoreError.Corrupt>(store.listByShot(1).err())
    }
}
