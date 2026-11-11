package io.github.alalkipgen.autoflow.platform

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class ProjectZipTest {
    private val tmp: File = Files.createTempDirectory("af-zip").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun file(path: String, bytes: ByteArray) = File(tmp, path).apply { parentFile.mkdirs(); writeBytes(bytes) }

    private fun project(name: String): File {
        file("$name/project.json", """{"name":"$name"}""".encodeToByteArray())
        file("$name/source/lyrics.txt", "မိုးရွာနေတဲ့ ည\n".repeat(200).encodeToByteArray())
        file("$name/artifacts/vd_aaaaaaaaaaaa.mp4", ByteArray(300_000) { (it * 7).toByte() })
        file("$name/exports/mv.mp4", ByteArray(10) { 1 })
        file("$name/exports/mv-next.part.mp4", ByteArray(10))
        file("$name/project.json.tmp", ByteArray(3))
        file("$name/.cache/thumbs/1.png", ByteArray(50))
        return File(tmp, name)
    }

    @Test
    fun planSkipsCacheHalfWrittenAndMissingFoldersAndRenamesRepeats() {
        val a = project("one/Rain.autoflow")
        val b = project("two/Rain.autoflow")
        val plan = ProjectZip.plan(
            listOf(BackupProject(a.path, "Rain.autoflow"), BackupProject(File(tmp, "gone").path, "Gone.autoflow"), BackupProject(b.path, "Rain.autoflow")),
        )
        assertEquals(listOf("Rain.autoflow", "Rain.autoflow (2)"), plan.projects.map { it.name })
        assertEquals(
            listOf(
                "Rain.autoflow/artifacts/vd_aaaaaaaaaaaa.mp4",
                "Rain.autoflow/exports/mv.mp4",
                "Rain.autoflow/project.json",
                "Rain.autoflow/source/lyrics.txt",
            ),
            plan.entries.filter { it.path.startsWith("Rain.autoflow/") }.map { it.path },
        )
        assertEquals(8, plan.entries.size)
        assertEquals(plan.entries.sumOf { it.size }, plan.totalBytes)
    }

    @Test
    fun zipHoldsTheFilesAndAManifestWithMediaStoredUncompressed() = runTest {
        val a = project("Rain.autoflow")
        val plan = ProjectZip.plan(listOf(BackupProject(a.path, "Rain.autoflow")))
        val seen = mutableListOf<BackupProgress>()
        val out = ByteArrayOutputStream()
        val size = ProjectZip.write(plan, out, "2026-11-11T10:00", seen::add)
        assertEquals(out.size().toLong(), size)
        assertEquals(BackupProgress(plan.totalBytes, plan.totalBytes), seen.last())
        assertEquals(0L, seen.first().doneBytes)

        val zipFile = File(tmp, "b.zip").apply { writeBytes(out.toByteArray()) }
        ZipFile(zipFile).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(plan.entries.map { it.path } + ProjectZip.MANIFEST, names)
            for (e in plan.entries) assertTrue(zip.getInputStream(zip.getEntry(e.path)).readBytes().contentEquals(e.file.readBytes()), e.path)
            val video = zip.getEntry("Rain.autoflow/artifacts/vd_aaaaaaaaaaaa.mp4")
            assertTrue(video.compressedSize >= video.size, "media is stored, not deflated")
            val lyrics = zip.getEntry("Rain.autoflow/source/lyrics.txt")
            assertTrue(lyrics.compressedSize < lyrics.size / 4, "text is deflated")
            val manifest = zip.getInputStream(zip.getEntry(ProjectZip.MANIFEST)).readBytes().decodeToString()
            assertEquals(
                """{"format":"autoflow-backup","version":1,"createdAt":"2026-11-11T10:00","projects":[{"folder":"Rain.autoflow","files":4,"bytes":${plan.totalBytes}}]}""",
                manifest,
            )
        }
    }

    @Test
    fun cancellingStopsTheZip() = runTest {
        val a = project("Rain.autoflow")
        file("Rain.autoflow/artifacts/vd_bbbbbbbbbbbb.mp4", ByteArray(3_000_000))
        val plan = ProjectZip.plan(listOf(BackupProject(a.path, "Rain.autoflow")))
        val job = Job()
        var calls = 0
        assertFailsWith<CancellationException> {
            withContext(job) {
                ProjectZip.write(plan, ByteArrayOutputStream(), "t") { if (++calls == 2) job.cancel() }
            }
        }
        assertTrue(calls < 5, "stopped within a chunk, after $calls progress calls")
    }

    @Test
    fun namesThatAreSkippedOrStored() {
        assertTrue(ProjectZip.skipped("mv-tiktok1080.part.mp4"))
        assertTrue(ProjectZip.skipped("projects.json.tmp"))
        assertEquals(false, ProjectZip.skipped("party.mp4"))
        assertTrue(ProjectZip.isCompressed("a/b/IM_1.JPG"))
        assertEquals(false, ProjectZip.isCompressed("a/b/sb_123.json"))
    }
}
