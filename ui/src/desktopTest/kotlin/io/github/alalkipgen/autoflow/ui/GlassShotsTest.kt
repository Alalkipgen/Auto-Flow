package io.github.alalkipgen.autoflow.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.recap.AiRunTiming
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.ProcessRunner
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.platform.RenderPackages
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.LocalMediaThumbnails
import io.github.alalkipgen.autoflow.ui.export.ExportHarness
import io.github.alalkipgen.autoflow.ui.export.ExportRun
import io.github.alalkipgen.autoflow.ui.home.HomeScreen
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.jobs.JobRow
import io.github.alalkipgen.autoflow.ui.jobs.JobsActions
import io.github.alalkipgen.autoflow.ui.jobs.JobsScreen
import io.github.alalkipgen.autoflow.ui.jobs.jobRows
import io.github.alalkipgen.autoflow.ui.jobs.jobsBadgeValue
import io.github.alalkipgen.autoflow.ui.media.MediaThumbnails
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.DashboardScreen
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Dest
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Shell
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark

/**
 * Phase 9 group 1 (glass look) screenshots, the owner's capture rules (v4 FINAL README): an Android phone 360 dp drawn
 * at density 2, font 1.0 and 2.0, English and Burmese, the bundled Pyidaungsu, `labs.dubbing` on, the shared demo data
 * (seven projects, two removed, five jobs, real scene frames through the real ffmpeg into a temporary cache) and the
 * full scroll height (window = 760 dp + the screen's scroll range). Runs only when `AUTOFLOW_GLASS_SHOTS_DIR` is set;
 * writes `<language>/<n>-<screen>-<font>.png`. Each capture also checks that no text is cut, clipped or truncated.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class GlassShotsTest(val language: UiLanguage, val font: Float) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}")
        fun cases() = listOf(UiLanguage.ENGLISH, UiLanguage.BURMESE).flatMap { l -> listOf(1f, 2f).map { arrayOf<Any>(l, it) } }
        private const val SCALE = 2f
        private const val WIDTH = 360
        private const val HEIGHT = 760
    }
    private val out = System.getenv("AUTOFLOW_GLASS_SHOTS_DIR")?.let(::File)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val fs = JvmFs()
    private val root by lazy { Files.createTempDirectory("glass-shots").toFile() }
    private val now = Instant.parse("2025-06-01T12:00:00Z")
    private val clock = object : Clock { override fun now() = now }

    private class Demo(val name: String, val mode: Mode, val ratio: Ratio, val ago: Duration, val shots: Pair<Int, Int>?,
        val frame: String?, val durationMs: Long?)
    private val demo = listOf(
        Demo("Dune: Part Two", Mode.MOVIE_RECAP, Ratio.R9_16, 25.minutes, 6 to 14, "dune", 9_952_000),
        Demo("Kung Fu Panda 4", Mode.DUBBING, Ratio.R16_9, 2.hours, null, "kfp", 1_450_000),
        Demo("Bagan Vlog", Mode.SUBTITLES, Ratio.R16_9, 5.hours, null, "bagan", 754_000),
        Demo("Interstellar", Mode.MOVIE_RECAP, Ratio.R16_9, 1.days, 12 to 12, "inter", 10_143_000),
        Demo("သင်ခန်းစာ 3", Mode.SUBTITLES, Ratio.R16_9, 3.days, null, "lesson", 1_122_000),
        Demo("ကြော်ငြာ အသံ", Mode.VOICE_AUDIO, Ratio.R9_16, 5.days, null, null, null),
        Demo("ချစ်သူ ကမ္ဘာ", Mode.MUSIC_VIDEO, Ratio.R9_16, 12.days, 8 to 8, null, null),
    )

    /** The demo projects (real folders outside the managed home, so nothing re-records them) and the two removed ones. */
    private inner class Fixture(interDir: String? = null) {
        val store = ProjectStore(fs)
        val home = File(root, "home").apply { mkdirs() }
        val dirs = demo.mapIndexed { i, d ->
            val dir = if (d.frame == "inter" && interDir != null) interDir else File(root, "list/p$i.autoflow").path
            if (dir != interDir) (store.create(dir, d.name, d.mode, ProjectSettings(ratio = d.ratio)) as CoreResult.Ok).value
            d.name to dir
        }.toMap()
        val removed = listOf("Old trailer", "Draft 1").mapIndexed { i, n ->
            File(home, "removed$i.autoflow").path.also { (store.create(it, n, Mode.MOVIE_RECAP) as CoreResult.Ok).value }
        }
        val indexPath = File(root, "index-${System.nanoTime()}.json").path.also { path ->
            val rows = demo.joinToString(",") { d ->
                val (a, t) = d.shots ?: (0 to 0)
                """{"path":${q(dirs.getValue(d.name))},"name":${q(d.name)},"mode":"${d.mode.name}","ratio":"${d.ratio.wire()}",""" +
                    """"acceptedShots":$a,"totalShots":$t,"lastOpenedAt":"${now - d.ago}"}"""
            }
            val gone = removed.joinToString(",") { q(fs.canonicalPath(it)) }
            File(path).writeText("""{"schemaVersion":1,"projects":[$rows],"removedPaths":[$gone]}""")
        }
        val index = ProjectIndex(fs, indexPath, clock)
        val scenes = File(root, "scenes").apply { mkdirs() }.also { dir ->
            listOf("dune", "kfp", "inter", "lesson", "bagan").forEach { k ->
                File(dir, "$k.png").writeBytes(scenePng(k))
            }
        }
        val settings = SettingsStore(fs, File(root, "settings.json").path)
        val locator = object : ToolLocator {
            override fun findFfmpeg() = System.getenv("AUTOFLOW_FFMPEG") ?: "ffmpeg"
            override fun findFfprobe() = System.getenv("AUTOFLOW_FFPROBE") ?: "ffprobe"
            override fun findVlc(): String? = null
            override fun isVlcDir(dir: String) = false
        }
        val runner = ToolRunner { cmd, wd, timeout, keep, line -> when (val r = ProcessRunner.run(cmd, File(wd), timeout, keep, line)) {
            is ProcessRunner.Exec.Done -> ToolExec.Done(r.exitCode, r.output, r.tail, r.wallMs, r.timedOut)
            is ProcessRunner.Exec.CannotStart -> ToolExec.CannotStart(r.message)
        } }
        val thumbs = MediaThumbnails(fs, MediaTools(settings, locator, runner), File(root, "cache-${System.nanoTime()}").path)
        val sources = demo.filter { it.frame != null }.associate { d ->
            dirs.getValue(d.name) to MediaThumbnails.Source(dirs.getValue(d.name), File(scenes, "${d.frame}.png").path, durationMs = d.durationMs)
        }
        val thumbSource: suspend (String) -> MediaThumbnails.Source? = { dir -> sources[dir] }
        val names = dirs.entries.associate { (n, d) -> d to n }
        fun dir(name: String) = dirs.getValue(name)
        fun ai() = listOf(
            AiJob("kfp", AiJobKind.DUB_TTS, dir("Kung Fu Panda 4"), (25..40).map { "$it" }, AiJobState.RUNNING, initialItemCount = 40, savedClips = 24),
            AiJob("bagan", AiJobKind.MEDIA_TRANSCRIBE, dir("Bagan Vlog"), listOf("0"), AiJobState.QUEUED, initialItemCount = 1),
            AiJob("lesson", AiJobKind.SUB_TRANSLATE, dir("သင်ခန်းစာ 3"), (1..12).map { "$it" }, AiJobState.PAUSED, initialItemCount = 30),
            AiJob("ad", AiJobKind.TEXT_TTS, dir("ကြော်ငြာ အသံ"), (1..4).map { "$it" }, AiJobState.FAILED,
                "Gemini returned no audio (finishReason=OTHER)", initialItemCount = 6),
        )
        val ai = object : AiJobRunner {
            override val jobs = MutableStateFlow(ai())
            override suspend fun enqueue(job: AiJob) = job.id
            override suspend fun cancel(id: String) = Unit
            override suspend fun refresh() = Unit
        }
        val output = File(root, "exports/Interstellar_16x9.mp4").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0)) }.path
        fun rows(render: ExportRun? = ExportRun(dir("Interstellar"), Quality.EXPORT, "render-1", output, RenderState.Done(output, 1, 2, null))) =
            jobRows(null, render, null, ai.jobs.value, language, names)
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun Ratio.wire() = "$width:$height"

    /** The live timing of the running dub job: "Waiting for Gemini · 0:42" (a fixed mark, so the label never ticks). */
    private fun liveTiming() {
        val mark = object : TimeMark { override fun elapsedNow() = 42.seconds }
        AiRunTiming(id = "kfp").restore(AiRunTiming.Live(mark))
    }

    /**
     * Two passes: the first measures the screen's scroll range at 760 dp, the second draws it at the full height, waits
     * for [frames] real thumbnails and writes the PNG.
     */
    private fun shot(name: String, frames: Int, content: @Composable () -> Unit) {
        val dir = out ?: return
        val provide: @Composable (@Composable () -> Unit) -> Unit = { inner ->
            CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(SCALE, font),
                LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family, content = inner)
        }
        var extra = 0
        runDesktopComposeUiTest(width = (WIDTH * SCALE).toInt(), height = (HEIGHT * SCALE).toInt()) {
            setContent { provide(content) }
            waitForIdle()
            extra = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNodes()
                .maxOfOrNull { it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() }?.toInt() ?: 0
        }
        runDesktopComposeUiTest(width = (WIDTH * SCALE).toInt(), height = (HEIGHT * SCALE).toInt() + extra) {
            setContent { provide(content) }
            waitForIdle()
            waitUntil(timeoutMillis = 60_000) { onAllNodesWithTag("media-thumb-image", useUnmergedTree = true).fetchSemanticsNodes().size >= frames }
            waitForIdle()
            val file = File(dir, "${language.code()}/$name-$font.png").apply { parentFile.mkdirs() }
            ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", file)
            assertEquals(emptyList(), textFitProblems((WIDTH * SCALE).toInt(), font, strict = true), "$name ${language.code()} $font")
        }
    }
    private fun UiLanguage.code() = if (this == UiLanguage.BURMESE) "my" else "en"

    @Test fun home() {
        if (out == null) return
        val f = Fixture(); val rows = f.rows(); liveTiming()
        val actions = JobsActions(scope, runner = f.ai)
        val step = UiText.V3_STEP.text(language, "step" to UiText.STORY.text(language), "n" to 2)
        shot("1-home", 3) {
            CompositionLocalProvider(LocalMediaThumbnails provides f.thumbs) {
                StudioV2Shell(StudioV2Dest.HOME, {}, jobsBadgeValue(rows), WIDTH.toFloat(), ambient = true) {
                    HomeScreen(recent = f.index.entries.value, jobs = rows, jobActions = actions, continueStep = step,
                        continueProjectPath = f.dir("Dune: Part Two"), onNewSubtitles = {}, onNewVoice = {}, onNewDubbing = {},
                        thumbSource = f.thumbSource)
                }
            }
        }
    }

    @Test fun projects() {
        if (out == null) return
        val f = Fixture(); val rows = f.rows()
        val packages = object : RenderPackages {
            override suspend fun pack(projectDir: String, folder: String, extras: List<io.github.alalkipgen.autoflow.platform.PackageFile>,
                zip: String, onProgress: (io.github.alalkipgen.autoflow.platform.BackupProgress) -> Unit) = PlatformResult.Unsupported("shots")
            override suspend fun unpack(zipPath: String, destDir: String) = PlatformResult.Unsupported("shots")
        }
        val model = DashboardModel(fs, f.store, f.index, f.settings, FakePicker(), CurrentProject(), projectsHome = f.home.path,
            packages = packages)
        shot("2-projects", 5) {
            CompositionLocalProvider(LocalMediaThumbnails provides f.thumbs) {
                StudioV2Shell(StudioV2Dest.PROJECTS, {}, jobsBadgeValue(rows), WIDTH.toFloat(), ambient = true) {
                    DashboardScreen(model, {}, clock = clock, jobs = rows, thumbSource = f.thumbSource)
                }
            }
        }
    }

    /** Jobs: the finished Interstellar export is a real (fake-queue) render, so its file actions show. */
    @Test fun jobs() {
        if (out == null) return
        ExportHarness().use { h ->
            val f = Fixture(h.fixture.dir.path)
            h.model.refresh(); h.model.render(Quality.EXPORT)
            val deadline = System.currentTimeMillis() + 180_000
            while (h.model.state.value.run == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
            val output = File(h.fixture.dir, "exports/Interstellar_16x9.mp4").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0)) }.path
            h.run.value = RenderState.Done(output, 1, 2, null)
            val share = object : FileShare {
                override fun open(path: String, mime: String) = PlatformResult.Ok(Unit)
                override fun share(path: String, mime: String, title: String) = PlatformResult.Ok(Unit)
                override suspend fun saveToGallery(path: String, mime: String, displayName: String) = PlatformResult.Ok(displayName)
            }
            val actions = JobsActions(scope, render = h.model, runner = f.ai, fs = fs, share = share)
            val rows = f.rows(h.model.state.value.run)
            liveTiming()
            shot("4-jobs", 3) {
                CompositionLocalProvider(LocalMediaThumbnails provides f.thumbs) {
                    StudioV2Shell(StudioV2Dest.JOBS, {}, jobsBadgeValue(rows), WIDTH.toFloat(), ambient = true) {
                        JobsScreen(rows, scope, {}, actions = actions, thumbSource = f.thumbSource)
                    }
                }
            }
        }
    }
}

/** The owner's demo frame [name] (Exact UI v4 `lib.js` scene, kept as SVG text) drawn 320 × 200 by Skia into a PNG. */
private fun scenePng(name: String): ByteArray {
    val svg = org.jetbrains.skia.svg.SVGDOM(org.jetbrains.skia.Data.makeFromBytes(
        GlassShotsTest::class.java.getResourceAsStream("/glass-scenes/$name.svg")!!.readBytes()))
    svg.setContainerSize(320f, 200f)
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(320, 200)
    svg.render(surface.canvas)
    return surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
}
