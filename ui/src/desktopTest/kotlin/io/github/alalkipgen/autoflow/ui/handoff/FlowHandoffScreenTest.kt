package io.github.alalkipgen.autoflow.ui.handoff

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.CreditEntry
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import kotlin.test.Test
import kotlin.test.assertEquals

/** The credit question appears after an import; one tap logs the suggested value and the counter updates. */
@OptIn(ExperimentalTestApi::class)
class FlowHandoffScreenTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val model = FlowHandoffModel(
        session, { PlatformResult.Ok(Unit) }, { PlatformResult.Ok(Unit) }, FakePicker(), nowMs = { 1_794_477_600_000L },
    )

    @Test
    fun importedVideoAsksForCreditsAndTheCounterCounts() = runComposeUiTest {
        val p = (store.create("/p/Rain.autoflow", "Rain", Mode.MUSIC_VIDEO, ProjectSettings()) as CoreResult.Ok).value
        current.set(OpenProject("/p/Rain.autoflow", p))
        session.save(p.copy(shots = listOf(Shot(1, 0, 8_000))))
        model.importVideoBytes(session.open!!.project, session.open!!.project.shots[0], byteArrayOf(1), "mp4")

        setContent { FlowHandoffScreen(model, readBytes = { null }) }
        onNodeWithText(creditSummary(0, 0)).assertExists()
        onNodeWithText(CREDITS_QUESTION).performScrollTo().assertExists()
        onNodeWithText("20 (Fast)").performScrollTo().performClick()
        waitForIdle()

        assertEquals(listOf(CreditEntry("2026-11-12", 20, 1)), session.open!!.project.flowCredits.manualLog)
        onNodeWithText(creditSummary(20, 20)).assertExists()
        onNodeWithText(CREDITS_QUESTION).assertDoesNotExist()
        onNodeWithText("Credits logged: 20").performScrollTo().assertExists()
        onNodeWithText(LOG_CREDITS_BUTTON).performScrollTo().assertExists()
    }
}
