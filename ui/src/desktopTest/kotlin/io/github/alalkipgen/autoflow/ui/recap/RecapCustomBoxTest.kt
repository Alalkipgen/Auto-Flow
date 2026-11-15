package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.MaskPreset
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.MainScope

/**
 * 5B-2 custom box on the Recap picture card: the "Custom box" chip adds a box, the ◀ ▶ / Wider / Narrower buttons and
 * a drag on the preview place it anywhere, and in the lower half it carries the captions. Every change is saved in
 * the project (`recap.visual`).
 */
@OptIn(ExperimentalTestApi::class)
class RecapCustomBoxTest {
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
        )
        current.set(OpenProject("/p/R.autoflow", p.copy(recap = recap)))
        return RecapModel(session, fs, null, null, MainScope()).also { it.refresh() }
    }

    private fun visual() = current.state.value!!.project.recap!!.visual
    private fun saved() = (store.open("/p/R.autoflow") as CoreResult.Ok).value.recap!!.visual
    private fun near(a: Float, b: Float, tol: Float = 0.02f) = kotlin.math.abs(a - b) <= tol

    @Test
    fun customBoxIsAddedPlacedAnywhereAndCarriesTheCaptionsLow() = runComposeUiTest {
        val m = open()
        setContent {
            val open by current.state.collectAsState()
            Column { open?.project?.recap?.let { RecapPictureControls(it, idle = true, model = m) } }
        }
        onNodeWithText(MaskPreset.CUSTOM.label).performClick()
        waitForIdle()
        assertEquals(listOf(MaskPreset.CUSTOM), visual().masks.map { it.preset })
        assertEquals(MaskPreset.CUSTOM.rect, visual().masks.single().rect)
        onNodeWithText(CAPTIONS_ON_BOX).assertDoesNotExist()

        onNodeWithText("◀").performClick()
        waitForIdle()
        onNodeWithText("Wider").performClick()
        waitForIdle()
        assertEquals(listOf(0.26f, 0.3f, 0.44f, 0.2f), visual().masks.single().rect)
        onNodeWithText("▶").performClick()
        onNodeWithText("Narrower").performClick()
        waitForIdle()
        assertEquals(listOf(0.3f, 0.3f, 0.4f, 0.2f), visual().masks.single().rect)

        // Drag the box (centre 0.5, 0.4) down-left by 0.3 of the preview.
        onNodeWithTag(MASK_PREVIEW_TAG).performTouchInput {
            val start = Offset(width * 0.5f, height * 0.4f)
            down(start)
            moveTo(start + Offset(-width * 0.1f, height * 0.15f))
            moveTo(start + Offset(-width * 0.2f, height * 0.3f))
            up()
        }
        waitForIdle()
        val r = visual().masks.single().rect
        assertTrue(near(0.1f, r[0]) && near(0.6f, r[1]) && near(0.4f, r[2]) && near(0.2f, r[3]), "dragged: $r")
        assertEquals(MaskPreset.CUSTOM, visual().captionMask?.preset, "lower half: the captions sit on it")
        onNodeWithText(CAPTIONS_ON_BOX).assertExists()
        assertEquals(visual(), saved(), "saved in the project file")

        onNodeWithText(MaskPreset.CUSTOM.label + ": drag to move · pinch or mouse wheel to resize").assertExists()
    }
}
