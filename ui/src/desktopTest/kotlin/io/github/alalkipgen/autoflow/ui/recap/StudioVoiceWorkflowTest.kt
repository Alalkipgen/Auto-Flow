package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.ai.fake.FakeTtsProvider
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.projects.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class StudioVoiceWorkflowTest {
    @Test fun remainingVoiceRequiresVisibleConfirmationThenPersistsOnlyTheMissingNativeBlock() = runDesktopComposeUiTest(width = 412, height = 760) {
        val fixture = QaRecapFixture.create(Files.createTempDirectory("uiv3-voice-save").toFile())
        val fs = JvmFs(); val store = ProjectStore(fs); val original = fixture.project.recap!!
        val missing = fixture.story.blocks.last().id
        val prepared = (store.save(fixture.dir.path, fixture.project.copy(recap = original.copy(
            takes = original.takes.filter { it.block != missing }, chosenTakes = original.chosenTakes - missing))) as CoreResult.Ok).value
        val before = File(fixture.dir, "project.json").readBytes()
        val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, prepared)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val tts = FakeTtsProvider()
        val model = RecapModel(ProjectSession(fs, store, ProjectIndex(fs, File(fixture.dir.parentFile, "index.json").path), current),
            fs, null, null, scope, tts = tts)
        try {
            setContent { CompositionLocalProvider(LocalCompact provides true) {
                StudioRecapScreen(model, RecapStep.VOICE, 412f, {}, RecapNavigation({}, {}, {}))
            } }
            onNodeWithTag("voice-remaining").assertIsDisplayed().assertIsEnabled().performMouseInput { click() }
            onNodeWithTag("voice-confirm").assertIsDisplayed()
            assertEquals(0, tts.calls)
            onNodeWithTag("voice-confirm-cancel").assertIsDisplayed().performMouseInput { click() }
            assertContentEquals(before, File(fixture.dir, "project.json").readBytes())
            onNodeWithTag("voice-remaining").assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("voice-confirm").assertIsDisplayed().performMouseInput { click() }
            waitUntil(timeoutMillis = 5_000) { !model.state.value.busy && model.state.value.currentTake(1) != null }
            val reopened = (store.open(fixture.dir.path) as CoreResult.Ok).value.recap!!
            assertEquals(1, tts.calls)
            assertEquals(original.chosenTakes[fixture.story.blocks.first().id], reopened.chosenTakes[fixture.story.blocks.first().id])
            assertEquals(original.takes.first(), reopened.takes.first())
            assertEquals(missing, reopened.takes.last().block)
            assertEquals(reopened.takes.last().audio, reopened.chosenTakes[missing])
            assertEquals(original.story, reopened.story)
            assertEquals(original.layers, reopened.layers)
            assertTrue(reopened.storyApproved)
        } finally { scope.cancel() }
    }
}