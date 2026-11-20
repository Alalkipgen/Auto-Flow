package io.github.alalkipgen.autoflow.ui.voice

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.CleanChoice
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.audio.CleanAudio
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

/** V-2b: pick → project with the file in source/ → Before / After → Save wav as a media task (real ffmpeg), one analysis. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class VoiceCleanTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    private fun works(bin: String) = runCatching {
        val p = ProcessBuilder(bin, "-hide_banner", "-version").redirectErrorStream(true).start()
        p.inputStream.readAllBytes(); p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    private fun ms(ffprobe: String, f: File): Long {
        val p = ProcessBuilder(ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", f.path).redirectErrorStream(true).start()
        return (p.inputStream.bufferedReader().readText().trim().toDouble() * 1000).toLong().also { p.waitFor() }
    }

    @Test fun pickPreviewAndSaveWithRealFfmpeg() = runBlocking {
        val ff = listOfNotNull(System.getenv("AUTOFLOW_FFMPEG"), "ffmpeg").firstOrNull(::works)
        val fp = listOfNotNull(System.getenv("AUTOFLOW_FFPROBE"), "ffprobe").firstOrNull(::works)
        if (ff == null || fp == null) {
            if (System.getenv("AUTOFLOW_EXPECT_FFMPEG") == "1") fail("AUTOFLOW_EXPECT_FFMPEG=1 but no ffmpeg / ffprobe")
            throw org.junit.AssumptionViolatedException("VoiceCleanTest not executed: no ffmpeg / ffprobe")
        }
        val tmp = Files.createTempDirectory("voice-clean").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val input = File(tmp, "My talk.wav")
            val gen = ProcessBuilder(ff, "-hide_banner", "-y", "-f", "lavfi", "-i", "aevalsrc='if(between(t,3,5),0,0.05*sin(2*PI*440*t))':s=48000:d=8",
                "-c:a", "pcm_s16le", input.path).redirectErrorStream(true).start()
            gen.inputStream.readAllBytes(); assertEquals(0, gen.waitFor())
            val fs = JvmFs(); val store = ProjectStore(fs); val current = CurrentProject()
            val index = ProjectIndex(fs, File(tmp, "projects.json").path)
            val session = ProjectSession(fs, store, index, current)
            val settings = SettingsStore(fs, File(tmp, "s.json").path)
            val picker = FakePicker(folder = File(tmp, "projects").path, files = listOf(input.path))
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
            val config = ToolsConfig(clean = ToolsConfig.Clean(previewMs = 6_000))
            val export = VoiceExportModel(session, fs, tasks, config, null, scope)
            var analyses = 0
            val run = VoiceCleanModel.ffmpegWith(media)
            val clean = VoiceCleanModel(session, fs, dashboard, picker, tasks, export, config, scope, SubtitlesModel.probeWith(media),
                { args, dir, t -> if (args.takeLast(3) == listOf("-f", "null", "-")) analyses++; run(args, dir, t) })

            clean.start()!!.join()
            val open = assertNotNull(session.open)
            assertEquals(Mode.VOICE_AUDIO, open.project.mode)
            val source = assertNotNull(open.project.voice?.source, clean.state.value.toString())
            assertEquals(VoiceTool.TASK_CLEAN, open.project.voice?.task)
            assertEquals("source/song.wav", source.file); assertEquals(0, source.width)
            assertTrue(abs(source.durationMs - 8_000) <= 30, source.toString())

            clean.preview()!!.join()
            val s = clean.state.value
            assertNull(s.notice, s.toString())
            assertTrue(abs(ms(fp, File(s.before!!)) - 6_000) <= 30)
            assertTrue(abs(ms(fp, File(s.after!!)) - 4_350) <= 60, "the first 6 s with the 2 s pause shortened")

            clean.save("wav")!!.join()
            val out = assertNotNull(export.state.value.file)
            withTimeout(60_000) { while (tasks.forOutput(out)?.state?.finished != true) delay(20) }
            assertIs<RenderState.Done>(tasks.forOutput(out)!!.state)
            val saved = File(assertNotNull(export.ready()))
            assertEquals("exports", saved.parentFile.name)
            assertEquals(CleanAudio.fileName("source/song.wav", CleanChoice(), source.sizeBytes, "wav"), saved.name)
            assertTrue(abs(ms(fp, saved) - 6_350) <= 60, "8 s with a 2 s pause → about 6.35 s")
            assertEquals(1, analyses, "one analysis for the preview and the save")
            assertEquals(CleanAudio.CLEAN_AUDIO, tasks.forOutput(out)!!.kind)

            clean.setChoice(CleanChoice(pauses = false))
            assertEquals(CleanChoice(pauses = false), session.open?.project?.voice?.clean)
            assertNull(clean.state.value.after)
            clean.setChoice(CleanChoice(false, false, false))
            assertEquals(CleanChoice(pauses = false), session.open?.project?.voice?.clean, "the last choice stays on")
        } finally {
            scope.cancel()
            tmp.deleteRecursively()
        }
    }

    @Test fun fileWithoutSoundCreatesNothing() = runBlocking {
        val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
        val index = ProjectIndex(fs, "/app/projects.json")
        val session = ProjectSession(fs, store, index, current)
        val picker = FakePicker(folder = "/p", files = listOf("/in/silent.mp4"))
        val dashboard = DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), picker, current)
        val tasks = MediaTasks(null, MainScope())
        val clean = VoiceCleanModel(session, fs, dashboard, picker, tasks, VoiceExportModel(session, fs, tasks, ToolsConfig(), null, MainScope()),
            ToolsConfig(), MainScope(), { io.github.alalkipgen.autoflow.media.probe.MediaInfo(5_000, 640, 360, 25.0, "h264", null) }, { _, _, _ -> null })
        clean.start()!!.join()
        assertNull(session.open)
        assertEquals(UiText.VA_NO_SOUND, clean.state.value.notice?.key)
    }

    @Test fun cleanScreenShowsChoicesAndSaveButtons() = runDesktopComposeUiTest(width = 412, height = 900) {
        val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val current = CurrentProject()
        val index = ProjectIndex(fs, "/app/projects.json")
        val session = ProjectSession(fs, store, index, current)
        val dashboard = DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), FakePicker(), current)
        val dir = "/p/Talk.autoflow"
        val created = (store.create(dir, "Talk", Mode.VOICE_AUDIO) as CoreResult.Ok).value
        current.set(OpenProject(dir, (store.save(dir, created.copy(voice = VoiceTool(task = VoiceTool.TASK_CLEAN,
            source = SourceVideo("source/song.mp3", 10, 65_000), clean = CleanChoice(pauses = false, noise = false)))) as CoreResult.Ok).value))
        val tasks = MediaTasks(null, MainScope())
        val export = VoiceExportModel(session, fs, tasks, ToolsConfig(), null, MainScope())
        val clean = VoiceCleanModel(session, fs, dashboard, FakePicker(), tasks, export, ToolsConfig(), MainScope(), { null }, { _, _, _ -> null })
        val model = VoiceAudioModel(session, dashboard, ToolsConfig(), MainScope())
        setContent { VoiceAudioScreen(model, session, onBack = {}, export = export, clean = clean) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("voice-clean-file").fetchSemanticsNodes().isNotEmpty() }
        onNode(hasText("song.mp3", substring = true)).assertExists()
        onNodeWithTag("voice-clean-choice-2").assertIsNotEnabled() // the only one on
        onNodeWithTag("voice-clean-choice-0").assertIsEnabled()
        onNodeWithTag("voice-clean-m4a").assertIsEnabled()
        onNodeWithTag("voice-clean-wav").performMouseInput { click() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("voice-clean-notice").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("This app cannot make audio files here.", clean.state.value.notice?.arguments?.get("detail"))
    }
}
