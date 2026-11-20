package io.github.alalkipgen.autoflow.platform

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
            for (e in plan.entries) assertTrue(zip.getInputStream(zip.getEntry(e.path)).readBytes().contentEquals(e.file!!.readBytes()), e.path)
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

    // ---- restore (audit F01) ----

    private val home get() = File(tmp, "home").apply { mkdirs() }
    private val projects get() = File(home, "projects").apply { mkdirs() }

    private fun realProject(name: String, json: String = """{"schemaVersion":2,"id":"id-$name","name":"$name","mode":"MOVIE_RECAP"}"""): File {
        file("src/$name/project.json", json.encodeToByteArray())
        file("src/$name/artifacts/vd_aaaaaaaaaaaa.mp4", ByteArray(40_000) { (it * 13).toByte() })
        file("src/$name/source/lyrics.txt", "မိုးရွာ $name\n".encodeToByteArray())
        return File(tmp, "src/$name")
    }

    private suspend fun backupOf(vararg names: String): ByteArray {
        val plan = ProjectZip.plan(names.map { BackupProject(realProject(it).path, it) })
        return ByteArrayOutputStream().also { ProjectZip.write(plan, it, "2026-11-15T10:00") }.toByteArray()
    }

    /** A hand-made zip; [manifest] null = none, otherwise listed after the entries like [ProjectZip.write] does. */
    private fun zipOf(entries: List<Pair<String, ByteArray>>, manifest: String? = null): ByteArray = ByteArrayOutputStream().also { b ->
        ZipOutputStream(b).use { z ->
            for ((name, bytes) in entries) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            if (manifest != null) { z.putNextEntry(ZipEntry(ProjectZip.MANIFEST)); z.write(manifest.encodeToByteArray()); z.closeEntry() }
        }
    }.toByteArray()

    private fun manifestFor(vararg folders: Pair<String, Int>) =
        """{"format":"autoflow-backup","version":1,"createdAt":"t","projects":[${folders.joinToString(",") { """{"folder":"${it.first}","files":${it.second},"bytes":1}""" }}]}"""

    private val validJson = """{"schemaVersion":2,"id":"x","name":"X","mode":"MOVIE_RECAP"}""".encodeToByteArray()

    /** Every path under [dir] (links not followed) with its bytes, a link's target or `<dir>`. */
    private fun tree(dir: File): Map<String, String> {
        if (!dir.exists()) return emptyMap()
        return Files.walk(dir.toPath()).use { s ->
            s.toList().associate { p ->
                val rel = dir.toPath().relativize(p).toString()
                rel to when {
                    Files.isSymbolicLink(p) -> "link->" + Files.readSymbolicLink(p)
                    Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) -> "<dir>"
                    else -> Files.readAllBytes(p).contentHashCode().toString() + ":" + Files.size(p)
                }
            }
        }
    }

    private fun noStagingLeft() = assertEquals(emptyList(), projects.list()!!.filter { it.startsWith(".restore-") }, "staging folder removed")

    @Test
    fun restoreRoundTripsABackupByteForByte() = runTest {
        val zip = backupOf("Rain.autoflow", "Sun.autoflow")
        val restored = ProjectZip.restore(ByteArrayInputStream(zip), projects)
        assertEquals(listOf(RestoredProject("Rain.autoflow", "Rain.autoflow"), RestoredProject("Sun.autoflow", "Sun.autoflow")), restored)
        for (name in listOf("Rain.autoflow", "Sun.autoflow")) assertEquals(tree(File(tmp, "src/$name")), tree(File(projects, name)), name)
        noStagingLeft()
    }

    @Test
    fun restoreUsesStrictUtf8ForBurmeseNamesAndProjectText() = runTest {
        val name = "မိုးရွာတဲ့ည.autoflow"
        val source = realProject(name,
            """{"id":"မြန်မာ-id","name":"မိုးရွာတဲ့ည","mode":"MOVIE_RECAP"}""")
        file("src/$name/source/စာသား.txt", "မြန်မာစာ\nEnglish prompt untouched".encodeToByteArray())
        val out = ByteArrayOutputStream()
        ProjectZip.write(ProjectZip.plan(listOf(BackupProject(source.path, name))), out, "t")
        val restored = ProjectZip.restore(ByteArrayInputStream(out.toByteArray()), projects).single()
        assertEquals(name, restored.folder)
        assertEquals(tree(source), tree(File(projects, name)))
        noStagingLeft()
    }

    @Test
    fun restoreRejectsMalformedUtf8EvenInsideOtherwiseValidJson() = runTest {
        file("home/projects/Keep.autoflow/project.json", "KEEP".encodeToByteArray())
        val before = tree(home)
        // Replacing malformed bytes with U+FFFD would turn these into valid JSON. Never do that.
        val invalid = listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xe1.toByte(), 0x80.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()), byteArrayOf(0xff.toByte()))
        for (bad in invalid) {
            val bytes = """{"id":"x","name":"""".encodeToByteArray() + bad +
                """","mode":"MOVIE_RECAP"}""".encodeToByteArray()
            val zip = zipOf(listOf("Bad.autoflow/project.json" to bytes), manifestFor("Bad.autoflow" to 1))
            val error = assertFailsWith<RestoreException> { ProjectZip.restore(ByteArrayInputStream(zip), projects) }
            assertTrue(error.message!!.contains("no readable project.json"))
            assertEquals(before, tree(home), "invalid UTF-8 cannot publish or change any project")
            noStagingLeft()
        }
    }

    @Test
    fun zipRestoreMustStayInsideDestination() = runTest {
        val bad = listOf(
            "../escaped.txt", "Proj.autoflow/../../escaped.txt", "/tmp/af-absolute-escape.txt", "Proj.autoflow\\..\\..\\escaped.txt",
            "C:/escaped.txt", "Proj.autoflow/C:escaped.txt", "Proj.autoflow/./project.json", "Proj.autoflow//x", ".restore-x/project.json",
        )
        val existing = file("home/projects/Keep.autoflow/project.json", "NEW WORK".encodeToByteArray())
        val before = tree(home)
        for (name in bad) {
            val zip = zipOf(listOf("Proj.autoflow/project.json" to validJson, name to "OUTSIDE".encodeToByteArray()), manifestFor("Proj.autoflow" to 2))
            val e = assertFailsWith<RestoreException>(name) { ProjectZip.restore(ByteArrayInputStream(zip), projects) }
            assertTrue(e.message!!.startsWith("Unsafe name"), "$name: ${e.message}")
            assertEquals(before, tree(home), "nothing written anywhere under home for $name")
            assertFalse(File(tmp, "escaped.txt").exists() || File("/tmp/af-absolute-escape.txt").exists(), name)
            noStagingLeft()
        }
        assertEquals("NEW WORK", existing.readText())
    }

    @Test
    fun restoreMustNotOverwriteExistingProjectsWithoutConsent() = runTest {
        val current = file("home/projects/Same.autoflow/project.json", """{"id":"mine","name":"NEW WORK","mode":"MOVIE_RECAP"}""".encodeToByteArray())
        file("home/projects/Same.autoflow/source/lyrics.txt", "new lyrics".encodeToByteArray())
        val before = tree(File(projects, "Same.autoflow"))
        val zip = backupOf("Same.autoflow")

        val first = ProjectZip.restore(ByteArrayInputStream(zip), projects)
        assertEquals(listOf(RestoredProject("Same (restored).autoflow", "Same.autoflow")), first)
        assertTrue(first.single().renamed)
        val second = ProjectZip.restore(ByteArrayInputStream(zip), projects)
        assertEquals(listOf(RestoredProject("Same (restored 2).autoflow", "Same.autoflow")), second)

        assertEquals(before, tree(File(projects, "Same.autoflow")), "the existing project is byte-identical")
        assertContentEquals("""{"id":"mine","name":"NEW WORK","mode":"MOVIE_RECAP"}""".encodeToByteArray(), current.readBytes())
        for (copy in listOf("Same (restored).autoflow", "Same (restored 2).autoflow")) {
            assertEquals(tree(File(tmp, "src/Same.autoflow")), tree(File(projects, copy)), copy)
        }
        noStagingLeft()
    }

    @Test
    fun restoreNeverWritesThroughAPreExistingLink() = runTest {
        val outside = File(tmp, "outside").apply { mkdirs() }
        File(outside, "project.json").writeText("OUTSIDE DATA")
        Files.createSymbolicLink(File(projects, "Evil.autoflow").toPath(), outside.toPath())
        Files.createSymbolicLink(File(projects, "Dangling.autoflow").toPath(), File(tmp, "nowhere").toPath())
        val before = tree(outside)
        val restored = ProjectZip.restore(ByteArrayInputStream(backupOf("Evil.autoflow", "Dangling.autoflow")), projects)
        assertEquals(listOf("Dangling (restored).autoflow", "Evil (restored).autoflow"), restored.map { it.folder })
        assertEquals(before, tree(outside), "the linked folder is untouched")
        assertTrue(Files.isSymbolicLink(File(projects, "Evil.autoflow").toPath()))
        assertFalse(File(tmp, "nowhere").exists(), "nothing created through the dangling link")
        assertEquals(tree(File(tmp, "src/Evil.autoflow")), tree(File(projects, "Evil (restored).autoflow")))
    }

    @Test
    fun corruptOrPartialArchivesChangeNothing() = runTest {
        file("home/projects/Rain.autoflow/project.json", "NEW WORK".encodeToByteArray())
        val before = tree(home)
        val good = backupOf("Rain.autoflow", "Sun.autoflow")
        val cases = mapOf(
            "truncated mid-file" to good.copyOf(good.size / 2),
            "manifest cut off" to zipOf(listOf("Sun.autoflow/project.json" to validJson, "Sun.autoflow/source/a.txt" to ByteArray(10))),
            "file missing from a listed project" to zipOf(listOf("Sun.autoflow/project.json" to validJson), manifestFor("Sun.autoflow" to 2)),
            "project not listed" to zipOf(listOf("Sun.autoflow/project.json" to validJson, "Odd.autoflow/project.json" to validJson), manifestFor("Sun.autoflow" to 1)),
            "no project.json" to zipOf(listOf("Sun.autoflow/source/a.txt" to ByteArray(10)), manifestFor("Sun.autoflow" to 1)),
            "corrupt project.json" to zipOf(listOf("Sun.autoflow/project.json" to "{trunc".encodeToByteArray()), manifestFor("Sun.autoflow" to 1)),
            "not a project" to zipOf(listOf("Sun.autoflow/project.json" to "{\"a\":1}".encodeToByteArray()), manifestFor("Sun.autoflow" to 1)),
            "bad manifest" to zipOf(listOf("Sun.autoflow/project.json" to validJson), "[not json"),
            "duplicate entry" to zipOf(listOf("Sun.autoflow/project.json" to validJson, "sun.autoflow/PROJECT.json" to validJson), manifestFor("Sun.autoflow" to 2)),
            "random bytes" to ByteArray(5_000) { (it * 31).toByte() },
        )
        for ((why, zip) in cases) {
            val e = assertFailsWith<IOException>(why) { ProjectZip.restore(ByteArrayInputStream(zip), projects) }
            assertTrue(e.message != null, why)
            assertEquals(before, tree(home), "home unchanged after: $why")
            noStagingLeft()
        }
    }

    @Test
    fun restoreIsBoundedAndCancellable() = runTest {
        val zip = backupOf("Big.autoflow")
        projects.mkdirs()
        val before = tree(home)
        assertFailsWith<RestoreException> { ProjectZip.restore(ByteArrayInputStream(zip), projects, RestoreLimits(maxBytes = 10_000)) }
        assertFailsWith<RestoreException> { ProjectZip.restore(ByteArrayInputStream(zip), projects, RestoreLimits(maxEntries = 2)) }
        assertFailsWith<RestoreException> { ProjectZip.restore(ByteArrayInputStream(zip), projects, RestoreLimits(maxNameLength = 10)) }
        assertEquals(before, tree(home))
        noStagingLeft()

        val job = Job()
        var read = 0
        val slow = object : FilterInputStream(ByteArrayInputStream(zip)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (++read == 3) job.cancel()
                return super.read(b, off, minOf(len, 4096))
            }
        }
        assertFailsWith<CancellationException> { withContext(job) { ProjectZip.restore(slow, projects) } }
        assertEquals(before, tree(home), "a cancelled restore leaves nothing behind")
        noStagingLeft()
    }

    @Test
    fun restoredNamesAndSafeSegments() {
        assertEquals("Rain.autoflow", ProjectZip.restoredName("Rain.autoflow", 1))
        assertEquals("Rain (restored).autoflow", ProjectZip.restoredName("Rain.autoflow", 2))
        assertEquals("Rain (restored 3).autoflow", ProjectZip.restoredName("Rain.autoflow", 4))
        assertEquals("Rain.autoflow (2) (restored)", ProjectZip.restoredName("Rain.autoflow (2)", 2))
        assertEquals(listOf("Rain.autoflow", "source", "a b.txt"), ProjectZip.safeSegments("Rain.autoflow/source/a b.txt"))
        assertEquals(listOf("Rain.autoflow", "source"), ProjectZip.safeSegments("Rain.autoflow/source/", directory = true))
        assertEquals(listOf("မိုး.autoflow", "project.json"), ProjectZip.safeSegments("မိုး.autoflow/project.json"))
    }
}
