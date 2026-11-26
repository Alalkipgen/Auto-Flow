package io.github.alalkipgen.autoflow.ui.subtitles

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
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
import io.github.alalkipgen.autoflow.media.playback.MoviePhase
import io.github.alalkipgen.autoflow.media.playback.MovieSnapshot
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.ProcessRunner
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.export.RecapFrames
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
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
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
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

/**
 * Group 5 rule check (PR 7): the Subtitles export through the screen (Export → the chips → Save .srt, Check one frame,
 * Make video) with a real ffmpeg, so what it writes can be compared byte for byte between two builds (the Export page
 * look must not change what is made). Runs only when `AUTOFLOW_SUB_BYTES_DIR` is set: makes a 12 s test video in
 * `<dir>/work-<case>` (the same paths every run), exports and writes `<dir>/<case>.sha256` (`sha256  path  size` of every
 * file in `exports/` and `.cache/burn/`).
 */
@OptIn(ExperimentalTestApi::class)
class SubExportBytesTest {
    private val out = System.getenv("AUTOFLOW_SUB_BYTES_DIR")?.let(::File)
    private val ffmpeg = System.getenv("AUTOFLOW_FFMPEG") ?: "ffmpeg"
    private val tools = ToolRunner { cmd, wd, timeout, keep, onLine ->
        when (val r = ProcessRunner.run(cmd, File(wd), timeout, keep, onLine)) {
            is ProcessRunner.Exec.Done -> ToolExec.Done(r.exitCode, r.output, r.tail, r.wallMs, r.timedOut)
            is ProcessRunner.Exec.CannotStart -> ToolExec.CannotStart(r.message)
        }
    }
    private val desktop = object : FileShare {
        override val sharesFiles = false
        override fun open(path: String, mime: String) = PlatformResult.Ok(Unit)
        override fun share(path: String, mime: String, title: String) = PlatformResult.Ok(Unit)
        override suspend fun saveToGallery(path: String, mime: String, displayName: String) = PlatformResult.Ok(displayName)
        override fun openFolder(path: String) = PlatformResult.Ok(Unit)
    }
    private val noDecoder: SubtitlePlayer = { _, _, m, report ->
        LaunchedEffect(Unit) { report(MovieSnapshot(phase = MoviePhase.FAILED, problem = "no decoder")) }
        Box(m)
    }

    private fun ff(vararg args: String, wd: File) {
        val p = ProcessBuilder(listOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-nostdin") + args).directory(wd).redirectErrorStream(true).start()
        val log = p.inputStream.readAllBytes().decodeToString()
        check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { log }
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    /** [chips] = the tags pressed before the export (language, look, size, lines, length). */
    private fun case(name: String, chips: List<String>) = runDesktopComposeUiTest(width = 1280, height = 900) {
        val root = File(out!!, "work-$name").apply { deleteRecursively(); mkdirs() }
        val dir = File(root, "Sub film.autoflow")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fs = JvmFs()
            val store = ProjectStore(fs)
            val current = CurrentProject()
            val index = ProjectIndex(fs, File(root, "projects.json").path)
            val session = ProjectSession(fs, store, index, current)
            val settings = SettingsStore(fs, File(root, "settings.json").path)
            val created = (store.create(dir.path, "Sub film", Mode.SUBTITLES) as CoreResult.Ok).value
            File(dir, "source").mkdirs()
            ff("-f", "lavfi", "-i", "testsrc2=s=640x360:r=25:d=12", "-f", "lavfi", "-i", "sine=frequency=330:sample_rate=48000:d=12",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "96k", "-shortest",
                "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-flags:a", "+bitexact", "source/movie.mp4", wd = dir)
            val doc = SubtitleDoc(source = SubtitleDoc.SOURCE_SRT, nextId = 4, cues = listOf(
                SubtitleCue("c1", 1_000, 3_000, "We have to leave tonight.", textMy = "ဒီည ထွက်သွားရမယ်။"),
                SubtitleCue("c2", 4_500, 6_500, "Before the rain starts, we cross the river and wait for the others there.",
                    textMy = "မိုးမရွာခင် မြစ်ကိုဖြတ်ပြီး တခြားသူတွေကို ဟိုမှာ စောင့်ကြမယ်။"),
                SubtitleCue("c3", 8_000, 10_000, "Trust me.", textMy = "ငါ့ကို ယုံပါ")))
            val st = (ArtifactStore(fs, dir.path).put(CoreJson.encodeToString(SubtitleDoc.serializer(), doc).encodeToByteArray(),
                ArtifactKind.SUBTITLES, "json") as CoreResult.Ok).value.id
            val video = SourceVideo("source/movie.mp4", File(dir, "source/movie.mp4").length(), 12_000, 640, 360, 25.0)
            current.set(OpenProject(dir.path, (store.save(dir.path, created.copy(subtitles = SubtitlesTool(video = video, current = st))) as CoreResult.Ok).value))
            val font = File(root, "fonts/noto.ttf").apply { parentFile.mkdirs() }
            font.writeBytes(Base64.getMimeDecoder().decode(javaClass.getResourceAsStream("/fonts/NotoSansMyanmar-subset.ttf.b64")!!.readBytes()))
            settings.update { it.copy(fontPaths = listOf(font.path)) }
            val locator = object : ToolLocator {
                override fun findFfmpeg() = ffmpeg
                override fun findFfprobe(): String? = null
                override fun findVlc(): String? = null
                override fun isVlcDir(dir: String) = false
            }
            val media = MediaTools(settings, locator, tools)
            val queue = InProcessRenderQueue(fs, media, scope)
            val model = SubtitlesModel(session, fs, DashboardModel(fs, store, index, settings, FakePicker(), current), null, scope, probe = { null })
            val editor = SubtitleEditorModel(session, fs, scope)
            val exporter = SubtitleExportModel(session, fs, desktop, scope)
            val burner = SubtitleBurnModel(session, fs, MediaTasks(queue, scope), RecapFrames(session, settings, fs), media, share = desktop)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalUiLanguage provides UiLanguage.ENGLISH,
                    LocalUiFontFamily provides BurmeseTestFont.family) {
                    SubtitlesScreen(model, session, editor, player = noDecoder, onBack = {}, exporter = exporter, burner = burner)
                }
            }
            fun tap(tag: String) { onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); waitForIdle() }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("subtitles-export-open").fetchSemanticsNodes().isNotEmpty() }
            tap("subtitles-export-open")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("subtitles-export-save").fetchSemanticsNodes().isNotEmpty() }
            chips.forEach(::tap)
            tap("subtitles-export-save")
            waitUntil(timeoutMillis = 10_000) { exporter.state.value.ok }
            tap("subtitles-check-next")
            tap("subtitles-check-frame")
            waitUntil(timeoutMillis = 120_000) { !burner.state.value.checking && burner.state.value.frame != null }
            tap("subtitles-burn-make")
            waitUntil(timeoutMillis = 300_000) { burner.run()?.state is RenderState.Done || burner.run()?.state is RenderState.Failed }
            check(burner.run()?.state is RenderState.Done) { "no video: ${burner.run()?.state}" }
            val made = listOf(File(dir, "exports"), File(dir, ".cache/burn")).filter { it.exists() }
                .flatMap { it.walkTopDown().filter(File::isFile).toList() }.sortedBy { it.path }
            assertTrue(made.any { it.name.endsWith(".srt") } && made.any { it.name.endsWith(".mp4") } && made.any { it.name.endsWith(".png") })
            File(out, "$name.sha256").writeText(made.joinToString("") { "${sha(it)}  ${it.relativeTo(dir).path}  ${it.length()}\n" })
        } finally { scope.cancel() }
    }

    /** Burmese (the default), Clean / Medium / the fit by the video (1 line, short). */
    @Test fun burmeseDefaults() { if (out != null) case("burmese-default", emptyList()) }

    /** Original, Outline / Large / 2 lines / Long. */
    @Test fun originalOutlineLarge() {
        if (out != null) case("original-outline", listOf("subtitles-export-ORIGINAL", "subtitles-burn-OUTLINE", "subtitles-burn-LARGE",
            "subtitles-burn-lines-2", "subtitles-burn-length-LONG"))
    }
}
