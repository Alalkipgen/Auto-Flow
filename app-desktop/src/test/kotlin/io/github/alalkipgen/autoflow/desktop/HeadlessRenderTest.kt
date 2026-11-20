package io.github.alalkipgen.autoflow.desktop

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.JvmRenderPackages
import io.github.alalkipgen.autoflow.platform.PackageFile
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.pack.RenderPlan
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

/** F-7 (D-96): `AutoFlow --render` — arguments, exit codes, and a real Subtitles burn from a package (ffmpeg on PATH). */
class HeadlessRenderTest {
    private val tmp: File = Files.createTempDirectory("af-headless").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    @Test
    fun argumentsAreReadAndBadOnesAreRefused() {
        val ok = assertIs<HeadlessRender.Parsed.Ok>(HeadlessRender.parse(listOf("--render", "a.zip", "--out", "o", "--quality", "preview")))
        assertEquals(File("a.zip").absoluteFile, ok.args.input)
        assertEquals(File("o").absoluteFile, ok.args.out)
        assertEquals(Quality.PREVIEW, ok.args.quality)
        assertNull(ok.args.home)
        assertIs<HeadlessRender.Parsed.Help>(HeadlessRender.parse(listOf("--help")))
        assertIs<HeadlessRender.Parsed.Help>(HeadlessRender.parse(listOf("--render", "a.zip", "-h")))
        assertIs<HeadlessRender.Parsed.Bad>(HeadlessRender.parse(listOf("--render")))
        assertIs<HeadlessRender.Parsed.Bad>(HeadlessRender.parse(listOf("--render", "a.zip", "--quality", "4k")))
        assertIs<HeadlessRender.Parsed.Bad>(HeadlessRender.parse(listOf("--render", "a.zip", "b.zip")))
        assertIs<HeadlessRender.Parsed.Bad>(HeadlessRender.parse(listOf("--render", "a.zip", "--out")))
        assertIs<HeadlessRender.Parsed.Bad>(HeadlessRender.parse(listOf("--render", "a.zip", "--fast")))
        assertTrue(HeadlessRender.wanted(arrayOf("--render", "x")))
        assertTrue(HeadlessRender.wanted(arrayOf("--help")))
        assertFalse(HeadlessRender.wanted(emptyArray()))
        assertFalse(HeadlessRender.wanted(arrayOf("-psn_0_1")))
    }

    @Test
    fun helpExitsZeroAndAMissingPackageExitsTwo() {
        val out = ByteArrayOutputStream()
        assertEquals(HeadlessRender.OK, HeadlessRender.run(listOf("--help"), PrintStream(out), PrintStream(ByteArrayOutputStream())))
        assertTrue(out.toString().contains("Usage: AutoFlow --render"))
        val err = ByteArrayOutputStream()
        assertEquals(HeadlessRender.BAD_ARGS, HeadlessRender.run(listOf("--render", File(tmp, "missing.zip").path), PrintStream(ByteArrayOutputStream()), PrintStream(err)))
        assertTrue(err.toString().contains("Not found"), err.toString())
        File(tmp, "not-a-project").mkdirs()
        assertEquals(HeadlessRender.BAD_ARGS, HeadlessRender.run(listOf("--render", File(tmp, "not-a-project").path), PrintStream(ByteArrayOutputStream()), PrintStream(err)))
        assertNull(System.getProperty(AppDirs.HOME_OVERRIDE_PROPERTY))
    }

    @Test
    fun aSubtitlesPackageIsBurnedWithRealFfmpeg() {
        val ffmpeg = ffmpeg() ?: return
        val fs = JvmFs()
        val store = ProjectStore(fs)
        val dir = tmp.path.replace('\\', '/') + "/phone/Film.autoflow"
        val created = (store.create(dir, "Film", Mode.SUBTITLES) as CoreResult.Ok).value
        val movie = File(dir, "source/movie.mp4").apply { parentFile.mkdirs() }
        val made = ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=2", "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", movie.path).redirectErrorStream(true).start()
        assertTrue(made.waitFor(120, TimeUnit.SECONDS) && made.exitValue() == 0, made.inputStream.readBytes().decodeToString())
        val doc = SubtitleDoc(source = "srt", nextId = 3, cues = listOf(
            SubtitleCue("c1", 200, 900, "Hello", textMy = "မင်္ဂလာပါ"),
            SubtitleCue("c2", 1_000, 1_800, "Goodbye", textMy = "သွားတော့မယ်")))
        val st = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(), ArtifactKind.SUBTITLES, "json")
            as CoreResult.Ok).value.id
        store.save(dir, created.copy(subtitles = SubtitlesTool(video = SourceVideo("source/movie.mp4", movie.length(), 2_000, 320, 240, 25.0), current = st)))
        val plan = RenderPlan(mode = Mode.SUBTITLES, look = "BOXED", size = "LARGE", text = "BURMESE", docVersion = st.value)
        val zip = File(tmp, "Film-render-package.zip")
        val packed = runBlocking {
            JvmRenderPackages().pack(dir, "Film.autoflow", listOf(PackageFile(RenderPlan.FILE, CoreJson.encodeToString(RenderPlan.serializer(), plan).encodeToByteArray())), zip.path)
        }
        assertIs<PlatformResult.Ok<*>>(packed)

        val outDir = File(tmp, "server")
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = HeadlessRender.run(listOf("--render", zip.path, "--out", outDir.path), PrintStream(out, true), PrintStream(err, true))
        assertEquals(HeadlessRender.OK, code, "stdout:\n$out\nstderr:\n$err")
        val video = File(out.toString().lines().single { it.startsWith("OUTPUT ") }.removePrefix("OUTPUT "))
        assertEquals(outDir.canonicalFile, video.parentFile.canonicalFile)
        assertTrue(video.name.endsWith(".mp4") && video.length() > 1_000, video.path)
        assertTrue("-boxed-large-" in video.name, "render.json's look and size were used: ${video.name}")
        val check = ProcessBuilder(ffmpeg, "-hide_banner", "-i", video.path, "-f", "null", "-").redirectErrorStream(true).start()
        val probe = check.inputStream.readBytes().decodeToString()
        assertTrue(check.waitFor(120, TimeUnit.SECONDS) && check.exitValue() == 0, probe)
        assertTrue("Duration: 00:00:02" in probe || "Duration: 00:00:01.9" in probe, probe)
        assertTrue(File(outDir, "Film.autoflow/project.json").isFile, "the package was unpacked next to the video")
        assertTrue(File(outDir, HeadlessRender.LOG).readText().contains("OUTPUT "))
        assertTrue(File(outDir, HeadlessRender.HOME).isDirectory, "it ran with its own app folder")
        assertNull(System.getProperty(AppDirs.HOME_OVERRIDE_PROPERTY))
    }

    /** ffmpeg on PATH (as the app finds it); CI sets AUTOFLOW_EXPECT_FFMPEG=1 so the render test never skips there. */
    private fun ffmpeg(): String? {
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) "ffmpeg.exe" else "ffmpeg"
        val found = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).map { File(it.trim('"'), exe) }.firstOrNull { it.canExecute() }?.path
        if (found == null && System.getenv("AUTOFLOW_EXPECT_FFMPEG") == "1") fail("AUTOFLOW_EXPECT_FFMPEG=1 but no ffmpeg on PATH")
        return found
    }
}
