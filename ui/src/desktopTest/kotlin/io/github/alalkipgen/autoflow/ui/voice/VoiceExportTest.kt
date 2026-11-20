package io.github.alalkipgen.autoflow.ui.voice

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.voice.VoiceTakes
import io.github.alalkipgen.autoflow.media.audio.PcmWav
import io.github.alalkipgen.autoflow.media.audio.VoiceJoin
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.ProcessRunner
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.i18n.My
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
import io.github.alalkipgen.autoflow.ui.shell.VOICE_SCREEN
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
import kotlin.test.*

/** V-1f: the takes joined once into `project.voice.joined`, Save wav into exports/, Save m4a as a media task (real ffmpeg). */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class VoiceExportTest {
    @BeforeTest fun setupDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    private val config = ToolsConfig(tts = ToolsConfig.Tts(maxCharsPerPart = 10, partGapMs = 100))

    /** A voice project at [dir] with two parts; [voiced] parts get a 0.1 s take at 1 kHz. */
    private fun project(fs: FileSystem, dir: String, voiced: Int = 2): Pair<ProjectSession, ProjectStore> {
        val store = ProjectStore(fs); val current = CurrentProject()
        val session = ProjectSession(fs, store, ProjectIndex(fs, joinTmp(dir, "projects.json")), current)
        val plan = VoiceTakes.plan(VoiceTool(task = VoiceTool.TASK_TEXT, text = "First one.\nSecond.",
            lock = VoiceLock("gemini", "tts-a", "Puck", "my")), 10, Lexicon.EMPTY)
        val created = (store.create(dir, "My Voice", Mode.VOICE_AUDIO) as CoreResult.Ok).value
        val artifacts = ArtifactStore(fs, dir)
        val parts = plan.parts.mapIndexed { i, p ->
            if (i >= voiced) p else p.copy(take = (artifacts.put(PcmWav.encode(1_000, FloatArray(100) { (i + 1) / 4f }), ArtifactKind.AUDIO, "wav")
                as CoreResult.Ok).value.id)
        }
        current.set(OpenProject(dir, (store.save(dir, created.copy(voice = plan.copy(parts = parts))) as CoreResult.Ok).value))
        return session to store
    }

    private fun joinTmp(dir: String, name: String) = dir.substringBeforeLast('/') + "/" + name

    @Test fun joinsOnceAndSavesTheWavInExports() {
        val fs = InMemoryFileSystem(); val dir = "/p/Voice.autoflow"
        val (session, store) = project(fs, dir)
        val export = VoiceExportModel(session, fs, MediaTasks(null, MainScope()), config, null, MainScope())
        val id = export.joined()!!
        assertEquals(id, (store.open(dir) as CoreResult.Ok).value.voice!!.joined)
        assertEquals(id, export.joined(), "made once")
        val path = export.saveWav()!!
        assertEquals("$dir/exports/${VoiceJoin.fileName("My Voice", id.value, "wav")}", path)
        assertTrue(path.endsWith(".wav") && "/My-Voice-voice-" in path, path)
        val wav = PcmWav.parse(fs.read(path))!!
        assertEquals(100 + 100 + 100, wav.samples.size, "two takes and a 100 ms gap")
        assertEquals(0.25f, wav.samples[0], 1e-3f); assertEquals(0f, wav.samples[150]); assertEquals(0.5f, wav.samples[299], 1e-3f)
        assertEquals(path, export.ready())
        assertEquals(UiText.SUB_EXPORT_SAVED, export.state.value.notice?.key)
        assertNull(export.saveM4a(), "no render queue here")
        assertEquals("This app cannot make audio files here.", export.state.value.notice?.arguments?.get("detail"))
    }

    @Test fun notEveryPartVoicedSavesNothing() {
        val fs = InMemoryFileSystem(); val dir = "/p/Voice.autoflow"
        val (session, store) = project(fs, dir, voiced = 1)
        val export = VoiceExportModel(session, fs, MediaTasks(null, MainScope()), config, null, MainScope())
        assertNull(export.saveWav())
        assertEquals(UiText.VA_JOIN_NOT_ALL, export.state.value.notice?.key)
        assertNull((store.open(dir) as CoreResult.Ok).value.voice!!.joined)
        assertTrue(!fs.exists("$dir/exports") || fs.list("$dir/exports").isEmpty())
    }

    @Test fun saveWavOnScreenOffersOpenFolder() = runDesktopComposeUiTest(width = 412, height = 900) {
        val fs = InMemoryFileSystem(); val dir = "/p/Voice.autoflow"
        val (session, store) = project(fs, dir)
        val index = ProjectIndex(fs, "/app/projects.json")
        val model = VoiceAudioModel(session, DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), FakePicker(), CurrentProject()),
            config, MainScope(), models = listOf("tts-a"))
        val export = VoiceExportModel(session, fs, MediaTasks(null, MainScope()), config, null, MainScope())
        setContent { VoiceAudioScreen(model, session, onBack = {}, export = export) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("voice-save-wav").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("voice-save-m4a").assertIsEnabled()
        onNodeWithTag("voice-save-wav").performMouseInput { click() }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("voice-folder").fetchSemanticsNodes().isNotEmpty() }
        val notice = onNodeWithTag("voice-save-notice").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        assertEquals(My.breakSyllables(UiText.SUB_EXPORT_SAVED.en.replace("{path}", export.ready()!!)), notice)
        onNodeWithTag("voice-share").assertDoesNotExist()
    }

    private fun works(bin: String) = runCatching {
        val p = ProcessBuilder(bin, "-hide_banner", "-version").redirectErrorStream(true).start()
        p.inputStream.readAllBytes(); p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    @Test fun saveM4aMakesARealAacFile() = runBlocking {
        val ff = listOfNotNull(System.getenv("AUTOFLOW_FFMPEG"), "ffmpeg").firstOrNull(::works) ?: run {
            if (System.getenv("AUTOFLOW_EXPECT_FFMPEG") == "1") fail("AUTOFLOW_EXPECT_FFMPEG=1 but no ffmpeg")
            throw org.junit.AssumptionViolatedException("saveM4aMakesARealAacFile not executed: no ffmpeg")
        }
        val tmp = Files.createTempDirectory("voice-m4a").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fs = JvmFs(); val dir = File(tmp, "Voice.autoflow").path
            val (session, _) = project(fs, dir)
            val locator = object : ToolLocator {
                override fun findFfmpeg() = ff
                override fun findFfprobe(): String? = null
                override fun findVlc(): String? = null
                override fun isVlcDir(dir: String) = false
            }
            val tools = ToolRunner { cmd, wd, timeout, keep, onLine ->
                when (val r = ProcessRunner.run(cmd, File(wd), timeout, keep, onLine)) {
                    is ProcessRunner.Exec.Done -> ToolExec.Done(r.exitCode, r.output, r.tail, r.wallMs, r.timedOut)
                    is ProcessRunner.Exec.CannotStart -> ToolExec.CannotStart(r.message)
                }
            }
            val tasks = MediaTasks(InProcessRenderQueue(fs, MediaTools(SettingsStore(fs, File(tmp, "s.json").path), locator, tools), scope), scope)
            val export = VoiceExportModel(session, fs, tasks, config, null, scope)
            val run = export.saveM4a()!!
            assertEquals(VoiceJoin.VOICE_M4A, run.kind); assertEquals(VOICE_SCREEN, run.screen)
            withTimeout(60_000) { while (tasks.run(run.id)?.state?.finished != true) delay(20) }
            assertIs<RenderState.Done>(tasks.run(run.id)!!.state, tasks.run(run.id)!!.state.toString())
            val out = File(export.ready()!!)
            assertTrue(out.name.endsWith(".m4a") && out.parentFile.name == "exports" && out.length() > 0, out.path)
            assertEquals("ftyp", String(out.readBytes().copyOfRange(4, 8)), "an mp4 container")
        } finally {
            scope.cancel()
            tmp.deleteRecursively()
        }
    }
}
