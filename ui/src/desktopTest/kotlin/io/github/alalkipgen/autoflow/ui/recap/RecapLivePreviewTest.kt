package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.MaskPreset
import io.github.alalkipgen.autoflow.core.recap.RecapMask
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.RecapVisual
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.MainScope

/** 5A-8 live preview: drag / pinch / mouse wheel on the blur-box preview edit the saved rect (D-42, D-50). */
@OptIn(ExperimentalTestApi::class)
class RecapLivePreviewTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)

    private fun open(): RecapModel {
        val p = (store.create("/p/R.autoflow", "R", Mode.MOVIE_RECAP) as CoreResult.Ok).value
        val recap = RecapV2(
            rightsConfirmed = true,
            movie = RecapMovie("source/movie.mp4", 9, 9_000, width = 1600, height = 900),
            scenes = listOf(RecapScene(0, 4_000, picked = true)),
            visual = RecapVisual(masks = listOf(RecapMask(MaskPreset.BOTTOM, MaskPreset.BOTTOM.rect), RecapMask(MaskPreset.LOGO_TR, MaskPreset.LOGO_TR.rect))),
        )
        current.set(OpenProject("/p/R.autoflow", p.copy(recap = recap)))
        return RecapModel(session, fs, null, null, MainScope()).also { it.refresh() }
    }

    private fun rect(p: MaskPreset) = current.state.value!!.project.recap!!.visual.masks.single { it.preset == p }.rect

    private fun near(a: Float, b: Float, tol: Float = 0.02f) = kotlin.math.abs(a - b) <= tol

    @Test
    fun dragMovesTheTouchedBoxOnly() = runComposeUiTest {
        val m = open()
        setContent {
            val open by current.state.collectAsState()
            Column { open?.project?.recap?.let { RecapPictureControls(it, idle = true, model = m) } }
        }
        val node = onNodeWithTag(MASK_PREVIEW_TAG)
        // Legacy mask placement stays pre-Looks/still; the separate output-timeline card owns playback.
        assertTrue(io.github.alalkipgen.autoflow.ui.components.moviePlaybackSupported)
        onNodeWithTag(PLAYBACK_TAG).assertDoesNotExist()
        // Touch the middle of the bottom strip (y 0.8–0.97) and drag up by 30 % of the preview height.
        node.performTouchInput {
            val start = Offset(width * 0.3f, height * 0.88f)
            down(start)
            moveTo(start + Offset(0f, -height * 0.15f))
            moveTo(start + Offset(0f, -height * 0.3f))
            up()
        }
        waitForIdle()
        val moved = rect(MaskPreset.BOTTOM)
        assertTrue(near(0.5f, moved[1]), "strip dragged up ≈ 0.3: $moved")
        assertTrue(near(1f, moved[2]) && near(0.17f, moved[3]), "same size: $moved")
        assertEquals(MaskPreset.LOGO_TR.rect, rect(MaskPreset.LOGO_TR), "the other box is untouched")
        onNodeWithText(boxHint(MaskPreset.BOTTOM)).assertExists()
    }

    @Test
    fun pinchResizesAroundTheFingers() = runComposeUiTest {
        val m = open()
        setContent {
            val open by current.state.collectAsState()
            Column { open?.project?.recap?.let { RecapPictureControls(it, idle = true, model = m) } }
        }
        // Logo box 0.76..0.98 × 0.03..0.16; two fingers inside it spread apart ×2.
        onNodeWithTag(MASK_PREVIEW_TAG).performTouchInput {
            val c = Offset(width * 0.87f, height * 0.095f)
            down(0, c - Offset(width * 0.02f, 0f))
            down(1, c + Offset(width * 0.02f, 0f))
            moveTo(0, c - Offset(width * 0.04f, 0f))
            moveTo(1, c + Offset(width * 0.04f, 0f))
            up(0); up(1)
        }
        waitForIdle()
        val r = rect(MaskPreset.LOGO_TR)
        assertTrue(r[2] > 0.3f && r[3] > 0.18f, "bigger: $r")
        assertTrue(r[0] + r[2] <= 1.0001f && r[1] >= 0f, "kept inside the picture: $r")
    }

    @Test
    fun mouseWheelResizesThePickedBox() = runComposeUiTest {
        val m = open()
        setContent {
            val open by current.state.collectAsState()
            Column { open?.project?.recap?.let { RecapPictureControls(it, idle = true, model = m) } }
        }
        val node = onNodeWithTag(MASK_PREVIEW_TAG)
        node.performTouchInput { down(Offset(width * 0.87f, height * 0.095f)); up() } // pick the logo box
        waitForIdle()
        assertEquals(MaskPreset.LOGO_TR.rect, rect(MaskPreset.LOGO_TR), "a tap only picks")
        node.performMouseInput { moveTo(Offset(width * 0.87f, height * 0.095f)); scroll(-1f) }
        waitForIdle()
        val r = rect(MaskPreset.LOGO_TR)
        assertTrue(near(0.242f, r[2], 0.005f) && near(0.143f, r[3], 0.005f), "wheel up = 10 % bigger: $r")
    }

    @Test
    fun exactFrameWithoutFfmpegSaysSo() = runComposeUiTest {
        val m = open()
        setContent {
            val open by current.state.collectAsState()
            Column { open?.project?.recap?.let { RecapPictureControls(it, idle = true, model = m) } }
        }
        // A semantics click: with a tall Myanmar fallback font (Windows CI) the button can sit below the test window.
        onNodeWithText(EXACT_FRAME).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(androidx.compose.ui.test.hasText("ffmpeg was not found", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }
}
