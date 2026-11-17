package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.shell.*
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ProjectWideShortcutsTest {
    @Test fun storyKeyboardUsesStableBlockTargetsAndNeverSavesDraft() = blockKeys(RecapStep.STORY)
    @Test fun voiceKeyboardUsesStableBlockTargetsAndNeverSavesDraft() = blockKeys(RecapStep.VOICE)

    private fun blockKeys(step: RecapStep) = runDesktopComposeUiTest(width = 840, height = 760) {
        val root = Files.createTempDirectory("wide-shortcuts").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val fixture = QaRecapFixture.create(root)
            fun bytes() = fixture.dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(fixture.dir).path to it.readBytes().toList() }
            val before = bytes()
            val fs = JvmFs(); val current = CurrentProject().apply { set(OpenProject(fixture.dir.path, fixture.project)) }
            val model = RecapModel(ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, File(root, "index.json").path), current), fs, null, null, scope)
            setContent { CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family, LocalUiLanguage provides UiLanguage.ENGLISH) {
                StudioV2Shell(StudioV2Dest.PROJECTS, {}, 0, 840f) { StudioRecapScreen(model, step, 840f, {}, RecapNavigation({}, {}, {})) }
            } }
            val list = onNodeWithTag("project-list").fetchSemanticsNode().boundsInRoot
            val preview = onNodeWithTag("project-preview").fetchSemanticsNode().boundsInRoot
            val inspector = onNodeWithTag("project-editor").fetchSemanticsNode().boundsInRoot
            assertTrue(list.right <= preview.left && preview.right <= inspector.left && inspector.right <= 840)
            val host = onNodeWithTag("project-shortcuts").assertIsFocused()
            host.performKeyInput { pressKey(Key.DirectionRight) }
            onNodeWithTag("voice-detail-number").assertTextEquals(UiText.V3_BLOCK_OF.text(UiLanguage.ENGLISH, "n" to 1, "total" to 2))
            onNodeWithTag("voice-detail-previous").assertIsNotEnabled()
            host.performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionRight) }
            onNodeWithTag("voice-detail-number").assertTextEquals(UiText.V3_BLOCK_OF.text(UiLanguage.ENGLISH, "n" to 2, "total" to 2))
            onNodeWithTag("voice-detail-next").assertIsNotEnabled()
            host.performKeyInput { pressKey(Key.DirectionRight) }
            onNodeWithTag("block-script-text").assertTextEquals("SECOND")
            onNodeWithTag("block-script-edit").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            val field = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("block-script-input")))
            field.assertIsDisplayed().performMouseInput { click() }; field.performTextInput(" typed space ")
            field.performKeyInput { pressKey(Key.Spacebar); pressKey(Key.DirectionLeft); pressKey(Key.DirectionRight) }
            onNodeWithTag("voice-detail-number").assertTextEquals(UiText.V3_BLOCK_OF.text(UiLanguage.ENGLISH, "n" to 2, "total" to 2))
            onNodeWithTag("block-script-cancel").assertIsDisplayed().performMouseInput { click() }
            onNodeWithTag("voice-detail-number").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            host.assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
            onNodeWithTag("block-script-text").assertTextEquals("FIRST")
            onNodeWithTag("voice-detail-previous").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            assertContentEquals(before.keys.toTypedArray(), bytes().keys.toTypedArray())
            assertEquals(before, bytes(), "opening, focus, arrows and cancelled typing leave every project file byte-identical")
        } finally { scope.cancel(); root.deleteRecursively() }
    }
}
