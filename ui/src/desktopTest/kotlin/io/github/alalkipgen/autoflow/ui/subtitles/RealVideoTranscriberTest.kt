package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.SceneSpan
import io.github.alalkipgen.autoflow.ai.TranscribeProvider
import io.github.alalkipgen.autoflow.ai.Transcript as AiTranscript
import io.github.alalkipgen.autoflow.ai.TranscriptSegment
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiJobStore
import io.github.alalkipgen.autoflow.core.recap.RpmPacer
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.ProcessRunner
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.jobs.DesktopAiJobRunner
import io.github.alalkipgen.autoflow.ui.jobs.RecapJobProcessor
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * S-5a with a real ffmpeg: `silencedetect` finds the pause the plan cuts at, each chunk's sound is cut to a small
 * mono AAC file, and the job ends with a new subtitles version (Gemini is a fake).
 */
class RealVideoTranscriberTest {
    private fun works(bin: String) = runCatching {
        val p = ProcessBuilder(bin, "-hide_banner", "-version").redirectErrorStream(true).start()
        p.inputStream.readAllBytes(); p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    private val ffmpeg: String? by lazy { listOfNotNull(System.getenv("AUTOFLOW_FFMPEG"), "ffmpeg").firstOrNull(::works) }

    private val tools = ToolRunner { cmd, wd, timeout, keep, onLine ->
        when (val r = ProcessRunner.run(cmd, File(wd), timeout, keep, onLine)) {
            is ProcessRunner.Exec.Done -> ToolExec.Done(r.exitCode, r.output, r.tail, r.wallMs, r.timedOut)
            is ProcessRunner.Exec.CannotStart -> ToolExec.CannotStart(r.message)
        }
    }

    @Test
    fun findsThePauseCutsTheSoundAndMakesAVersion(): Unit = runBlocking {
        val ff = ffmpeg ?: run {
            if (System.getenv("AUTOFLOW_EXPECT_FFMPEG") == "1") fail("AUTOFLOW_EXPECT_FFMPEG=1 but no ffmpeg")
            throw org.junit.AssumptionViolatedException("RealVideoTranscriberTest not executed: no ffmpeg")
        }
        val root = Files.createTempDirectory("transcribe").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val dir = File(root, "Film.autoflow").path
            val fs = JvmFs()
            val store = ProjectStore(fs)
            val p = (store.create(dir, "Film", Mode.SUBTITLES) as CoreResult.Ok).value
            File(dir, "source").mkdirs()
            // 14 s: a tone with a 1-second pause at 5–6 s.
            val enc = ProcessBuilder(ff, "-hide_banner", "-y", "-nostdin", "-f", "lavfi", "-i", "color=c=black:s=160x90:r=10:d=14",
                "-f", "lavfi", "-i", "aevalsrc='if(between(t,5,6),0,0.5*sin(440*2*PI*t))':d=14:s=44100",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", "source/movie.mp4")
                .directory(File(dir)).redirectErrorStream(true).start()
            val log = enc.inputStream.readAllBytes().decodeToString()
            assertTrue(enc.waitFor(60, TimeUnit.SECONDS) && enc.exitValue() == 0, log)
            val video = SourceVideo("source/movie.mp4", File(dir, "source/movie.mp4").length(), 14_000, 160, 90, 10.0)
            store.save(dir, p.copy(subtitles = SubtitlesTool(video = video)))

            val locator = object : ToolLocator {
                override fun findFfmpeg() = ff
                override fun findFfprobe(): String? = null
                override fun findVlc(): String? = null
                override fun isVlcDir(dir: String) = false
            }
            val sent = mutableListOf<Int>()
            val gemini = object : TranscribeProvider {
                override suspend fun transcribe(audio: ByteArray, lang: String, mime: String, lines: List<String>): AiResult<AiTranscript> = error("not used")
                override suspend fun transcribeScenes(audio: ByteArray, mime: String, scenes: List<SceneSpan>, lang: String?): AiResult<List<List<TranscriptSegment>>> {
                    sent += audio.size
                    return AiResult.Ok(listOf(listOf(TranscriptSegment(100, 400, "line ${sent.size}"))), Usage("gemini-test"))
                }
            }
            val config = ToolsConfig(transcribe = ToolsConfig.Transcribe(chunkMaxMs = 8_000, silenceWindowMs = 4_000, overlapMs = 500))
            val session = ProjectSession(fs, store, ProjectIndex(fs, File(root, "projects.json").path), CurrentProject())
            val t = VideoTranscriber(session, fs, MediaTools(SettingsStore(fs, File(root, "s.json").path), locator, tools), gemini, config)

            val plan = t.plan(dir, video)
            assertEquals(3, plan.chunks.size, plan.chunks.toString())
            val cut = plan.chunks[0].endMs
            assertTrue(cut in 5_300..5_700, "cut in the pause: $cut")
            assertEquals(listOf(0L to cut, cut to cut + 8_000, cut + 7_500 to 14_000L), plan.chunks.map { it.startMs to it.endMs }, "then a hard cut with overlap")

            val runner = DesktopAiJobRunner(AiJobStore(fs, File(root, "jobs").path), scope, RecapJobProcessor({ null }, { t }), pacer = { RpmPacer(600) })
            val id = t.enqueue(runner, dir, plan, VideoTranscriber.AUTO)
            withTimeout(90_000) { while (runner.jobs.value.firstOrNull { it.id == id }?.state != AiJobState.DONE) delay(50) }
            assertEquals(3, sent.size)
            assertTrue(sent.all { it in 1_000..200_000 }, "small mono AAC chunks: $sent")
            val cache = File(dir, ".cache/transcribe").listFiles()!!.map { it.name }.sorted()
            assertEquals(plan.chunks.map { "k${it.startMs}-${it.endMs}.m4a" }.sorted(), cache)
            val now = (store.open(dir) as CoreResult.Ok).value.subtitles!!.current
            assertNotNull(now)
            assertIs<CoreResult.Ok<ByteArray>>(ArtifactStore(fs, dir).readBytes(now), "the new version is a file of the project")
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }
}
