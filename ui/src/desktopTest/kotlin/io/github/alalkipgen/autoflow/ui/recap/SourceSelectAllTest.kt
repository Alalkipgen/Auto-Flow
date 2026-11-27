package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Select all (Group 2 A §2): every scene picked through the model's write path; Clear picks undoes it. */
@OptIn(ExperimentalTestApi::class)
class SourceSelectAllTest {
    private fun model(name: String, scope: CoroutineScope): Triple<RecapModel, ProjectStore, File> {
        val fixture = QaRecapFixture.create(Files.createTempDirectory(name).toFile())
        val fs = JvmFs(); val store = ProjectStore(fs)
        val recap = fixture.project.recap!!.copy(scenes = listOf(RecapScene(0, 2_000, true), RecapScene(2_000, 4_000), RecapScene(4_000, 6_000)))
        val saved = (store.save(fixture.dir.path, fixture.project.copy(recap = recap)) as CoreResult.Ok).value
        val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, saved)) }
        val session = ProjectSession(fs, store, ProjectIndex(fs, File(fixture.dir.parentFile, "index.json").path), current)
        return Triple(RecapModel(session, fs, null, null, scope), store, fixture.dir)
    }

    private fun picks(store: ProjectStore, dir: File) = ((store.open(dir.path) as CoreResult.Ok).value.recap!!.scenes.map { it.picked })

    @Test fun studioSelectAllPicksEverySceneAndHidesUntilAPickIsCleared() = runDesktopComposeUiTest(width = 412, height = 900) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val (model, store, dir) = model("select-all", scope)
        try {
            setContent { StudioRecapScreen(model, RecapStep.SOURCE, 412f, {}, RecapNavigation({}, {}, {})) }
            fun click(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("source-select-all").performScrollTo().assertIsDisplayed()
                .assertTextEquals(UiText.SELECT_ALL_SCENES.en)
            onNodeWithTag("scene-pick-1").performScrollTo().assertIsOff()
            click("source-select-all")
            waitUntil { model.state.value.recap!!.scenes.all { it.picked } }
            repeat(3) { onNodeWithTag("scene-pick-$it").performScrollTo().assertIsOn() }
            onNodeWithTag("source-select-all").assertDoesNotExist()
            assertEquals(listOf(true, true, true), picks(store, dir), "Select all is saved like a pick")
            click("source-clear-picks")
            waitUntil { model.state.value.recap!!.scenes.none { it.picked } }
            repeat(3) { onNodeWithTag("scene-pick-$it").performScrollTo().assertIsOff() }
            onNodeWithTag("source-clear-picks").assertDoesNotExist()
            onNodeWithTag("source-select-all").performScrollTo().assertIsDisplayed()
            assertEquals(listOf(false, false, false), picks(store, dir))
        } finally { scope.cancel() }
    }

    @Test fun classicSelectAllUsesTheSameModelWrite() = runDesktopComposeUiTest(width = 900, height = 1400) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val (model, store, dir) = model("select-all-classic", scope)
        try {
            setContent { CompositionLocalProvider(LocalUiLanguage provides UiLanguage.BURMESE) { RecapScreen(model) } }
            onNodeWithTag("source-select-all").performScrollTo().assertIsDisplayed()
                .assertTextEquals(UiText.SELECT_ALL_SCENES.text(UiLanguage.BURMESE)).performMouseInput { click() }
            waitUntil { model.state.value.recap!!.scenes.all { it.picked } }
            onNodeWithTag("source-select-all").assertDoesNotExist()
            assertEquals(listOf(true, true, true), picks(store, dir))
        } finally { scope.cancel() }
    }
}
