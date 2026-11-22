package io.github.alalkipgen.autoflow.ui.projects

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.LocalMediaThumbnails
import io.github.alalkipgen.autoflow.ui.media.MediaThumbnails
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Dest
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Shell
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P9-G1a (#286): a project card draws its thumbnail's frame as the card art (DESIGN.md §9.10) also when the frame is
 * already in memory — coming back to Projects showed the frames in the thumbnails but left most cards untinted. Its
 * captions are then MutedOnArt; a card without a frame keeps Muted.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectCardArtTest {
    private val root = Files.createTempDirectory("card-art").toFile()
    private val fs = JvmFs()
    private val colours = listOf(0xd97706, 0x2563eb, 0x16a34a, 0xdb2777)
    private val projects = colours.indices.map { i ->
        val dir = File(root, "p$i.autoflow").path
        dir to (ProjectStore(fs).create(dir, "Project $i", Mode.SUBTITLES) as CoreResult.Ok).value
    }
    private val plain = File(root, "plain.autoflow").path.let { dir ->
        dir to (ProjectStore(fs).create(dir, "Plain", Mode.SUBTITLES) as CoreResult.Ok).value
    }
    private val sources = projects.associate { (dir, _) ->
        dir to MediaThumbnails.Source(dir, File(dir, "cover.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }.path)
    }
    private val locator = object : ToolLocator {
        override fun findFfmpeg() = "/fake/ffmpeg"
        override fun findVlc(): String? = null
        override fun isVlcDir(dir: String) = false
    }
    // The fake ffmpeg writes one plain JPEG per source (its colour from the source folder).
    private val runner = ToolRunner { cmd, _, _, _, _ ->
        val i = projects.indexOfFirst { (dir, _) -> cmd.any { it.startsWith(dir) } }
        File(cmd.last()).writeBytes(jpeg(colours[i.coerceAtLeast(0)]))
        ToolExec.Done(0, "", emptyList(), 1, false)
    }
    private val thumbs = MediaThumbnails(fs, MediaTools(SettingsStore(fs, File(root, "settings.json").path), locator, runner),
        File(root, "cache").path)

    @Test fun everyCardWithAFrameDrawsItAsArtAlsoWhenTheFrameIsAlreadyInMemory() {
        val index = ProjectIndex(fs, File(root, "index.json").path)
        (listOf(plain) + projects).forEach { (dir, project) -> index.record(dir, project) }
        val model = DashboardModel(fs, ProjectStore(fs), index, SettingsStore(fs, File(root, "settings.json").path), FakePicker(), CurrentProject())
        // The first screen makes the frames; the second (a new screen, as after a tab switch) gets them from memory.
        listOf("made", "from memory").forEach { pass ->
            runDesktopComposeUiTest(width = 720, height = 2000) {
                setContent {
                    CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(2f, 1f),
                        LocalMediaThumbnails provides thumbs) {
                        StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 360f, ambient = true) {
                            DashboardScreen(model, {}, thumbSource = { dir -> sources[dir] })
                        }
                    }
                }
                waitUntil(timeoutMillis = 30_000) {
                    onAllNodesWithTag("media-thumb-image", useUnmergedTree = true).fetchSemanticsNodes().size == projects.size
                }
                waitForIdle()
                assertEquals(projects.size, onAllNodesWithTag("glass-art", useUnmergedTree = true).fetchSemanticsNodes().size, pass)
                fun captions(name: String) = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
                    hasAnyAncestor(hasTestTag("project-row-$name")), useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { node ->
                    mutableListOf<TextLayoutResult>().also { node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }
                        .firstOrNull()?.layoutInput?.style?.color?.takeIf { it == AutoFlowColors.Muted || it == GlassColors.MutedOnArt }
                }
                projects.forEach { (_, project) -> assertEquals(setOf(GlassColors.MutedOnArt), captions(project.name).toSet(), "${project.name} $pass") }
                assertEquals(setOf(AutoFlowColors.Muted), captions("Plain").toSet(), "Plain $pass")
            }
        }
    }

    private fun jpeg(rgb: Int) = ByteArrayOutputStream().also { out ->
        val image = BufferedImage(240, 136, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = java.awt.Color(rgb); fillRect(0, 0, 240, 136); dispose() }
        check(ImageIO.write(image, "jpg", out))
    }.toByteArray()
}
