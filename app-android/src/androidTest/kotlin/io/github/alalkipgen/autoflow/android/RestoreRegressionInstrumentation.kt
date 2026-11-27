package io.github.alalkipgen.autoflow.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import io.github.alalkipgen.autoflow.platform.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*

/** Platform-only executable runner: no new testing dependency, real Android linkage + production adapter. */
class RestoreRegressionInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val root = Files.createTempDirectory(targetContext.cacheDir.toPath(), "restore-regression-").toFile()
        try {
            runBlocking { exercise(root) }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: production Android restore, fixture, UTF-8, rejection/collision/cancellation cleanup") })
        } catch (failure: Throwable) { // Test harness reports linkage Errors too; production does not swallow them.
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${failure.stackTraceToString()}") })
        } finally { root.deleteRecursively() } // Only this test's fresh private sandbox, never owner projects.
    }

    private suspend fun exercise(root: File) {
        val fixture = File(root, "fixture.zip")
        context.assets.open("AutoFlow-5A8-synthetic-backup.zip").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val destination = File(root, "projects").apply { mkdirs() }
        val adapter = AndroidProjectBackup(targetContext)
        val first = adapter.restore(fixture.path, destination.path)
        check(first is PlatformResult.Ok && first.value.size == 3) { "Real fixture restore failed: $first" }
        val before = snapshot(destination)
        val again = adapter.restore(fixture.path, destination.path)
        check(again is PlatformResult.Ok && again.value.all { it.renamed })
        check(before.all { (path, bytes) -> File(destination, path).readBytes().contentEquals(bytes) })

        val name = "မိုးရွာတဲ့ည.autoflow"
        val source = File(root, name).apply { mkdirs() }
        val json = """{"id":"မြန်မာ-id","name":"မိုးရွာတဲ့ည","mode":"MOVIE_RECAP"}""".toByteArray(Charsets.UTF_8)
        File(source, "project.json").writeBytes(json)
        File(source, "စာသား.txt").writeText("မြန်မာစာ / English prompt", Charsets.UTF_8)
        val zipped = ByteArrayOutputStream()
        ProjectZip.write(ProjectZip.plan(listOf(BackupProject(source.path, name))), zipped, "t")
        val restored = ProjectZip.restore(ByteArrayInputStream(zipped.toByteArray()), destination).single()
        check(File(destination, "${restored.folder}/project.json").readBytes().contentEquals(json))
        check(snapshot(source).all { (path, bytes) -> File(destination, "$name/$path").readBytes().contentEquals(bytes) })
        val preserved = snapshot(destination)
        val manifest = """{"format":"autoflow-backup","projects":[{"folder":"Bad.autoflow","files":1}]}"""
        val malformed = """{"id":"x","name":"""".toByteArray(Charsets.UTF_8) + byteArrayOf(0xc3.toByte(), 0x28) +
            """","mode":"MOVIE_RECAP"}""".toByteArray(Charsets.UTF_8)
        for ((entry, bytes, listing) in listOf(
            Triple("Bad.autoflow/project.json", malformed, manifest),
            Triple("Bad.autoflow/project.json", "{broken".toByteArray(), manifest),
            Triple("Bad.autoflow/missing.txt", json, manifest),
            Triple("Bad.autoflow/project.json", json, "not JSON"),
            Triple("Bad.autoflow/project.json", json, manifest.replace(":1", ":2")),
        )) {
            val zip = File(root, "rejected.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                for ((path, content) in listOf(entry to bytes, ProjectZip.MANIFEST to listing.toByteArray(Charsets.UTF_8))) {
                    out.putNextEntry(ZipEntry(path)); out.write(content); out.closeEntry()
                }
            }
            check(adapter.restore(zip.path, destination.path) is PlatformResult.Failed)
            checkSame(preserved, snapshot(destination))
            check(destination.list()!!.none { it.startsWith(".restore-") })
        }
        val job = Job()
        val cancelled = object : FilterInputStream(ByteArrayInputStream(zipped.toByteArray())) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                job.cancel(); return super.read(b, off, len)
            }
        }
        try {
            withContext(job) { ProjectZip.restore(cancelled, destination) }
            error("Cancellation must propagate")
        } catch (_: CancellationException) { /* expected, never success */ }
        checkSame(preserved, snapshot(destination))
        check(destination.list()!!.none { it.startsWith(".restore-") })
    }

    private fun snapshot(root: File): Map<String, ByteArray> = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }
    private fun checkSame(a: Map<String, ByteArray>, b: Map<String, ByteArray>) {
        check(a.keys == b.keys && a.all { (path, bytes) -> bytes.contentEquals(b[path]) })
    }
}
