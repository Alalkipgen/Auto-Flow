package io.github.alalkipgen.autoflow.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.ProcessRunner
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion.Refusal
import io.github.alalkipgen.autoflow.platform.ProjectFolderDeletion.Result as Deletion
import io.github.alalkipgen.autoflow.platform.RenderPackages
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.LocalMediaThumbnails
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
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
import kotlin.time.Instant

/**
 * Projects dialogs screenshots (owner's Projects dialogs README §5): an Android phone 360 × 780 dp drawn at density 2,
 * font 1.0 and 2.0, English and Burmese, the bundled Pyidaungsu, over the Projects screen (the Group 1 demo projects with
 * real scene frames; two removed projects in the phone's app folder). Dialogs 1–5 and three variants (not app-managed,
 * a refusal, after a delete error); a fake deletion (184 600 000 bytes = "184 MB", one backup), nothing is deleted. A
 * dialog taller than the phone gets a taller window, so the whole dialog shows. Runs only when `AUTOFLOW_DIALOG_SHOTS_DIR`
 * is set; writes `<language>/<n>-<dialog>-<font>.png`. Each capture also checks that no text is cut, clipped or truncated.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class ProjectsDialogsShotsTest(val language: UiLanguage, val font: Float) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}")
        fun cases() = listOf(UiLanguage.ENGLISH, UiLanguage.BURMESE).flatMap { l -> listOf(1f, 2f).map { arrayOf<Any>(l, it) } }
        private const val SCALE = 2f
        private const val WIDTH = 360
        private const val HEIGHT = 780
        private const val HOME = "/storage/emulated/0/Android/data/io.github.alalkipgen.autoflow/files/projects"
    }
    private val out = System.getenv("AUTOFLOW_DIALOG_SHOTS_DIR")?.let(::File)
    private val root by lazy { Files.createTempDirectory("dialog-shots").toFile() }
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
    private val scenes by lazy { File(root, "scenes").apply { mkdirs() }.also { dir -> GlassScenes.forEach { File(dir, "$it.png").writeBytes(glassScenePng(it)) } } }
    private val thumbs by lazy {
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
        MediaThumbnails(JvmFs(), MediaTools(SettingsStore(JvmFs(), File(root, "settings.json").path), locator, runner), File(root, "cache").path)
    }

    /** The demo list in memory (the phone's paths), two removed projects in the app folder and a fake deletion. */
    private inner class Fixture(managed: Boolean, refusal: Refusal?, deleteError: Boolean) {
        val fs = InMemoryFileSystem()
        val store = ProjectStore(fs)
        val dirs = demo.mapIndexed { i, d ->
            val dir = "/storage/emulated/0/Movies/AutoFlow/p$i.autoflow"
            store.create(dir, d.name, d.mode, ProjectSettings(ratio = d.ratio)).getOrNull()!!
            d.name to dir
        }.toMap()
        val removed = listOf("Oppenheimer draft", "ကြော်ငြာ v1").associate { n ->
            val dir = "$HOME/$n.autoflow"
            dir to store.create(dir, n, Mode.MOVIE_RECAP).getOrNull()!!.id
        }
        val index = run {
            val rows = demo.joinToString(",") { d ->
                val (a, t) = d.shots ?: (0 to 0)
                """{"path":${q(dirs.getValue(d.name))},"name":${q(d.name)},"mode":"${d.mode.name}","ratio":"${d.ratio.width}:${d.ratio.height}",""" +
                    """"acceptedShots":$a,"totalShots":$t,"lastOpenedAt":"${now - d.ago}"}"""
            }
            val gone = removed.keys.joinToString(",") { q(it) }
            fs.createDirectories("/h")
            fs.writeDurably("/h/index.json", """{"schemaVersion":1,"projects":[$rows],"removedPaths":[$gone]}""".encodeToByteArray())
            ProjectIndex(fs, "/h/index.json", clock)
        }
        val deletion = object : ProjectFolderDeletion {
            override fun inspect(root: String, folder: String): Deletion = refusal?.let { Deletion.Refused(it) }
                ?: Deletion.Ready(ProjectFolderDeletion.Target(folder, removed.getValue(folder), folder.substringAfterLast('/').removeSuffix(".autoflow"),
                    184_600_000, now, listOf("$folder/backups/backup.zip")))
            override fun delete(root: String, target: ProjectFolderDeletion.Target, pending: () -> Boolean): Deletion =
                if (deleteError) Deletion.Refused(Refusal.IO) else error("the screenshots never delete")
        }
        val packages = object : RenderPackages {
            override suspend fun pack(projectDir: String, folder: String, extras: List<io.github.alalkipgen.autoflow.platform.PackageFile>,
                zip: String, onProgress: (io.github.alalkipgen.autoflow.platform.BackupProgress) -> Unit) = PlatformResult.Unsupported("shots")
            override suspend fun unpack(zipPath: String, destDir: String) = PlatformResult.Unsupported("shots")
        }
        val model = DashboardModel(fs, store, index, SettingsStore(fs, "/h/settings.json"), FakePicker(), CurrentProject(),
            projectsHome = if (managed) HOME else null, deletion = deletion, packages = packages)
        val sources = demo.filter { it.frame != null }.associate { d ->
            dirs.getValue(d.name) to MediaThumbnails.Source(dirs.getValue(d.name), File(scenes, "${d.frame}.png").path, durationMs = d.durationMs)
        }
        fun dir(name: String) = dirs.getValue(name)
        val rows = jobRows(null, null, null, listOf(
            AiJob("kfp", AiJobKind.DUB_TTS, dir("Kung Fu Panda 4"), (25..40).map { "$it" }, AiJobState.RUNNING, initialItemCount = 40, savedClips = 24),
            AiJob("bagan", AiJobKind.MEDIA_TRANSCRIBE, dir("Bagan Vlog"), listOf("0"), AiJobState.QUEUED, initialItemCount = 1),
            AiJob("lesson", AiJobKind.SUB_TRANSLATE, dir("သင်ခန်းစာ 3"), (1..12).map { "$it" }, AiJobState.PAUSED, initialItemCount = 30),
            AiJob("ad", AiJobKind.TEXT_TTS, dir("ကြော်ငြာ အသံ"), (1..4).map { "$it" }, AiJobState.FAILED,
                "Gemini returned no audio (finishReason=OTHER)", initialItemCount = 6),
        ), language, dirs.entries.associate { (n, d) -> d to n })
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun UiLanguage.code() = if (this == UiLanguage.BURMESE) "my" else "en"
    /** The click action without a pointer: on a phone (touch mode) a tap does not focus, so no focus ring is drawn. */
    private fun ComposeUiTest.click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        .performSemanticsAction(SemanticsActions.OnClick)

    /** Waits until the number of loaded thumbnails has not changed for 2 s. */
    private fun ComposeUiTest.settle() {
        var last = -1; var since = System.nanoTime()
        waitUntil(timeoutMillis = 60_000) {
            val n = onAllNodesWithTag("media-thumb-image", useUnmergedTree = true).fetchSemanticsNodes().size
            if (n != last) { last = n; since = System.nanoTime() }
            System.nanoTime() - since > 2_000_000_000
        }
        waitForIdle()
    }

    /** Two passes: the first opens the dialog and measures its scroll range, the second draws it at the full height. */
    private fun shot(name: String, managed: Boolean = true, refusal: Refusal? = null, deleteError: Boolean = false, open: ComposeUiTest.() -> Unit) {
        val dir = out ?: return
        val f = Fixture(managed, refusal, deleteError)
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(SCALE, font), LocalUiLanguage provides language,
                LocalUiFontFamily provides BurmeseTestFont.family, LocalMediaThumbnails provides thumbs) {
                StudioV2Shell(StudioV2Dest.PROJECTS, {}, jobsBadgeValue(f.rows), WIDTH.toFloat(), ambient = true) {
                    DashboardScreen(f.model, {}, clock = clock, jobs = f.rows, thumbSource = { d -> f.sources[d] })
                }
            }
        }
        var extra = 0
        runDesktopComposeUiTest(width = (WIDTH * SCALE).toInt(), height = (HEIGHT * SCALE).toInt()) {
            setContent(content)
            waitForIdle()
            open()
            waitForIdle()
            extra = onNodeWithTag("glass-dialog").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue().toInt()
        }
        runDesktopComposeUiTest(width = (WIDTH * SCALE).toInt(), height = (HEIGHT * SCALE).toInt() + extra) {
            setContent(content)
            settle()
            open()
            settle()
            val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
            ImageIO.write(image, "png", File(dir, "${language.code()}/$name-$font.png").apply { parentFile.mkdirs() })
            assertEquals(emptyList(), textFitProblems((WIDTH * SCALE).toInt(), font, strict = true), "$name ${language.code()} $font")
        }
    }

    private val actions: ComposeUiTest.() -> Unit = { click("project-more-Dune: Part Two") }
    /** The list scrolled to its end (the removed row above Open package), then the removed row. */
    private val removed: ComposeUiTest.() -> Unit = { onNodeWithTag("projects-open-package").performScrollTo(); click("projects-open-removed") }
    private val project: ComposeUiTest.() -> Unit = { removed(); click("removed-project-0") }

    @Test fun dialogs() {
        if (out == null) return
        shot("1-project-actions", open = actions)
        shot("2-remove-confirm") { actions(); click("project-remove-action") }
        shot("3-removed-list", open = removed)
        shot("4-removed-project", open = project)
        shot("5-delete-confirm") { project(); click("project-delete-action") }
        shot("6a-not-managed", managed = false, open = actions)
        shot("6b-refused", refusal = Refusal.BUSY, open = project)
        shot("6c-delete-error", deleteError = true) {
            project(); click("project-delete-action"); click("project-delete-confirm")
        }
    }
}
