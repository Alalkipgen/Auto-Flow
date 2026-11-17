package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.*
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.projects.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class BlockClipsWorkflowTest {
    @Test fun actualVisibleSaveReopensTrimmedNativeRangeAndPreservesUnrelatedDataAndArtifacts() = saveAndReopen(RecapStep.VOICE)
    @Test fun storySaveThenExplicitApprovalUsesTheSameNativeTransaction() = saveAndReopen(RecapStep.STORY)
    private fun saveAndReopen(step: RecapStep) = runDesktopComposeUiTest(width = 412, height = 760) {
        val f = QaRecapFixture.create(Files.createTempDirectory("uiv3-cuts-save").toFile())
        val fs = JvmFs(); val store = ProjectStore(fs)
        val current = CurrentProject().apply { set(OpenProject(f.dir.path, f.project)) }
        val session = ProjectSession(fs, store, ProjectIndex(fs, File(f.dir.parentFile, "index.json").path), current)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = RecapModel(session, fs, null, null, scope)
        val old = File(f.dir, "artifacts").listFiles()!!.associate { it.name to it.readBytes().toList() }
        try {
            setContent { CompositionLocalProvider(LocalCompact provides true) {
                StudioRecapScreen(model, step, 412f, {}, RecapNavigation({}, {}, {}))
            } }
            fun click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            click("block-select-0"); click("block-clips-edit")
            click("clip-from-0")
            onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("clip-from-0"))).performTextReplacement("2.750")
            onNodeWithTag("block-clips-save").assertIsDisplayed().assertIsEnabled().performMouseInput { click() }
            onNodeWithTag("block-clips-form").assertDoesNotExist()
            val saved = (store.open(f.dir.path) as CoreResult.Ok).value
            val story = (session.readJson(saved.recap!!.story!!, RecapStory.serializer()) as CoreResult.Ok).value
            assertEquals(f.story.copy(blocks = listOf(f.story.blocks.first().copy(clips = listOf(RecapClip(2750, 4000), RecapClip(6000, 8000))), f.story.blocks.last())), story)
            assertEquals(f.project.recap!!.copy(story = saved.recap!!.story, storyApproved = false), saved.recap)
            old.forEach { (name, bytes) -> assertEquals(bytes, File(f.dir, "artifacts/$name").readBytes().toList(), name) }
            click("block-clips-edit")
            onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("clip-from-0"))).assertTextEquals("2.750")
            onNodeWithTag("block-clips-discard").assertIsDisplayed().performMouseInput { click() }
            if (step == RecapStep.STORY) {
                onNodeWithTag("story-approve").assertIsDisplayed().assertIsEnabled().performMouseInput { click() }
                val approved = (store.open(f.dir.path) as CoreResult.Ok).value
                assertEquals(saved.recap!!.copy(storyApproved = true), approved.recap)
                onNodeWithTag("recap-next").assertIsDisplayed().assertIsEnabled()
                old.forEach { (name, bytes) -> assertEquals(bytes, File(f.dir, "artifacts/$name").readBytes().toList(), name) }
            }
        } finally { scope.cancel() }
    }
}