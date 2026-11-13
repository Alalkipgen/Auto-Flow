package io.github.alalkipgen.autoflow.ui.handoff

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Approvals
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Shot card thumbnails (phone test v0.1.14, fix 2): which reference image and video are attached to a shot. */
class HandoffThumbsTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val dir = "/p/Rain.autoflow"
    private val opened = mutableListOf<String>()
    private val share = object : FileShare {
        override fun open(path: String, mime: String): PlatformResult<Unit> = PlatformResult.Ok(Unit).also { opened += "$path $mime" }
        override fun share(path: String, mime: String, title: String): PlatformResult<Unit> = PlatformResult.Ok(Unit)
    }
    private val ffmpegCalls = mutableListOf<List<String>>()
    private val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1)
    private val runner = ToolRunner { cmd, _, _, _, _ ->
        ffmpegCalls += cmd
        fs.writeDurably(cmd.last(), jpg)
        ToolExec.Done(0, "", emptyList(), 1, false)
    }
    private class Locator(val found: Boolean) : ToolLocator {
        override fun findFfmpeg(): String? = if (found) "/bin/ffmpeg" else null
        override fun findVlc(): String? = null
        override fun isVlcDir(dir: String): Boolean = false
    }
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 4, 5)

    private fun model(ffmpeg: Boolean) = FlowHandoffModel(
        session, { PlatformResult.Ok(Unit) }, { PlatformResult.Ok(Unit) }, FakePicker(),
        fs = fs, fileShare = share,
        media = MediaTools(SettingsStore(fs, "/h/settings.json"), Locator(ffmpeg), if (ffmpeg) runner else null),
    )

    private fun put(bytes: ByteArray, kind: ArtifactKind, ext: String, shot: Int): ArtifactId =
        (session.artifacts()!!.put(bytes, kind, ext, shot) as CoreResult.Ok).value.id

    private var ref = ArtifactId("")
    private var v1 = ArtifactId("")
    private var v2 = ArtifactId("")

    /** Shot 1: approved ref, two videos, the first accepted. Shot 2: ref not approved, one .mov. Shot 3: nothing. */
    private fun open() {
        val p = (store.create(dir, "Rain", Mode.MUSIC_VIDEO, ProjectSettings()) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
        ref = put(png, ArtifactKind.IMAGE, "png", 1)
        v1 = put(byteArrayOf(1, 1, 1), ArtifactKind.VIDEO, "mp4", 1)
        v2 = put(byteArrayOf(2, 2, 2), ArtifactKind.VIDEO, "mp4", 1)
        val mov = put(byteArrayOf(3, 3, 3), ArtifactKind.VIDEO, "mov", 2)
        session.save(p.copy(shots = listOf(
            Shot(1, 0, 8_000, chosenRef = ref, approvals = Approvals(ref = true), videos = listOf(v1, v2), acceptedVideo = v1),
            Shot(2, 8_000, 8_000, chosenRef = ref, videos = listOf(mov)),
            Shot(3, 16_000, 8_000),
        )))
    }

    @Test
    fun rowsSayWhichImageAndVideoAreAttached() {
        open()
        val rows = model(ffmpeg = false).current().rows
        assertEquals(ref, rows[0].refImage)
        assertEquals(v1, rows[0].shownVideo, "the accepted video wins over a newer import")
        assertEquals("Reference image", refThumbLabel(rows[0]))
        assertEquals("Accepted video", videoThumbLabel(rows[0]))
        assertEquals("Reference image (not approved)", refThumbLabel(rows[1]))
        assertEquals("Imported video", videoThumbLabel(rows[1]))
        assertNull(rows[2].refImage)
        assertNull(rows[2].shownVideo)
        assertEquals("No reference image", refThumbLabel(rows[2]))
        assertEquals("No video yet", videoThumbLabel(rows[2]))
        assertEquals("Latest video (2 of 2)", videoThumbLabel(rows[0].copy(accepted = false)))
    }

    @Test
    fun thumbnailsComeFromFfmpegOnceAndAreCached() = runTest {
        open()
        val m = model(ffmpeg = true)
        assertContentEquals(jpg, m.refThumbnail(1))
        assertContentEquals(jpg, m.videoThumbnail(1))
        assertEquals(2, ffmpegCalls.size)
        assertTrue(ffmpegCalls[1].any { it.endsWith("/artifacts/" + (session.artifacts()!!.get(v1) as CoreResult.Ok).value.file) }, "the accepted video")
        assertTrue("1.000" in ffmpegCalls[1], "frame at 1 s")
        assertTrue(fs.exists("$dir/.cache/thumbs/${ref.value}.jpg"))
        assertTrue(fs.exists("$dir/.cache/thumbs/${v1.value}-1000.jpg"))
        m.refThumbnail(1)
        m.videoThumbnail(1)
        assertEquals(2, ffmpegCalls.size, "cached")
        assertNull(m.videoThumbnail(3))
        assertNull(m.refThumbnail(3))
    }

    @Test
    fun withoutFfmpegTheImageItselfIsShownAndVideosHaveNoPicture() = runTest {
        open()
        val m = model(ffmpeg = false)
        assertContentEquals(png, m.refThumbnail(1))
        assertNull(m.videoThumbnail(1))
    }

    @Test
    fun tappingOpensTheFileWithTheRightType() {
        open()
        val m = model(ffmpeg = false)
        m.playVideo(1)
        m.playVideo(2)
        m.openRefImage(1)
        assertEquals(3, opened.size)
        assertTrue(opened[0].startsWith("$dir/artifacts/") && opened[0].endsWith(".mp4 video/mp4"), opened[0])
        assertTrue(opened[1].endsWith(".mov video/quicktime"), opened[1])
        assertTrue(opened[2].endsWith(".png image/png"), opened[2])
        m.playVideo(3)
        assertEquals("No video imported for Shot 3", m.current().message)
    }
}
