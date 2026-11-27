package io.github.alalkipgen.autoflow.ui.voice

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.audio.VideoSound
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.ProcessRunner
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.media.InProcessRenderQueue
import io.github.alalkipgen.autoflow.ui.media.MediaTasks
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlesModel
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.test.*

/** V-3b: pick a video → project with it in source/ → Save the sound only, Replace and Add as media tasks (real ffmpeg), picture kept. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class VideoSoundTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    private fun works(bin: String) = runCatching {
        val p = ProcessBuilder(bin, "-hide_banner", "-version").redirectErrorStream(true).start()
        p.inputStream.readAllBytes(); p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    private fun out(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readText().also { assertEquals(0, p.waitFor(), it) }
    }

    @Test fun pickVideoThenSoundOnlyReplaceAndAddWithRealFfmpeg() = runBlocking {
        val ff = listOfNotNull(System.getenv("AUTOFLOW_FFMPEG"), "ffmpeg").firstOrNull(::works)
        val fp = listOfNotNull(System.getenv("AUTOFLOW_FFPROBE"), "ffprobe").firstOrNull(::works)
        if (ff == null || fp == null) {
            if (System.getenv("AUTOFLOW_EXPECT_FFMPEG") == "1") fail("AUTOFLOW_EXPECT_FFMPEG=1 but no ffmpeg / ffprobe")
            throw org.junit.AssumptionViolatedException("VideoSoundTest not executed: no ffmpeg / ffprobe")
        }
        val tmp = Files.createTempDirectory("video-sound").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val video = File(tmp, "My clip.mp4"); val voice = File(tmp, "voice.wav")
            out(ff, "-hide_banner", "-y", "-f", "lavfi", "-i", "testsrc2=s=320x180:r=25:d=4", "-f", "lavfi", "-i", "sine=f=300:d=4",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", video.path)
            out(ff, "-hide_banner", "-y", "-f", "lavfi", "-i", "sine=f=800:d=2", "-c:a", "pcm_s16le", voice.path)
            fun picture(f: File) = out(ff, "-hide_banner", "-nostdin", "-i", f.path, "-map", "0:v:0", "-c", "copy", "-f", "md5", "-").lines().first { it.startsWith("MD5=") }
            fun ms(f: File) = (out(fp, "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", f.path).trim().toDouble() * 1000).toLong()
            val fs = JvmFs(); val store = ProjectStore(fs); val current = CurrentProject()
            val index = ProjectIndex(fs, File(tmp, "projects.json").path)
            val session = ProjectSession(fs, store, index, current)
            val settings = SettingsStore(fs, File(tmp, "s.json").path)
            val picker = FakePicker(folder = File(tmp, "projects").path, files = listOf(video.path))
            val dashboard = DashboardModel(fs, store, index, settings, picker, current)
            val locator = object : ToolLocator {
                override fun findFfmpeg() = ff
                override fun findFfprobe() = fp
                override fun findVlc(): String? = null
                override fun isVlcDir(dir: String) = false
            }
            val tools = ToolRunner { cmd, wd, timeout, keep, onLine ->
                when (val r = ProcessRunner.run(cmd, File(wd), timeout, keep, onLine)) {
                    is ProcessRunner.Exec.Done -> ToolExec.Done(r.exitCode, r.output, r.tail, r.wallMs, r.timedOut)
                    is ProcessRunner.Exec.CannotStart -> ToolExec.CannotStart(r.message)
                }
            }
            val media = MediaTools(settings, locator, tools)
            val tasks = MediaTasks(InProcessRenderQueue(fs, media, scope), scope)
            val export = VoiceExportModel(session, fs, tasks, ToolsConfig(), null, scope)
            val model = VideoSoundModel(session, fs, dashboard, picker, tasks, export, ToolsConfig(), scope, SubtitlesModel.probeWith(media))
            suspend fun saved(): File {
                val o = assertNotNull(export.state.value.file)
                withTimeout(60_000) { while (tasks.forOutput(o)?.state?.finished != true) delay(20) }
                assertIs<RenderState.Done>(tasks.forOutput(o)!!.state, tasks.forOutput(o)!!.state.toString())
                return File(assertNotNull(export.ready())).also { assertEquals("exports", it.parentFile.name) }
            }

            model.start()!!.join()
            val open = assertNotNull(session.open)
            assertEquals(Mode.VOICE_AUDIO, open.project.mode)
            assertEquals(VoiceTool.TASK_VIDEO_SOUND, open.project.voice?.task)
            val source = assertNotNull(open.project.voice?.source, model.state.value.toString())
            assertEquals(320, source.width); assertTrue(source.hasAudio)
            assertTrue(abs(source.durationMs - 4_000) <= 100, source.toString())

            model.save("m4a")!!.join()
            val sound = saved()
            assertTrue(sound.name.endsWith("-sound.m4a"), sound.name)
            assertEquals("audio", out(fp, "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", sound.path).trim())
            assertEquals(VideoSound.SOUND_ONLY, tasks.forOutput(export.state.value.file!!)!!.kind)

            model.setAction(REPLACE)
            model.save()?.join()
            assertEquals(UiText.VA_PICK_SOUND_FILE, model.state.value.notice?.key, "nothing to make before a sound is picked")
            picker.files = listOf(voice.path)
            model.pickSound()!!.join()
            assertEquals("source/song.wav", model.state.value.sound, model.state.value.toString())
            model.save()!!.join()
            val replaced = saved()
            assertTrue(replaced.name.contains("-replaced-") && replaced.name.endsWith(".mp4"), replaced.name)
            assertEquals(picture(video), picture(replaced), "the picture is not re-encoded")
            assertTrue(abs(ms(replaced) - 4_000) <= 150, "the video's length is kept")

            model.setAction(ADD); model.setStart(1_000); model.setVolume(0.5)
            model.save()!!.join()
            val added = saved()
            assertTrue(added.name.contains("-added-") && added.name.contains("-1000-50l"), added.name)
            assertEquals(picture(video), picture(added))
            assertEquals(VideoSound.ADD, tasks.forOutput(export.state.value.file!!)!!.kind)
            assertTrue(File(open.dir, source.file).exists(), "the source is kept")
        } finally {
            scope.cancel()
            tmp.deleteRecursively()
        }
    }

    @Test fun fileWithoutPictureCreatesNothing() = runBlocking {
        val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
        val index = ProjectIndex(fs, "/app/projects.json")
        val session = ProjectSession(fs, store, index, current)
        val picker = FakePicker(folder = "/p", files = listOf("/in/talk.mp4"))
        val dashboard = DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), picker, current)
        val tasks = MediaTasks(null, MainScope())
        val model = VideoSoundModel(session, fs, dashboard, picker, tasks, VoiceExportModel(session, fs, tasks, ToolsConfig(), null, MainScope()),
            ToolsConfig(), MainScope(), { io.github.alalkipgen.autoflow.media.probe.MediaInfo(5_000, null, null, null, null, "aac") })
        model.start()!!.join()
        assertNull(session.open)
        assertEquals(UiText.VA_NOT_VIDEO, model.state.value.notice?.key)
    }

    @Test fun videoSoundScreenShowsActionsAndButtons() = runDesktopComposeUiTest(width = 412, height = 900) {
        val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
        val index = ProjectIndex(fs, "/app/projects.json")
        val session = ProjectSession(fs, store, index, current)
        val dashboard = DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), FakePicker(), current)
        val dir = "/p/Clip.autoflow"
        val created = (store.create(dir, "Clip", Mode.VOICE_AUDIO) as CoreResult.Ok).value
        current.set(OpenProject(dir, (store.save(dir, created.copy(voice = VoiceTool(task = VoiceTool.TASK_VIDEO_SOUND,
            source = SourceVideo("source/movie.mp4", 10, 65_000, 1280, 720, 25.0, hasAudio = false)))) as CoreResult.Ok).value))
        val tasks = MediaTasks(null, MainScope())
        val export = VoiceExportModel(session, fs, tasks, ToolsConfig(), null, MainScope())
        val video = VideoSoundModel(session, fs, dashboard, FakePicker(), tasks, export, ToolsConfig(), MainScope(), { null })
        val model = VoiceAudioModel(session, dashboard, ToolsConfig(), MainScope())
        setContent { VoiceAudioScreen(model, session, onBack = {}, export = export, video = video) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("video-sound-file").fetchSemanticsNodes().isNotEmpty() }
        onNode(hasText("movie.mp4", substring = true)).assertExists()
        onNodeWithTag("video-sound-sound").assertIsNotEnabled() // no sound of its own
        onNodeWithTag("video-sound-add").performMouseInput { click() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("video-sound-start").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("video-sound-volume-100").assertExists()
        assertTrue(onAllNodesWithTag("video-sound-lower").fetchSemanticsNodes().isEmpty(), "no original to lower")
        onNodeWithTag("video-sound-make").assertIsNotEnabled()
    }
}
