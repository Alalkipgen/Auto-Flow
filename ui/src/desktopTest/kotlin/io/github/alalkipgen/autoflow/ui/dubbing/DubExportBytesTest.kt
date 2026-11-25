package io.github.alalkipgen.autoflow.ui.dubbing

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.dubbing.DubRules
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.DubOriginal
import io.github.alalkipgen.autoflow.core.model.DubTake
import io.github.alalkipgen.autoflow.core.model.DubbingTool
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.ProcessRunner
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.export.RecapFrames
import io.github.alalkipgen.autoflow.ui.media.InProcessRenderQueue
import io.github.alalkipgen.autoflow.ui.media.MediaTasks
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Group 4 rule check (PR 6): the Dubbing export with a real ffmpeg, so its files can be compared byte for byte between two
 * builds (the look changes must not change what is made). Runs only when `AUTOFLOW_DUB_BYTES_DIR` is set: makes a 12 s
 * test video and three spoken takes in `<dir>/work-<case>` (the same paths every run), exports (a) Lower 15 % + the
 * `.wav`, (b) Burmese subtitles burned + Mute, and writes `<dir>/<case>.sha256` (`sha256  path` of every file made).
 */
class DubExportBytesTest {
    private val out = System.getenv("AUTOFLOW_DUB_BYTES_DIR")?.let(::File)
    private val ffmpeg = System.getenv("AUTOFLOW_FFMPEG") ?: "ffmpeg"
    private val tools = ToolRunner { cmd, wd, timeout, keep, onLine ->
        when (val r = ProcessRunner.run(cmd, File(wd), timeout, keep, onLine)) {
            is ProcessRunner.Exec.Done -> ToolExec.Done(r.exitCode, r.output, r.tail, r.wallMs, r.timedOut)
            is ProcessRunner.Exec.CannotStart -> ToolExec.CannotStart(r.message)
        }
    }
    private val lock = VoiceLock("gemini", "tts-a", "Kore", "my")

    private fun ff(vararg args: String, wd: File) {
        val p = ProcessBuilder(listOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-nostdin") + args).directory(wd).redirectErrorStream(true).start()
        val log = p.inputStream.readAllBytes().decodeToString()
        check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { log }
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun case(name: String, burn: Boolean, mute: Boolean, wav: Boolean) = runBlocking {
        val root = File(out!!, "work-$name").apply { deleteRecursively(); mkdirs() }
        val dir = File(root, "Dub film.autoflow")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fs = JvmFs()
            val store = ProjectStore(fs)
            val current = CurrentProject()
            val session = ProjectSession(fs, store, ProjectIndex(fs, File(root, "projects.json").path), current)
            val settings = SettingsStore(fs, File(root, "settings.json").path)
            val created = (store.create(dir.path, "Dub film", Mode.DUBBING) as CoreResult.Ok).value
            File(dir, "source").mkdirs()
            ff("-f", "lavfi", "-i", "testsrc2=s=640x360:r=25:d=12", "-f", "lavfi", "-i", "sine=frequency=330:sample_rate=48000:d=12",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "96k", "-shortest",
                "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-flags:a", "+bitexact", "source/movie.mp4", wd = dir)
            val doc = SubtitleDoc(source = "srt", nextId = 4, cues = listOf(
                SubtitleCue("c1", 1_000, 3_000, "We have to leave tonight.", textMy = "ဒီည ထွက်သွားရမယ်။"),
                SubtitleCue("c2", 4_500, 6_500, "Before the rain starts.", textMy = "မိုးမရွာခင် သွားရမယ်။"),
                SubtitleCue("c3", 8_000, 10_000, "Trust me.", textMy = "ငါ့ကို ယုံပါ")))
            val art = ArtifactStore(fs, dir.path)
            val st = (art.put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(), ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
            val takes = doc.cues.withIndex().associate { (i, c) ->
                val wavFile = File(root, "take-${c.id}.wav")
                ff("-f", "lavfi", "-i", "sine=frequency=${500 + i * 120}:sample_rate=24000:d=1.5", "-ac", "1", "-c:a", "pcm_s16le",
                    "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", wavFile.name, wd = root)
                val au = (art.put(wavFile.readBytes(), ArtifactKind.AUDIO, "wav") as CoreResult.Ok).value.id
                c.id to DubTake(au, DubRules.takeKey(c, Lexicon.EMPTY, lock, null), 1_500, leadMs = 40)
            }
            val tool = DubRules.approve(DubbingTool(lock = lock, takes = takes, burnSubtitles = burn,
                original = if (mute) DubOriginal(DubOriginal.MUTE, 0.0) else DubOriginal()), 12_000, 600_000, doc, 1)!!
            val video = SourceVideo("source/movie.mp4", File(dir, "source/movie.mp4").length(), 12_000, 640, 360, 25.0)
            current.set(OpenProject(dir.path, (store.save(dir.path, created.copy(dubbing = tool,
                subtitles = SubtitlesTool(video = video, current = st))) as CoreResult.Ok).value))
            if (burn) {
                val font = File(root, "fonts/noto.ttf").apply { parentFile.mkdirs() }
                font.writeBytes(Base64.getMimeDecoder().decode(javaClass.getResourceAsStream("/fonts/NotoSansMyanmar-subset.ttf.b64")!!.readBytes()))
                settings.update { it.copy(fontPaths = listOf(font.path)) }
            }
            val locator = object : ToolLocator {
                override fun findFfmpeg() = ffmpeg
                override fun findFfprobe(): String? = null
                override fun findVlc(): String? = null
                override fun isVlcDir(dir: String) = false
            }
            val queue = InProcessRenderQueue(fs, MediaTools(settings, locator, tools), scope)
            val model = DubExportModel(session, fs, MediaTasks(queue, scope), scope, RecapFrames(session, settings, fs))
            model.setWav(wav)
            checkNotNull(model.start(doc)) { "not started: ${model.state.value.notice}" }
            withTimeout(300_000) { while (model.state.value.busy) delay(50) }
            val path = model.done()
            checkNotNull(path) { "no video: ${model.current()?.state}" }
            val made = listOf(File(dir, "exports"), File(dir, ".cache/burn")).filter { it.exists() }
                .flatMap { it.walkTopDown().filter(File::isFile).toList() }.sortedBy { it.path }
            assertTrue(made.isNotEmpty())
            File(out, "$name.sha256").writeText(made.joinToString("") { "${sha(it)}  ${it.relativeTo(dir).path}  ${it.length()}\n" })
        } finally { scope.cancel() }
    }

    @Test fun lowerAndWav() { if (out != null) case("lower-wav", burn = false, mute = false, wav = true) }
    @Test fun burnAndMute() { if (out != null) case("burn-mute", burn = true, mute = true, wav = false) }
}
