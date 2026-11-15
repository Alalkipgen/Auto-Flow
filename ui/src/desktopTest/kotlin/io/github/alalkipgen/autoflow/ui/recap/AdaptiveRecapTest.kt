package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Dest
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Shell
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** Actual production shell + step header + selected-block editor, not a hand-drawn screenshot fixture. */
@OptIn(ExperimentalTestApi::class)
class AdaptiveRecapTest {
    private fun model(): RecapModel {
        val fs = InMemoryFileSystem()
        val store = ProjectStore(fs)
        val dir = "/p/layout.autoflow"
        val p = (store.create(dir, "Layout", Mode.MOVIE_RECAP) as CoreResult.Ok).value
        val story = RecapStory(source = "manual", blocks = listOf(
            RecapBlock("one", listOf(RecapClip(0, 3_000)), "first block written text"),
            RecapBlock("two", listOf(RecapClip(3_000, 6_000)), "second block written text"),
        ))
        val id = (ArtifactStore(fs, dir).put(CoreJson.encodeToString(RecapStory.serializer(), story).encodeToByteArray(), ArtifactKind.RECAP_SCRIPT, "json") as CoreResult.Ok).value.id
        val recap = RecapV2(rightsConfirmed = true, movie = RecapMovie("source/movie.mp4", 1, 8_000), scenes = listOf(RecapScene(0, 3_000, picked = true), RecapScene(3_000, 6_000, picked = true)), story = id, storyApproved = true)
        val saved = (store.save(dir, p.copy(recap = recap)) as CoreResult.Ok).value
        val current = CurrentProject().apply { set(OpenProject(dir, saved)) }
        return RecapModel(ProjectSession(fs, store, ProjectIndex(fs, "/index.json"), current), fs, null, null, CoroutineScope(Dispatchers.Unconfined))
    }

    @Test fun phoneFont100() = layout(412, 1f)
    @Test fun phoneFont130() = layout(412, 1.3f)
    @Test fun phoneFont200() = layout(412, 2f)
    @Test fun wideFont100() = layout(840, 1f)
    @Test fun wideFont130() = layout(840, 1.3f)
    @Test fun wideFont200() = layout(840, 2f)

    private fun layout(width: Int, font: Float) = runDesktopComposeUiTest(width = width, height = 1000) {
        val m = model()
        var step by mutableStateOf(RecapStep.STORY)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, font)) {
                StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, width.toFloat()) {
                    Column(Modifier.fillMaxSize()) {
                        RecapStepHeader(step, { step = step.previous() ?: step }, { step = step.next() ?: step }, {})
                        Box(Modifier.weight(1f)) { StudioRecapScreen(m, step, width.toFloat(), {}) }
                    }
                }
            }
        }
        onNodeWithText("Story · 2/5").assertIsDisplayed()
        onNodeWithTag(if (width >= 840) "project-three-panes" else "project-single-pane").assertExists()
        if (width >= 840) {
            val a = onNodeWithTag("project-list").fetchSemanticsNode().boundsInRoot
            val b = onNodeWithTag("project-editor").fetchSemanticsNode().boundsInRoot
            val c = onNodeWithTag("project-preview").fetchSemanticsNode().boundsInRoot
            assertTrue(a.right <= b.left && b.right <= c.left, "list, editor and preview must not overlap")
            assertTrue(c.right <= width, "rail must be included in the 840 dp breakpoint")
        }
        onNodeWithTag("block-select-1").performScrollTo().performClick()
        onNodeWithText("second block written text").assertExists()
        onNodeWithText("first block written text").assertDoesNotExist()
        onNodeWithText("✎", useUnmergedTree = true).performScrollTo().performClick()
        onNodeWithContentDescription("Narration for scene 2").performScrollTo().performTextReplacement("edited second block")
        onNodeWithText("Save").performScrollTo().performClick()
        waitForIdle()
        assertEquals("first block written text", m.state.value.story!!.blocks[0].textMy)
        assertEquals("edited second block", m.state.value.story!!.blocks[1].textMy)
        for (tag in listOf("recap-back", "recap-next", "classic-recap")) {
            val node = onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            assertTrue(node.boundsInRoot.height >= 48f, "$tag must have a 48 dp touch target")
            assertTrue(node.boundsInRoot.left >= 0f && node.boundsInRoot.right <= width.toFloat())
            val label = when (tag) { "recap-back" -> "← Back"; "recap-next" -> "Next: Voice →"; else -> "Classic Recap" }
            val text = onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertTrue(layouts.isNotEmpty(), "must inspect actual laid-out text for $tag")
            // Skia can report didOverflowWidth for identical 46px/46px widths. Check actual lines,
            // including their final character, with only half a physical pixel of rounding tolerance.
            layouts.forEach { layout ->
                assertFalse(layout.multiParagraph.didExceedMaxLines, "$tag truncated at scale $font")
                assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                for (line in 0 until layout.lineCount) {
                    assertTrue(layout.getLineLeft(line) >= -0.5f && layout.getLineRight(line) <= layout.size.width + 0.5f,
                        "$tag horizontal clipping at scale $font: ${layout.size}")
                    assertTrue(layout.getLineBottom(line) <= layout.size.height + 0.5f, "$tag vertical clipping at scale $font")
                }
            }
        }
        val dir = File(System.getenv("AUTOFLOW_SCREENSHOTS_DIR") ?: "build/reports/studio-layout").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "recap-$width-font${(font * 100).toInt()}.png"))
        onNodeWithTag("recap-next").performClick()
        onNodeWithText("Voice · 3/5").assertIsDisplayed()
        onNodeWithTag("recap-back").performClick()
        onNodeWithText("Story · 2/5").assertIsDisplayed()
    }

    @Test fun below840IsStillSinglePane() = runDesktopComposeUiTest(width = 839, height = 500) {
        setContent { ProjectPanes(839f, { AfText("list") }, { AfText("editor") }, { AfText("preview") }) }
        onNodeWithTag("project-single-pane").assertExists()
        onNodeWithText("editor").assertExists()
        onNodeWithText("list").assertDoesNotExist()
    }
}
