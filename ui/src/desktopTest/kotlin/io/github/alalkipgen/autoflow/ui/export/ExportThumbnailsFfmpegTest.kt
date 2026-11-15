package io.github.alalkipgen.autoflow.ui.export

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.PublishKit
import io.github.alalkipgen.autoflow.core.recap.RecapBlock
import io.github.alalkipgen.autoflow.core.recap.RecapClip
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.RecapStory
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.ProcessRunner
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 5B-9 / 5B-10 with a real ffmpeg on a real movie: the Export screen's Exact-frame thumbnails are readable PNGs of
 * the preview size at their times, and the Publish Kit thumbnail `exports/export.thumb.jpg` is a readable JPEG of the
 * export size whose lower third (the Burmese title layer) differs from the same frame without it while the rest of
 * the picture stays the same. `AUTOFLOW_EXPECT_FFMPEG=1` (CI) turns a missing ffmpeg into a failure.
 */
class ExportThumbnailsFfmpegTest {
    private fun works(bin: String) = runCatching {
        val p = ProcessBuilder(bin, "-hide_banner", "-version").redirectErrorStream(true).start()
        p.inputStream.readAllBytes(); p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    private val ffmpeg: String? by lazy { listOfNotNull(System.getenv("AUTOFLOW_FFMPEG"), "ffmpeg").firstOrNull(::works) }
    private val ffprobe: String? by lazy {
        listOfNotNull(System.getenv("AUTOFLOW_FFPROBE"), ffmpeg?.let { File(File(it).parentFile ?: File("."), "ffprobe").path }, "ffprobe")
            .firstOrNull(::works)
    }

    /** The desktop runner (app-desktop DesktopToolRunner) over [ProcessRunner]. */
    private val tools = ToolRunner { cmd, wd, timeout, keep, onLine ->
        when (val r = ProcessRunner.run(cmd, File(wd), timeout, keep, onLine)) {
            is ProcessRunner.Exec.Done -> ToolExec.Done(r.exitCode, r.output, r.tail, r.wallMs, r.timedOut)
            is ProcessRunner.Exec.CannotStart -> ToolExec.CannotStart(r.message)
        }
    }

    private fun sh(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "timed out: ${cmd.joinToString(" ")}")
        assertEquals(0, p.exitValue(), "${cmd.joinToString(" ")}\n$out")
    }

    private fun decode(bytes: ByteArray): BufferedImage = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)), "not a readable image")

    /** Mean absolute RGB difference of [a] and [b] over the box [x0, y0, x1, y1] (0–1 of the picture). */
    private fun diff(a: BufferedImage, b: BufferedImage, x0: Double, y0: Double, x1: Double, y1: Double): Double {
        var sum = 0L; var n = 0L
        for (y in (y0 * a.height).toInt() until (y1 * a.height).toInt() step 2) for (x in (x0 * a.width).toInt() until (x1 * a.width).toInt() step 2) {
            val p = a.getRGB(x, y); val q = b.getRGB(x, y)
            sum += abs((p shr 16 and 255) - (q shr 16 and 255)) + abs((p shr 8 and 255) - (q shr 8 and 255)) + abs((p and 255) - (q and 255))
            n += 3
        }
        return sum.toDouble() / n
    }

    @Test
    fun realExactFramesAndPublishThumbnailAreTheExportsOwnPictures() = runBlocking {
        val ff = ffmpeg; val fp = ffprobe
        if (ff == null || fp == null) {
            if (System.getenv("AUTOFLOW_EXPECT_FFMPEG") == "1") fail("AUTOFLOW_EXPECT_FFMPEG=1 but no ffmpeg / ffprobe")
            println("SKIP ExportThumbnailsFfmpegTest: no ffmpeg / ffprobe binary"); return@runBlocking
        }
        val root = Files.createTempDirectory("af-thumbs").toFile()
        try {
            val fs = JvmFs()
            val store = ProjectStore(fs)
            val current = CurrentProject()
            val session = ProjectSession(fs, store, ProjectIndex(fs, File(root, "projects.json").path), current)
            val settings = SettingsStore(fs, File(root, "settings.json").path)
            val font = File(root, "noto.ttf").apply {
                writeBytes(Base64.getMimeDecoder().decode(ExportThumbnailsFfmpegTest::class.java.getResourceAsStream("/fonts/NotoSansMyanmar-subset.ttf.b64")!!.readBytes()))
            }
            settings.update { it.copy(fontPaths = listOf(font.path)) }
            val dir = File(root, "R.autoflow").path
            val p = (store.create(dir, "R", Mode.MOVIE_RECAP) as CoreResult.Ok).value
            File(dir, "source").mkdirs()
            val movie = File(dir, "source/movie.mp4")
            sh(ff, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25:duration=16",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=16", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", movie.path)
            val story = RecapStory(
                source = RecapStory.SOURCE_MANUAL,
                blocks = listOf(
                    RecapBlock("b_a", listOf(RecapClip(0, 4_000)), "ကောင်လေး"),
                    RecapBlock("b_b", listOf(RecapClip(6_000, 9_000)), "သူငယ်ချင်း"),
                    RecapBlock("b_c", listOf(RecapClip(11_000, 15_000)), "အဆုံး"),
                ),
            )
            val sid = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(RecapStory.serializer(), story).encodeToByteArray(), ArtifactKind.RECAP_SCRIPT, "json", provider = "manual") as CoreResult.Ok).value.id
            val recap = RecapV2(
                rightsConfirmed = true,
                movie = RecapMovie("source/movie.mp4", movie.length(), 16_000, 1280, 720),
                scenes = listOf(RecapScene(0, 4_000, picked = true)),
                story = sid, storyApproved = true,
            )
            current.set(OpenProject(dir, (store.save(dir, p.copy(recap = recap)) as CoreResult.Ok).value))
            val locator = object : ToolLocator {
                override fun findFfmpeg(): String = ff
                override fun findFfprobe(): String = fp
                override fun findVlc(): String? = null
                override fun isVlcDir(dir: String): Boolean = false
            }
            val frames = RecapFrames(session, settings, fs, MediaTools(settings, locator, tools))
            val m = ExportModel(session, settings, fs, null, null, null, CoroutineScope(Dispatchers.Default), frames = frames)
            val open = current.state.value!!

            // 5B-9: three stills, each a readable PNG of the preview size, at the clip middles; the moving test
            // pattern makes each time a different picture.
            m.makeThumbnails(max = 3)
            withTimeout(180_000) { while (m.state.value.thumbs?.working != false) delay(50) }
            val thumbs = assertNotNull(m.state.value.thumbs)
            assertNull(thumbs.failed, thumbs.failed)
            assertEquals(listOf(2_000L, 5_500L, 9_000L), thumbs.frames.map { it.atMs })
            val preview = assertNotNull(frames.job(open, Quality.PREVIEW, null, "exports/p", "p")).graph.output
            val pictures = thumbs.frames.map { decode(it.bytes) }
            pictures.forEach { assertEquals(preview.width to preview.height, it.width to it.height) }
            assertTrue(diff(pictures[0], pictures[1], 0.0, 0.0, 1.0, 1.0) > 3.0, "different times, different pictures")
            assertTrue(thumbs.frames.all { File(it.file).isFile && it.file.startsWith(dir) })

            // 5B-10: the thumbnail at the second still, export size, JPEG, with the title layer in the lower third.
            m.setPublishKit(PublishKit(title = "ကောင်လေး"))
            m.makePublishThumbnail(thumbs.frames[1].atMs)
            withTimeout(180_000) { while (m.state.value.publishThumb?.working != false) delay(50) }
            val thumb = assertNotNull(m.state.value.publishThumb)
            assertNull(thumb.failed, thumb.failed)
            val file = File(dir, "exports/export.thumb.jpg")
            val bytes = file.readBytes()
            assertEquals(0xFF.toByte() to 0xD8.toByte(), bytes[0] to bytes[1], "JPEG")
            val withTitle = decode(bytes)
            val export = assertNotNull(frames.job(open, Quality.EXPORT, null, "exports/e", "e")).graph.output
            assertEquals(export.width to export.height, withTitle.width to withTitle.height)
            val plain = assertIs<RecapFrames.Result.Ok>(frames.frame(thumbs.frames[1].atMs, Quality.EXPORT, ".cache/frames/plain.jpg", open = open))
            val without = decode(plain.frame.bytes)
            val titleBand = diff(withTitle, without, 0.06, 0.64, 0.94, 0.92)
            val top = diff(withTitle, without, 0.0, 0.0, 1.0, 0.45)
            println("ExportThumbnailsFfmpegTest: title band diff=$titleBand, top diff=$top, export ${export.width}x${export.height}")
            assertTrue(titleBand > 4.0, "the title is drawn in the lower third (diff $titleBand)")
            assertTrue(top < 2.0, "the rest of the frame is the same picture (diff $top)")
            assertTrue(File(dir, "exports/export.thumb.json").isFile)
            assertEquals(emptyList(), File(dir, "exports").list()!!.filter { it.endsWith(".tmp") || it.endsWith(".ass") || ".part." in it })
        } finally {
            root.deleteRecursively()
        }
    }
}
