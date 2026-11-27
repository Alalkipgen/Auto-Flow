package io.github.alalkipgen.autoflow.platform

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** F-7 (D-96): a render package holds only what a render reads, and unpacks like a backup. */
class RenderPackageTest {
    private val tmp: File = Files.createTempDirectory("af-pack").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun file(path: String, text: String) = File(tmp, path).apply { parentFile.mkdirs(); writeText(text) }
    private fun file(path: String, bytes: ByteArray) = File(tmp, path).apply { parentFile.mkdirs(); writeBytes(bytes) }

    /** project.json → st (current version) → au takes; an old st version, an unused take and every cache / export. */
    private fun project(): File {
        file("Film.autoflow/project.json", """{"id":"p1","name":"Film","mode":"SUBTITLES","subtitles":{"current":"st_111111111111"},"music":"source/song.mp3"}""")
        file("Film.autoflow/source/movie.mp4", ByteArray(2_000) { 3 })
        file("Film.autoflow/source/song.mp3", ByteArray(500) { 4 })
        file("Film.autoflow/artifacts/st_111111111111.json", """{"cues":[{"take":"au_aaaaaaaaaaaa"}],"derivedFrom":null}""")
        file("Film.autoflow/artifacts/st_111111111111.meta.json", """{"id":"st_111111111111","derivedFrom":"st_222222222222"}""")
        file("Film.autoflow/artifacts/au_aaaaaaaaaaaa.wav", ByteArray(800) { 5 })
        file("Film.autoflow/artifacts/au_aaaaaaaaaaaa.meta.json", """{"id":"au_aaaaaaaaaaaa"}""")
        file("Film.autoflow/artifacts/st_222222222222.json", """{"cues":[{"take":"au_bbbbbbbbbbbb"}]}""")
        file("Film.autoflow/artifacts/au_bbbbbbbbbbbb.wav", ByteArray(900) { 6 })
        file("Film.autoflow/exports/film.mp4", ByteArray(100))
        file("Film.autoflow/inbox/shared.png", ByteArray(10))
        file("Film.autoflow/packages/old.zip", ByteArray(10))
        file("Film.autoflow/.cache/fonts/noto.ttf", ByteArray(10))
        file("Film.autoflow/source/.cache/thumb.png", ByteArray(10))
        file("Film.autoflow/project.json.bak", "{}")
        file("Film.autoflow/project.json.tmp", "{}")
        file("Film.autoflow/fonts/old.ttf", ByteArray(10) { 7 })
        return File(tmp, "Film.autoflow")
    }

    @Test
    fun planKeepsWhatTheRenderReadsAndDropsCachesExportsAndOldVersions() {
        val plan = RenderPackage.plan(project(), "Film.autoflow")
        assertEquals(RenderPackage.KIND, plan.kind)
        assertEquals(
            listOf(
                "Film.autoflow/artifacts/au_aaaaaaaaaaaa.meta.json",
                "Film.autoflow/artifacts/au_aaaaaaaaaaaa.wav",
                "Film.autoflow/artifacts/st_111111111111.json",
                "Film.autoflow/artifacts/st_111111111111.meta.json",
                "Film.autoflow/fonts/old.ttf",
                "Film.autoflow/project.json",
                "Film.autoflow/source/movie.mp4",
                "Film.autoflow/source/song.mp3",
            ),
            plan.entries.map { it.path },
            "the old version st_2… is named only in a .meta.json, so it and its take stay out",
        )
        assertEquals(listOf("Film.autoflow"), plan.projects.map { it.name })
    }

    @Test
    fun extrasReplaceAProjectFileAndAPackagedFontReplacesTheOldFonts() {
        val font = ByteArray(64) { 9 }
        val plan = RenderPackage.plan(project(), "Film.autoflow", listOf(
            PackageFile("render.json", """{"format":"autoflow-render"}""".encodeToByteArray()),
            PackageFile("fonts/Noto.ttf", font),
            PackageFile("source/song.mp3", ByteArray(3) { 1 }),
        ))
        val paths = plan.entries.map { it.path }
        assertTrue("Film.autoflow/render.json" in paths)
        assertTrue("Film.autoflow/fonts/Noto.ttf" in paths)
        assertFalse("Film.autoflow/fonts/old.ttf" in paths, "a packaged font replaces the project's fonts/")
        assertEquals(1, paths.count { it == "Film.autoflow/source/song.mp3" })
        assertEquals(3L, plan.entries.single { it.path == "Film.autoflow/source/song.mp3" }.size)
    }

    @Test
    fun packThenUnpackGivesTheProjectBackNextToAnExistingFolder() = runTest {
        val dir = project()
        val zip = File(tmp, "out/Film-render-package.zip")
        val packed = JvmRenderPackages().pack(dir.path, "Film.autoflow", listOf(PackageFile("render.json", "{}".encodeToByteArray())), zip.path)
        assertIs<PlatformResult.Ok<PackedProject>>(packed)
        assertEquals(9, packed.value.files)
        assertTrue(zip.isFile)
        assertFalse(File(zip.path + ".part").exists())
        ZipFile(zip).use { z -> assertTrue(z.getEntry(ProjectZip.MANIFEST)!!.let { e -> z.getInputStream(e).readBytes().decodeToString() }.contains("render-package")) }

        val dest = File(tmp, "other").apply { mkdirs() }
        File(dest, "Film.autoflow").mkdirs()
        val restored = JvmRenderPackages().unpack(zip.path, dest.path)
        assertIs<PlatformResult.Ok<List<RestoredProject>>>(restored)
        val folder = restored.value.single().folder
        assertTrue(restored.value.single().renamed, "an existing folder is never replaced")
        assertContentEquals(File(dir, "source/movie.mp4").readBytes(), File(dest, "$folder/source/movie.mp4").readBytes())
        assertTrue(File(dest, "$folder/render.json").isFile)
        assertFalse(File(dest, "$folder/exports").exists())
    }

    @Test
    fun aFolderWithoutAProjectIsNotPacked() = runTest {
        File(tmp, "empty").mkdirs()
        assertIs<PlatformResult.Failed>(JvmRenderPackages().pack(File(tmp, "empty").path, "x", emptyList(), File(tmp, "x.zip").path))
    }
}
