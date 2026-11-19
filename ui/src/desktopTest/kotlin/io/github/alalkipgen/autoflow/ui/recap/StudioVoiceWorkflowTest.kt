package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.ai.TtsProvider
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.ai.fake.FakeTtsProvider
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.recap.AiRunTiming
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.jobs.JobsActions
import io.github.alalkipgen.autoflow.ui.jobs.JobsScreen
import io.github.alalkipgen.autoflow.ui.jobs.jobRows
import io.github.alalkipgen.autoflow.ui.projects.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class StudioVoiceWorkflowTest {
    @Test fun singleVoiceProgressIsVisibleInVoiceAtLargeBurmeseFont() =
        voiceProgress(RecapStep.VOICE, UiLanguage.BURMESE, 2f)

    @Test fun singleVoiceProgressIsVisibleInStoryWithItsCancel() =
        voiceProgress(RecapStep.STORY, UiLanguage.ENGLISH, 1f)

    private fun voiceProgress(step: RecapStep, language: UiLanguage, font: Float) = runDesktopComposeUiTest(width = 412, height = 760) {
        val fixture = QaRecapFixture.create(Files.createTempDirectory("uiv3-voice-progress").toFile())
        val fs = JvmFs()
        val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, fixture.project)) }
        val session = ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, File(fixture.dir.parentFile, "index.json").path), current)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tts = object : TtsProvider by FakeTtsProvider() {
            override suspend fun speak(text: String, voice: VoiceRef, lang: String, style: String?, lockedModel: String?) =
                awaitCancellation()
        }
        val model = RecapModel(session, fs, null, null, scope, tts = tts)
        val manifest = File(fixture.dir, "project.json")
        val before = manifest.readBytes()
        val artifacts = File(fixture.dir, "artifacts").list().orEmpty().sorted()
        var showJobs by mutableStateOf(false)
        var openedId: String? = null
        try {
            setContent { CompositionLocalProvider(LocalCompact provides true, LocalUiLanguage provides language, LocalDensity provides Density(1f, font)) {
                if (showJobs) JobsScreen(jobRows(model.state.value, null, null, emptyList(), language), scope,
                    { openedId = it.jobId; showJobs = false }, actions = JobsActions(scope, recap = model))
                else StudioRecapScreen(model, step, 412f, {}, RecapNavigation({}, {}, {}))
            } }
            val first = if (step == RecapStep.VOICE) "voice-open-0" else "block-select-0"
            onNodeWithTag(first).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            val request = onNodeWithTag("voice-block-request")
            if (step == RecapStep.STORY) request.performScrollTo()
            request.assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("voice-block-confirm").assertIsDisplayed().performMouseInput { click() }
            val inside = hasAnyAncestor(hasTestTag("recap-voice-run"))
            onNodeWithTag("recap-voice-run").assertIsDisplayed()
            val indeterminate = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)
            onNode(inside and indeterminate, useUnmergedTree = true).assertIsDisplayed()
            onNode(inside and hasText(My.breakSyllables(UiText.JOB_WAITING_GEMINI.text(language)), substring = true)).assertIsDisplayed()
            val id = assertNotNull(model.state.value.voiceRun).id
            runOnIdle { showJobs = true }
            onNodeWithTag("job-open-$id").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            assertEquals(id, openedId)
            onNodeWithTag("recap-voice-run").assertIsDisplayed()
            onNodeWithTag("recap-voice-run-cancel").assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("recap-voice-run").assertDoesNotExist()
            assertFalse(id in AiRunTiming.active.value)
            assertTrue(model.foregroundVoiceRuns.value.isEmpty())
            assertContentEquals(before, manifest.readBytes())
            assertEquals(artifacts, File(fixture.dir, "artifacts").list().orEmpty().sorted())
        } finally { scope.cancel() }
    }

    @Test fun visibleNativeScriptSaveReopensWithSpeakAsAndKeepsAllTakesLayersAndOtherBlocks() = runDesktopComposeUiTest(width = 412, height = 760) {
        val fixture = QaRecapFixture.create(Files.createTempDirectory("uiv3-script-save").toFile())
        val fs = JvmFs(); val store = ProjectStore(fs)
        val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, fixture.project)) }
        val session = ProjectSession(fs, store, ProjectIndex(fs, File(fixture.dir.parentFile, "index.json").path), current)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = RecapModel(session, fs, null, null, scope)
        try {
            setContent { CompositionLocalProvider(LocalCompact provides true) {
                StudioRecapScreen(model, RecapStep.VOICE, 412f, {}, RecapNavigation({}, {}, {}))
            } }
            onNodeWithTag("voice-open-0").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("block-script-edit").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithContentDescription("Caption text").performScrollTo().assertIsDisplayed().performTextReplacement("စာတန်း အသစ်")
            onNodeWithContentDescription("Speak-as override (optional)").performScrollTo().assertIsDisplayed().performTextReplacement("အသံထွက် အသစ်")
            onNodeWithTag("block-script-save").assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("block-script-input").assertDoesNotExist()
            val reopened = (store.open(fixture.dir.path) as CoreResult.Ok).value.recap!!
            val story = (session.readJson(reopened.story!!, io.github.alalkipgen.autoflow.core.recap.RecapStory.serializer()) as CoreResult.Ok).value
            assertEquals(fixture.story.blocks.first().copy(textMy = "စာတန်း အသစ်", speakMy = "အသံထွက် အသစ်"), story.blocks.first())
            assertEquals(fixture.story.blocks.last(), story.blocks.last())
            assertEquals(fixture.project.recap!!.copy(story = reopened.story, storyApproved = false), reopened)
            onNodeWithTag("block-script-text").assertTextEquals("စာတန်း အသစ်")
        } finally { scope.cancel() }
    }
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